"""One-shot LOCAL PKCE receiver, not a frontend or authentication server.
Create a non-production user and assign API client roles first. Tokens stay in memory.
"""
import argparse
import base64
import hashlib
from http.server import BaseHTTPRequestHandler, HTTPServer
import secrets
import urllib.parse
import webbrowser

from verify_tokens import ISSUER, request_json, validate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client', choices=['payflow-portal', 'payflow-platform-console'], default='payflow-portal')
    args = parser.parse_args()
    port = 8181 if args.client == 'payflow-portal' else 8182
    callback = f'http://localhost:{port}/callback'
    state, nonce, verifier = (secrets.token_urlsafe(48) for _ in range(3))
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('=')
    scope = 'merchant.read' if port == 8181 else 'merchant.lifecycle.manage'
    result = {}

    class Callback(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass  # Never log authorization codes or callback query strings.

        def do_GET(self):
            url = urllib.parse.urlsplit(self.path)
            query = urllib.parse.parse_qs(url.query)
            valid = url.path == '/callback' and query.get('state') == [state] and len(query.get('code', [])) == 1
            if valid:
                result['code'] = query['code'][0]
            self.send_response(200 if valid else 400)
            self.send_header('Content-Type', 'text/plain')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Referrer-Policy', 'no-referrer')
            self.end_headers()
            self.wfile.write(b'Local callback received. Return to the terminal; no tokens are displayed.')

    with HTTPServer(('127.0.0.1', port), Callback) as server:
        server.timeout = 180
        url = ISSUER + '/protocol/openid-connect/auth?' + urllib.parse.urlencode(dict(client_id=args.client, redirect_uri=callback, response_type='code', scope='openid ' + scope, state=state, nonce=nonce, code_challenge=challenge, code_challenge_method='S256'))
        print('Opening local Keycloak login. Complete it within 180 seconds. No tokens will be printed.')
        webbrowser.open(url)
        server.handle_request()
    if 'code' not in result:
        raise ValueError('Missing or invalid callback')
    tokens = request_json(ISSUER + '/protocol/openid-connect/token', dict(grant_type='authorization_code', client_id=args.client, redirect_uri=callback, code=result['code'], code_verifier=verifier))
    claims = validate(tokens['access_token'], 'payflow-merchant-api', args.client)
    identity = validate(tokens['id_token'], args.client, args.client, token_type='ID')
    if identity.get('nonce') != nonce or claims['sub'] != identity['sub']:
        raise ValueError('OIDC nonce/subject mismatch')
    if scope not in claims.get('scope', '').split():
        raise ValueError('Missing requested scope')
    print('PASS: browser PKCE login, state, signed access/ID tokens, issuer, audience, nonce and subject. Tokens discarded; API authorization is not implemented in Slice 1.')


if __name__ == '__main__':
    try:
        main()
    except Exception:
        raise SystemExit('FAIL: local browser check failed; ensure the callback port is free and user roles are assigned. No token diagnostics displayed.') from None
