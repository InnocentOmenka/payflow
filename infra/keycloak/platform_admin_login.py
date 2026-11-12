"""LOCAL DEVELOPMENT ONLY: console PKCE login; optional private access-token export.
No client secret, password, Docker operation or application configuration change.
"""
import argparse
import errno
import base64
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
import os
from pathlib import Path
import re
import secrets
import stat
import socket
import threading
import time
import urllib.parse
import urllib.error
import urllib.request
import webbrowser

from verify_tokens import ISSUER, validate, TokenValidationFailure, VALIDATION_MESSAGES

CLIENT = 'payflow-platform-console'
AUDIENCE = 'payflow-merchant-api'
SCOPE = 'merchant.bindings.manage'
REDIRECT = 'http://localhost:8182/callback'
TOKEN_URL = ISSUER + '/protocol/openid-connect/token'
KEYS_URL = ISSUER + '/protocol/openid-connect/certs'
ROOT = Path(__file__).resolve().parents[2]
TOKEN_FILE = 'platform-admin-access-token.txt'


def challenge(verifier):
    return base64.urlsafe_b64encode(hashlib.sha256(verifier.encode('ascii')).digest()).decode().rstrip('=')


def fresh_login():
    verifier, state, nonce = (secrets.token_urlsafe(48) for _ in range(3))
    url = ISSUER + '/protocol/openid-connect/auth?' + urllib.parse.urlencode(dict(
        client_id=CLIENT, redirect_uri=REDIRECT, response_type='code',
        scope='openid ' + SCOPE, code_challenge=challenge(verifier),
        code_challenge_method='S256', state=state, nonce=nonce))
    return verifier, state, nonce, url


def same_secret(actual, expected):
    return isinstance(actual, str) and actual.isascii() and secrets.compare_digest(actual, expected)


# Only these static messages may reach terminal diagnostics. Never stringify an exception.
DIAGNOSTICS = {
    'listener-bind': 'Cannot bind loopback callback port 8182. Check for another owner; do not stop unrelated applications.',
    'listener-runtime': 'The local callback listener failed while waiting for login.',
    'browser-open': 'The browser launcher failed. Check the local default browser configuration.',
    'callback-timeout': 'No valid callback arrived within the login deadline. Confirm the redirect is http://localhost:8182/callback and retry a fresh login.',
    'callback-denied': 'Keycloak returned an authorization error. Check the console client, requested scope and human login in Keycloak.',
    'token-exchange': 'Authorization-code exchange failed. The code may be expired, consumed, or rejected by the client/redirect/PKCE configuration.',
    'jwks-fetch': 'Cannot fetch valid signing keys from the configured local Keycloak JWKS endpoint.',
    'access-validation': 'Access-token signature, issuer, audience, client, type or time validation failed.',
    'token-response': 'The token endpoint response lacks the expected bearer access token or signed ID token.',
    'access-claims': 'Access-token identity or timestamp claims have invalid types or values.',
    'scope-validation': 'Access token lacks openid and merchant.bindings.manage. Verify the console scope mapping.',
    'role-validation': 'Access token lacks payflow-merchant-api/platform-admin. Verify the development human user role.',
    'id-validation': 'ID-token signature, issuer, client, audience or time validation failed.',
    'nonce-validation': 'ID-token nonce or subject does not match this login. No token was accepted.',
    'export-exists': 'The private token export already exists (or is a symlink). No overwrite was attempted; finish using and remove only your generated export before retrying.',
    'export-permissions': 'The private export directory must be a real directory owned by this user with mode 0700. No permissions were changed.',
    'export-preflight': 'Private export destination is not usable. Check .local-private ownership/mode 0700, symlinks and any existing token file.',
    'export-write': 'Cannot exclusively write the private token file. Check permissions, existing output and available disk space.',
    'cancelled': 'Login was cancelled. Any local listeners have been closed.',
    'internal': 'Unexpected helper failure. No exception text or private values are displayed.',
}

