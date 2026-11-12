import base64
import contextlib
import hashlib
import http.client
import importlib
import io
import json
import os
from pathlib import Path
import stat
import socket
import errno
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch, MagicMock
import urllib.parse

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
login = importlib.import_module('platform_admin_login')


def encoded(value):
    value = value if isinstance(value, bytes) else json.dumps(value).encode()
    return base64.urlsafe_b64encode(value).decode().rstrip('=')


class LoginParametersTest(unittest.TestCase):
    def test_rfc7636_s256_vector(self):
        self.assertEqual('E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM',
                         login.challenge('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk'))

    def test_login_is_fresh_and_uses_exact_contract(self):
        first, second = login.fresh_login(), login.fresh_login()
        self.assertTrue(all(first[i] != second[i] for i in range(3)))
        verifier, state, nonce, url = first
        self.assertRegex(verifier, r'^[A-Za-z0-9_-]{43,128}$')
        parsed = urllib.parse.urlsplit(url)
        self.assertEqual(login.ISSUER + '/protocol/openid-connect/auth', parsed._replace(query='').geturl())
        values = urllib.parse.parse_qs(parsed.query)
        self.assertEqual(['payflow-platform-console'], values['client_id'])
        self.assertEqual(['http://localhost:8182/callback'], values['redirect_uri'])
        self.assertEqual(['openid merchant.bindings.manage'], values['scope'])
        self.assertEqual(['S256'], values['code_challenge_method'])
        self.assertTrue(values['state'] == [state] and values['nonce'] == [nonce])
        self.assertTrue(values['code_challenge'] == [login.challenge(verifier)])
        self.assertNotIn(verifier, url)
        self.assertNotIn('client_secret', values)

    def test_valid_callback(self):
        target = '/callback?' + urllib.parse.urlencode(dict(state='expected-state', code='fake-code', iss=login.ISSUER))
        self.assertTrue(login.callback_code(target, 'expected-state') == 'fake-code')

    def test_invalid_callback_variants(self):
        for target in [
            '/callback?state=wrong&code=fake', '/callback?code=fake',
            '/callback?state=expected-state', '/callback?state=expected-state&code=',
            '/callback?state=expected-state&code=a&code=b',
            '/callback?state=expected-state&state=expected-state&code=fake',
            '/callback?state=expected-state&code=fake&iss=https%3A%2F%2Fother.invalid',
            '/other?state=expected-state&code=fake',
            'http://evil.invalid/callback?state=expected-state&code=fake',
            '/callback?state=expected-state&code=fake#fragment',
            '/callback?state=%E2%98%83&code=fake',
            '/callback?state=expected-state&code=' + 'x' * 4097,
        ]:
            with self.subTest(kind=target.split('?')[0]), self.assertRaises(ValueError):
                login.callback_code(target, 'expected-state')

    def test_oauth_failure_is_not_a_code(self):
        with self.assertRaises(login.LoginDenied):
            login.callback_code('/callback?state=expected-state&error=access_denied&error_description=private', 'expected-state')


class CallbackListenerTest(unittest.TestCase):
    def setUp(self):
        self.outcome = {}
        self.server = login.QuietHTTPServer(('127.0.0.1', 0), login.callback_handler('expected-state', self.outcome))
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.worker.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.worker.join(timeout=3)

    def send(self, path, host='localhost:8182'):
        with contextlib.redirect_stderr(io.StringIO()) as err:
            connection = http.client.HTTPConnection(*self.server.server_address, timeout=5)
            connection.request('GET', path, headers={'Host': host})
            response = connection.getresponse()
            body = response.read().decode()
            status = response.status
            self.assertEqual('no-store', response.getheader('Cache-Control'))
            connection.close()
        self.assertEqual('', err.getvalue())
        self.assertNotIn('fake-code', body)
        self.assertNotIn('expected-state', body)
        return status

    def test_invalid_state_does_not_end_login_and_valid_callback_succeeds(self):
        self.assertEqual(400, self.send('/callback?state=wrong&code=fake-code'))
        self.assertFalse(self.outcome)
        self.assertEqual(200, self.send('/callback?state=expected-state&code=fake-code'))
        self.assertTrue(self.outcome.get('code') == 'fake-code')
        self.assertEqual('127.0.0.1', self.server.server_address[0])

    def test_host_validation(self):
        self.assertEqual(400, self.send('/callback?state=expected-state&code=fake-code', 'evil.invalid:8182'))
        self.assertFalse(self.outcome)

    def test_denied_login_does_not_export_a_code(self):
        self.assertEqual(400, self.send('/callback?state=expected-state&error=access_denied'))
        self.assertEqual({'denied': True}, self.outcome)


class SignedAdministratorTokenTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        cls.key = Path(cls.directory.name) / 'key.pem'
        subprocess.run(['openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048', '-out', str(cls.key)], check=True, capture_output=True)
        modulus = subprocess.run(['openssl', 'rsa', '-in', str(cls.key), '-modulus', '-noout'], check=True, capture_output=True).stdout.decode().strip().split('=')[1]
        cls.jwks = {'keys': [{'kid': 'test', 'kty': 'RSA', 'use': 'sig', 'n': encoded(bytes.fromhex(modulus)), 'e': 'AQAB'}]}

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def signed(self, claims):
        content = encoded({'alg': 'RS256', 'kid': 'test'}) + '.' + encoded(claims)
        signature = subprocess.run(['openssl', 'dgst', '-sha256', '-sign', str(self.key)], input=content.encode(), capture_output=True, check=True).stdout
        return content + '.' + encoded(signature)

    def tokens(self, access_changes=None, identity_changes=None):
        base = dict(iss=login.ISSUER, sub='human-subject', azp=login.CLIENT,
                    iat=int(time.time()) - 1, exp=int(time.time()) + 300)
        access = dict(base, aud=login.AUDIENCE, typ='Bearer', scope='openid ' + login.SCOPE,
                      resource_access={login.AUDIENCE: {'roles': ['platform-admin']}})
        identity = dict(base, aud=login.CLIENT, typ='ID', nonce='expected-nonce')
        access.update(access_changes or {})
        identity.update(identity_changes or {})
        return dict(access_token=self.signed(access), id_token=self.signed(identity),
                    token_type='Bearer', refresh_token='fake-refresh-never-export')

    def accept(self, tokens):
        with patch.object(login, 'local_json', return_value=self.jwks):
            return login.accepted_access_token(tokens, 'expected-nonce')

    def test_accepts_correct_signed_admin_token(self):
        tokens = self.tokens()
        self.assertTrue(self.accept(tokens) == tokens['access_token'])

    def test_rejects_wrong_identity_permissions_and_times(self):
        changes = [dict(iss='https://other.invalid'), dict(aud='payflow-payment-api'),
                   dict(azp='payflow-portal'), dict(azp='payflow-payment-service'),
                   dict(typ='ID'), dict(sub=123), dict(sub=' '), dict(iat=None),
                   dict(exp=int(time.time()) - 10), dict(exp=float('nan')), dict(nbf=True), dict(nbf=int(time.time()) + 100),
                   dict(scope='merchant.lifecycle.manage'), dict(scope=login.SCOPE), dict(scope=login.SCOPE + '.extra'),
                   dict(resource_access={'payflow-payment-api': {'roles': ['platform-admin']}}),
                   dict(resource_access={login.AUDIENCE: {'roles': ['merchant-admin']}}),
                   dict(resource_access={login.AUDIENCE: {'roles': 'platform-admin'}})]
        for change in changes:
            with self.subTest(field=next(iter(change))), self.assertRaises((ValueError, TypeError)):
                self.accept(self.tokens(change))

    def test_rejects_wrong_nonce_or_mismatched_id_subject(self):
        for change in [dict(nonce='wrong'), dict(sub='different'), dict(aud='other-client')]:
            with self.assertRaises(ValueError):
                self.accept(self.tokens(identity_changes=change))

    def test_missing_subject_is_rejected_not_inferred_from_id_token(self):
        tokens = self.tokens()
        claims = json.loads(login.base64.urlsafe_b64decode(tokens['access_token'].split('.')[1] + '==='))
        claims.pop('sub')
        tokens['access_token'] = self.signed(claims)
        with self.assertRaises(login.HelperFailure) as failure:
            self.accept(tokens)
        self.assertEqual('access-subject-missing', failure.exception.stage)
        # The ID token still has a subject: copying it into the access token is forbidden.

    def test_correct_audience_never_substitutes_for_role_or_scope(self):
        for change, expected in [({'resource_access': {}}, 'role-validation'),
                                 ({'scope': 'openid'}, 'scope-validation'),
                                 ({'aud': None}, 'access-audience')]:
            with self.assertRaises(login.HelperFailure) as failure:
                self.accept(self.tokens(access_changes=change))
            self.assertEqual(expected, failure.exception.stage)

    def test_access_diagnostic_identifiers_never_include_claim_values(self):
        for changes, expected in [({'iss': 'https://private-issuer-marker.invalid'}, 'access-issuer'),
                                  ({'aud': 'private-audience-marker'}, 'access-audience'),
                                  ({'azp': 'private-client-marker'}, 'access-client'),
                                  ({'typ': 'private-type-marker'}, 'access-token-type')]:
            with self.assertRaises(login.HelperFailure) as failure:
                self.accept(self.tokens(access_changes=changes))
            self.assertEqual(expected, failure.exception.stage)
            self.assertNotIn('private-', str(failure.exception))

    def test_rejects_signature_tampering(self):
        tokens = self.tokens()
        parts = tokens['access_token'].split('.')
        tokens['access_token'] = parts[0] + '.' + encoded({'sub': 'tampered'}) + '.' + parts[2]
        with self.assertRaisesRegex(ValueError, 'signature'):
            self.accept(tokens)

    def test_rejects_missing_tokens_and_wrong_response_type(self):
        for field in ['access_token', 'id_token', 'token_type']:
            tokens = self.tokens()
            tokens.pop(field)
            with self.assertRaises((ValueError, KeyError)):
                self.accept(tokens)