# Whitelisted condition labels only; values are never included in output.
for _condition, _message in VALIDATION_MESSAGES.items():
    DIAGNOSTICS['access-' + _condition] = 'Access token rejected: ' + _message + '.'
    DIAGNOSTICS['id-' + _condition] = 'ID token rejected: ' + _message + '.'


class HelperFailure(ValueError):
    def __init__(self, stage, status=None):
        self.stage = stage if stage in DIAGNOSTICS else 'internal'
        self.status = status if type(status) is int and 100 <= status <= 599 else None
        super().__init__(DIAGNOSTICS[self.stage])


class LoginDenied(ValueError):
    pass


class CallbackRejected(ValueError):
    def __init__(self, reason):
        self.reason = reason
        super().__init__('Callback rejected')


def callback_code(target, expected_state):
    try:
        parsed = urllib.parse.urlsplit(target)
        if parsed.scheme or parsed.netloc or parsed.fragment or parsed.path != '/callback':
            raise CallbackRejected('path')
        query = urllib.parse.parse_qs(parsed.query, keep_blank_values=True, strict_parsing=True, max_num_fields=12)
    except CallbackRejected:
        raise
    except ValueError:
        raise CallbackRejected('malformed') from None
    if any(len(values) != 1 for values in query.values()):
        raise CallbackRejected('duplicate parameters')
    if not same_secret(query.get('state', [None])[0], expected_state):
        raise CallbackRejected('state')
    if 'iss' in query and query['iss'][0] != ISSUER:
        raise CallbackRejected('issuer')
    if any(key in query for key in ('error', 'error_description', 'error_uri')):
        raise LoginDenied('Login was not completed')
    code = query.get('code', [''])[0]
    if not code.strip() or len(code) > 4096:
        raise CallbackRejected('code missing or malformed')
    return code


def callback_handler(state, outcome, completed=None, rejected=None):
    lock = threading.Lock()

    def reject(reason):
        # At most one static diagnostic per category. Never echo a URL or query value.
        if rejected is not None:
            with lock:
                if reason not in rejected:
                    rejected.add(reason)
                    print('Callback rejected [' + reason + ']; still waiting for a valid callback.')

    class Callback(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(3)

        def log_message(self, *args):
            pass

        def do_GET(self):
            status = 400
            if self.headers.get('Host') != 'localhost:8182':
                reject('host')
            else:
                try:
                    code = callback_code(self.path, state)
                    with lock:
                        if not outcome:
                            outcome['code'] = code
                            if completed is not None:
                                completed.set()
                    status = 200
                except LoginDenied:
                    with lock:
                        if not outcome:
                            outcome['denied'] = True
                            if completed is not None:
                                completed.set()
                except CallbackRejected as failure:
                    reject(failure.reason)
                except (ValueError, TypeError):
                    reject('malformed')
            self.send_response(status)
            self.send_header('Content-Type', 'text/plain; charset=utf-8')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Referrer-Policy', 'no-referrer')
            self.send_header('Content-Security-Policy', "default-src 'none'; frame-ancestors 'none'")
            self.end_headers()
            self.wfile.write(b'Local login callback handled. Return to the terminal. No token is displayed here.')
    return Callback


class QuietHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    block_on_close = False

    def handle_error(self, request, client_address):
        pass


class IPv6HTTPServer(QuietHTTPServer):
    address_family = socket.AF_INET6

    def server_bind(self):
        self.socket.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1)
        super().server_bind()


def loopback_servers(handler, port=8182):
    servers = []
    try:
        servers.append(QuietHTTPServer(('127.0.0.1', port), handler))
        port = servers[0].server_address[1]
        try:
            servers.append(IPv6HTTPServer(('::1', port), handler))
        except OSError as failure:
            if failure.errno not in (errno.EAFNOSUPPORT, errno.EADDRNOTAVAIL, errno.ENOPROTOOPT):
                raise  # An IPv6 port conflict is fatal, not a fallback to a different owner.
            print('IPv6 loopback is unavailable; callback listener is IPv4-only on 127.0.0.1.')
        return servers
    except BaseException:
        for server in servers:
            server.server_close()
        raise


def receive_code(url, state, timeout_seconds=180):
    outcome, rejected = {}, set()
    completed, browser_done = threading.Event(), threading.Event()
    browser_opened = [False]
    try:
        servers = loopback_servers(callback_handler(state, outcome, completed, rejected))
    except OSError:
        raise HelperFailure('listener-bind') from None
    workers = []
    try:
        # Serving threads run BEFORE browser launch. A launcher may block while a page loads.
        for server in servers:
            worker = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': 0.05}, daemon=True)
            worker.start()
            workers.append((server, worker))
        print('Callback listeners ready on loopback port 8182; registered redirect remains http://localhost:8182/callback.')
        deadline = time.monotonic() + timeout_seconds

        def open_browser():
            try:
                browser_opened[0] = bool(webbrowser.open(url))
            except Exception:
                pass
            finally:
                browser_done.set()

        threading.Thread(target=open_browser, daemon=True).start()
        print('Waiting for the browser callback (180-second default deadline). Listener remains active during login.')
        while not completed.is_set():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise HelperFailure('callback-timeout')
            if browser_done.is_set() and not browser_opened[0]:
                raise HelperFailure('browser-open')
            if any(not worker.is_alive() for _, worker in workers):
                raise HelperFailure('listener-runtime')
            completed.wait(min(0.05, remaining))
    finally:
        for server, worker in workers:
            if worker.is_alive():
                server.shutdown()
            worker.join(timeout=2)
        for server in servers:
            server.server_close()
    if 'code' not in outcome:
        raise HelperFailure('callback-denied')
    print('Valid callback received; callback listeners closed. Exchanging the code without displaying it.')
    return outcome['code']


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        return None


def local_json(url, data=None):
    if url not in (TOKEN_URL, KEYS_URL):
        raise ValueError('Unexpected local endpoint')
    # Do not send authorization codes/verifiers through environment proxies or redirects.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    body = None if data is None else urllib.parse.urlencode(data).encode()
    stage = 'token-exchange' if url == TOKEN_URL else 'jwks-fetch'
    try:
        with opener.open(url, body, timeout=15) as response:
            result = json.load(response)
        if not isinstance(result, dict) or (url == KEYS_URL and not isinstance(result.get('keys'), list)):
            raise ValueError('Invalid response')
        return result
    except urllib.error.HTTPError as failure:
        raise HelperFailure(stage, failure.code) from None
    except (OSError, ValueError):
        raise HelperFailure(stage) from None