class FailureAndExportTest(unittest.TestCase):
    def ephemeral_listeners(self):
        original = login.loopback_servers
        servers = []
        def create(handler):
            result = original(handler, 0)
            servers.extend(result)
            return result
        return patch.object(login, 'loopback_servers', side_effect=create), servers

    def test_port_conflict_fails_before_opening_browser(self):
        with patch.object(login, 'loopback_servers', side_effect=OSError('private-diagnostic')), patch.object(login.webbrowser, 'open') as browser:
            with self.assertRaises(login.HelperFailure) as failure:
                login.receive_code('http://localhost/auth', 'state')
            self.assertEqual('listener-bind', failure.exception.stage)
            browser.assert_not_called()

    def test_browser_failure_closes_listener(self):
        listeners, servers = self.ephemeral_listeners()
        with listeners, patch.object(login.webbrowser, 'open', return_value=False):
            with self.assertRaises(login.HelperFailure) as failure:
                login.receive_code('http://localhost/auth', 'state')
            self.assertEqual('browser-open', failure.exception.stage)
        self.assertTrue(all(server.socket.fileno() == -1 for server in servers))

    def test_timeout_closes_listener_even_if_browser_launch_blocks(self):
        listeners, servers = self.ephemeral_listeners()
        release = threading.Event()
        def blocked_browser(url):
            release.wait(3)
            return True
        started = time.monotonic()
        try:
            with listeners, patch.object(login.webbrowser, 'open', side_effect=blocked_browser):
                with self.assertRaises(login.HelperFailure) as failure:
                    login.receive_code('http://localhost/auth', 'state', timeout_seconds=0.15)
                self.assertEqual('callback-timeout', failure.exception.stage)
            self.assertLess(time.monotonic() - started, 2)
            self.assertTrue(all(server.socket.fileno() == -1 for server in servers))
        finally:
            release.set()

    def test_callback_is_served_before_browser_open_returns(self):
        listeners, servers = self.ephemeral_listeners()
        completed = threading.Event()
        def browser_waiting_for_page(url):
            # Models a launcher waiting for page completion before returning.
            connection = http.client.HTTPConnection('127.0.0.1', servers[0].server_address[1], timeout=2)
            connection.request('GET', '/callback?state=expected-state&code=fake-code', headers={'Host': 'localhost:8182'})
            response = connection.getresponse()
            response.read()
            connection.close()
            self.assertEqual(200, response.status)
            completed.set()
            return True
        with listeners, patch.object(login.webbrowser, 'open', side_effect=browser_waiting_for_page):
            self.assertTrue(login.receive_code('http://localhost/auth', 'expected-state', timeout_seconds=3) == 'fake-code')
        self.assertTrue(completed.wait(2))
        self.assertTrue(all(server.socket.fileno() == -1 for server in servers))

    def test_listener_stays_active_while_user_logs_in(self):
        listeners, servers = self.ephemeral_listeners()
        def delayed_callback():
            time.sleep(0.15)
            connection = http.client.HTTPConnection('127.0.0.1', servers[0].server_address[1], timeout=2)
            connection.request('GET', '/callback?state=expected-state&code=fake-code', headers={'Host': 'localhost:8182'})
            response = connection.getresponse(); response.read(); connection.close()
        def launch(url):
            threading.Thread(target=delayed_callback, daemon=True).start()
            return True
        with listeners, patch.object(login.webbrowser, 'open', side_effect=launch):
            self.assertTrue(login.receive_code('http://localhost/auth', 'expected-state', timeout_seconds=3) == 'fake-code')

    def test_localhost_ipv4_and_ipv6_reach_same_callback(self):
        outcome = {}
        servers = login.loopback_servers(login.callback_handler('expected-state', outcome), 0)
        workers = [threading.Thread(target=server.serve_forever, kwargs={'poll_interval': 0.05}, daemon=True) for server in servers]
        for worker in workers: worker.start()
        try:
            self.assertEqual('127.0.0.1', servers[0].server_address[0])
            self.assertTrue(all(server.server_address[0] in ('127.0.0.1', '::1') for server in servers))
            for server in servers:
                host, port = server.server_address[:2]
                connection = http.client.HTTPConnection(host, port, timeout=2)
                connection.request('GET', '/callback?state=expected-state&code=fake-code', headers={'Host': 'localhost:8182'})
                response = connection.getresponse(); response.read(); connection.close()
                self.assertEqual(200, response.status)
            if len(servers) == 2:
                self.assertEqual('::1', servers[1].server_address[0])
                self.assertEqual(1, servers[1].socket.getsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY))
        finally:
            for server, worker in zip(servers, workers):
                server.shutdown(); server.server_close(); worker.join(timeout=2)

    def test_ipv6_port_conflict_is_fatal_and_closes_ipv4(self):
        with patch.object(login, 'QuietHTTPServer') as ipv4, patch.object(login, 'IPv6HTTPServer', side_effect=OSError(errno.EADDRINUSE, 'private-diagnostic')):
            ipv4.return_value.server_address = ('127.0.0.1', 8182)
            with self.assertRaises(OSError):
                login.loopback_servers(object())
            ipv4.return_value.server_close.assert_called_once()

    def test_ipv6_unavailable_falls_back_only_for_unsupported_loopback(self):
        with patch.object(login, 'QuietHTTPServer') as ipv4, patch.object(login, 'IPv6HTTPServer', side_effect=OSError(errno.EAFNOSUPPORT, 'private-diagnostic')), contextlib.redirect_stdout(io.StringIO()) as output:
            ipv4.return_value.server_address = ('127.0.0.1', 8182)
            servers = login.loopback_servers(object())
            self.assertEqual(1, len(servers))
            self.assertNotIn('private-diagnostic', output.getvalue())

    def test_network_errors_do_not_leak_diagnostics(self):
        for failure in [ValueError('sensitive-marker'), OSError('sensitive-marker'), KeyboardInterrupt()]:
            output = io.StringIO()
            with patch.object(login, 'run', side_effect=failure), contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
                self.assertEqual(1, login.cli([]))
            self.assertNotIn('sensitive-marker', output.getvalue())
            self.assertNotIn('Traceback', output.getvalue())

    def test_specific_diagnostics_do_not_echo_private_exception_values(self):
        for stage in ['listener-bind', 'browser-open', 'callback-timeout', 'callback-denied',
                      'token-exchange', 'jwks-fetch', 'access-validation', 'scope-validation',
                      'role-validation', 'id-validation', 'nonce-validation', 'export-preflight', 'export-exists', 'export-permissions', 'export-write']:
            with self.subTest(stage=stage), patch.object(login, 'run', side_effect=login.HelperFailure(stage, 400)), contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(1, login.cli([]))
                self.assertIn('FAIL [' + stage + ']', output.getvalue())
                self.assertIn('HTTP status 400', output.getvalue())

    def test_http_failure_reports_only_stage_and_status(self):
        failure = login.urllib.error.HTTPError(login.TOKEN_URL + '?private-query-marker', 400,
                'private-body-marker', {}, io.BytesIO(b'private-response-marker'))
        opener = MagicMock(); opener.open.side_effect = failure
        with patch.object(login.urllib.request, 'build_opener', return_value=opener):
            with self.assertRaises(login.HelperFailure) as result:
                login.local_json(login.TOKEN_URL, {'code': 'private-code-marker'})
        self.assertEqual('token-exchange', result.exception.stage)
        self.assertEqual(400, result.exception.status)
        self.assertNotIn('private-', str(result.exception))

    def test_export_permissions_fail_before_browser_launch(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(login, 'ROOT', Path(directory)), patch.object(login, 'receive_code') as receive:
            private = Path(directory) / '.local-private'; private.mkdir(); private.chmod(0o755)
            with contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(1, login.cli(['--export-for-postman']))
            self.assertIn('[export-permissions]', output.getvalue())
            receive.assert_not_called()

    def test_no_export_without_explicit_flag(self):
        output = io.StringIO()
        with patch.object(login, 'receive_code', return_value='fake-code'), patch.object(login, 'local_json', return_value={}), patch.object(login, 'accepted_access_token', return_value='synthetic-token-marker'), patch.object(login, 'export_token') as export, contextlib.redirect_stdout(output):
            login.run()
            export.assert_not_called()
        self.assertNotIn('synthetic-token-marker', output.getvalue())

    def test_invalid_token_never_exported(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(login, 'ROOT', Path(directory)), patch.object(login, 'receive_code', return_value='fake-code'), patch.object(login, 'local_json', return_value={}), patch.object(login, 'accepted_access_token', side_effect=ValueError('invalid')), patch.object(login, 'export_token') as export, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(1, login.cli(['--export-for-postman']))
            export.assert_not_called()

    def test_private_exclusive_export_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(login, 'ROOT', Path(directory)):
            login.export_token('synthetic-token-marker')
            folder = Path(directory) / '.local-private'
            token = folder / login.TOKEN_FILE
            self.assertEqual(0o700, stat.S_IMODE(folder.stat().st_mode))
            self.assertEqual(0o600, stat.S_IMODE(token.stat().st_mode))
            self.assertTrue(token.read_text() == 'synthetic-token-marker\n')
            with self.assertRaises(FileExistsError):
                login.export_token('replacement-marker')
            self.assertTrue(token.read_text() == 'synthetic-token-marker\n')
            with patch.object(login, 'receive_code') as receive, contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, login.cli(['--export-for-postman']))
                receive.assert_not_called()

    def test_export_refuses_symlinks_and_unsafe_directory(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(login, 'ROOT', Path(directory)):
            root = Path(directory)
            (root / 'outside').mkdir()
            (root / '.local-private').symlink_to(root / 'outside', target_is_directory=True)
            with self.assertRaises(ValueError):
                login.export_token('synthetic-token-marker')
            self.assertFalse(list((root / 'outside').iterdir()))
        with tempfile.TemporaryDirectory() as directory, patch.object(login, 'ROOT', Path(directory)):
            folder = Path(directory) / '.local-private'
            folder.mkdir(mode=0o755)
            folder.chmod(0o755)
            with self.assertRaises(ValueError):
                login.export_token('synthetic-token-marker')

    def test_transport_pins_local_endpoints_and_rejects_redirects(self):
        with self.assertRaises(ValueError):
            login.local_json('https://other.invalid/token')
        self.assertIsNone(login.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://other.invalid'))
        response = MagicMock()
        response.__enter__.return_value = io.StringIO('{"ok":true}')
        opener = MagicMock()
        opener.open.return_value = response
        with patch.object(login.urllib.request, 'build_opener', return_value=opener) as factory:
            self.assertTrue(login.local_json(login.TOKEN_URL, {'grant_type': 'authorization_code'})['ok'])
        self.assertEqual({}, factory.call_args.args[0].proxies)
        self.assertIsInstance(factory.call_args.args[1], login.NoRedirect)
        self.assertEqual(15, opener.open.call_args.kwargs['timeout'])


if __name__ == '__main__':
    unittest.main()