def accepted_access_token(tokens, nonce):
    access = tokens.get('access_token')
    if not isinstance(access, str) or not re.fullmatch(r'[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', access):
        raise HelperFailure('token-response')
    if not isinstance(tokens.get('token_type'), str) or tokens['token_type'].lower() != 'bearer':
        raise HelperFailure('token-response')
    # Shared verifier performs RS256/JWKS signature, issuer, audience, azp and time checks.
    try:
        claims = validate(access, AUDIENCE, CLIENT, fetch_json=local_json)
    except TokenValidationFailure as failure:
        raise HelperFailure('access-' + failure.condition) from None
    except HelperFailure:
        raise
    except Exception:
        raise HelperFailure('access-validation') from None
    subject = claims.get('sub')
    dates = [claims.get('iat'), claims.get('exp')]
    if 'nbf' in claims:
        dates.append(claims['nbf'])
    audiences = claims.get('aud')
    audience_shape = isinstance(audiences, str) or (isinstance(audiences, list) and audiences and all(isinstance(value, str) and value.strip() for value in audiences))
    if (not isinstance(subject, str) or not subject.strip() or len(subject) > 255
            or not audience_shape
            or any(isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) for value in dates)
            or dates[1] <= dates[0]):
        raise HelperFailure('access-claims')
    scope = claims.get('scope')
    resources = claims.get('resource_access')
    roles = resources.get(AUDIENCE, {}).get('roles') if isinstance(resources, dict) and isinstance(resources.get(AUDIENCE), dict) else None
    if not isinstance(scope, str) or not {'openid', SCOPE}.issubset(scope.split()):
        raise HelperFailure('scope-validation')
    if not isinstance(roles, list) or not all(isinstance(role, str) for role in roles) or 'platform-admin' not in roles:
        raise HelperFailure('role-validation')
    if 'id_token' not in tokens:
        raise HelperFailure('token-response')
    try:
        identity = validate(tokens['id_token'], CLIENT, CLIENT, token_type='ID', fetch_json=local_json)
    except TokenValidationFailure as failure:
        raise HelperFailure('id-' + failure.condition) from None
    except HelperFailure:
        raise
    except Exception:
        raise HelperFailure('id-validation') from None
    if not same_secret(identity.get('nonce'), nonce) or identity.get('sub') != subject:
        raise HelperFailure('nonce-validation')
    return access


def private_directory():
    directory = ROOT / '.local-private'
    directory.mkdir(mode=0o700, exist_ok=True)
    info = directory.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise HelperFailure('export-permissions')
    return directory


def export_preflight():
    try:
        directory = private_directory()
        if os.path.lexists(directory / TOKEN_FILE):
            raise HelperFailure('export-exists')
    except HelperFailure:
        raise
    except (OSError, ValueError):
        raise HelperFailure('export-preflight') from None


def export_token(access):
    directory = private_directory()
    # POSIX-only private local export, relative to an opened non-symlink directory.
    directory_fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        fd = os.open(TOKEN_FILE, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=directory_fd)
        try:
            with os.fdopen(fd, 'w') as output:
                output.write(access + '\n')
        except BaseException:
            os.unlink(TOKEN_FILE, dir_fd=directory_fd)  # Only this invocation's incomplete new file.
            raise
    finally:
        os.close(directory_fd)


def run(export=False):
    if export:
        export_preflight()
    verifier, state, nonce, url = fresh_login()
    print('Opening LOCAL Keycloak console login; complete within 180 seconds. Tokens will not be printed.')
    code = receive_code(url, state)
    tokens = local_json(TOKEN_URL, dict(grant_type='authorization_code', client_id=CLIENT,
                        redirect_uri=REDIRECT, code=code, code_verifier=verifier))
    print('Code exchange completed. Validating signed tokens, administrator scope, role and nonce.')
    access = accepted_access_token(tokens, nonce)
    if export:
        try:
            export_token(access)
        except (OSError, ValueError):
            raise HelperFailure('export-write') from None
        print('Validated. Saved .local-private/platform-admin-access-token.txt (0600). Copy privately into Postman, then remove that file. Never share or commit it.')
    else:
        print('Validated. Token discarded. Use --export-for-postman only when you explicitly want a private local access-token file.')


def cli(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--export-for-postman', action='store_true', help='Explicitly write only the validated access token to ignored .local-private/ (0600); never overwrite')
    args = parser.parse_args(argv)
    try:
        run(args.export_for_postman)
        return 0
    except HelperFailure as failure:
        suffix = (' HTTP status ' + str(failure.status) + '.') if failure.status is not None else ''
        print('FAIL [' + failure.stage + ']: ' + DIAGNOSTICS[failure.stage] + suffix)
        return 1
    except KeyboardInterrupt:
        print('FAIL [cancelled]: ' + DIAGNOSTICS['cancelled'])
        return 1
    except Exception:
        print('FAIL [internal]: ' + DIAGNOSTICS['internal'])
        return 1


if __name__ == '__main__':
    raise SystemExit(cli())
