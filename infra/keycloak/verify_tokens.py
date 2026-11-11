"""Local-only token smoke check. Tokens and credentials are never printed.
Requires Python 3 and OpenSSL. Does not configure PayFlow services.
"""
import base64
import hashlib
import json
import secrets
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ISSUER = 'http://localhost:8180/realms/payflow'


def request_json(url, data=None):
    body = None if data is None else urllib.parse.urlencode(data).encode()
    with urllib.request.urlopen(url, body, timeout=15) as response:
        return json.load(response)


def decode(value):
    return base64.urlsafe_b64decode(value + '=' * (-len(value) % 4))


def der(tag, value):
    size = len(value)
    length = bytes([size]) if size < 128 else bytes([0x80 + (size.bit_length() + 7) // 8]) + size.to_bytes((size.bit_length() + 7) // 8, 'big')
    return bytes([tag]) + length + value


def rsa_integer(value):
    raw = decode(value).lstrip(b'\x00') or b'\x00'
    return der(2, (b'\x00' if raw[0] & 0x80 else b'') + raw)


def validate(token, audience, client, token_type='Bearer'):
    header, payload, signature = token.split('.')
    head = json.loads(decode(header))
    claims = json.loads(decode(payload))
    if head.get('alg') != 'RS256':
        raise ValueError('Unexpected signing algorithm')
    keys = request_json(ISSUER + '/protocol/openid-connect/certs')['keys']
    key = next(k for k in keys if k['kid'] == head['kid'] and k['kty'] == 'RSA' and k.get('use') == 'sig')
    public = der(0x30, rsa_integer(key['n']) + rsa_integer(key['e']))
    pem = b'-----BEGIN RSA PUBLIC KEY-----\n' + base64.encodebytes(public) + b'-----END RSA PUBLIC KEY-----\n'
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        (root / 'public.pem').write_bytes(pem)
        (root / 'signature').write_bytes(decode(signature))
        verified = subprocess.run(['openssl', 'dgst', '-sha256', '-verify', str(root / 'public.pem'), '-signature', str(root / 'signature')], input=(header + '.' + payload).encode(), capture_output=True)
        if verified.returncode:
            raise ValueError('Invalid signature')
    now = time.time()
    audiences = claims.get('aud', [])
    if isinstance(audiences, str):
        audiences = [audiences]
    if (claims.get('iss') != ISSUER or audience not in audiences or not claims.get('sub')
            or claims.get('azp') != client or claims.get('typ') != token_type
            or claims.get('exp', 0) <= now or claims.get('nbf', 0) > now
            or claims.get('iat', now + 1) > now + 5):
        raise ValueError('Invalid access-token claims')
    return claims


def main():
    discovery = request_json(ISSUER + '/.well-known/openid-configuration')
    if discovery['issuer'] != ISSUER or 'S256' not in discovery['code_challenge_methods_supported']:
        raise ValueError('Discovery contract mismatch')
    env = dict(line.split('=', 1) for line in (Path(__file__).resolve().parents[2] / '.env.keycloak.local').read_text().splitlines() if line and not line.startswith('#'))
    endpoint = discovery['token_endpoint']
    credentials = {'client_id': 'payflow-payment-service', 'client_secret': env['PAYFLOW_PAYMENT_SERVICE_SECRET']}
    result = request_json(endpoint, dict(credentials, grant_type='client_credentials'))
    claims = validate(result['access_token'], 'payflow-merchant-api', credentials['client_id'])
    if set(claims.get('scope', '').split()) != {'merchant.access.resolve'}:
        raise ValueError('Unexpected machine scope')
    if claims.get('resource_access', {}) != {'payflow-merchant-api': {'roles': ['access-resolver']}}:
        raise ValueError('Unexpected machine permissions')
    audiences = claims['aud'] if isinstance(claims['aud'], list) else [claims['aud']]
    if set(audiences) != {'payflow-merchant-api'} or 'refresh_token' in result:
        raise ValueError('Machine token exceeded contract')
    try:
        validate(result['access_token'], 'payflow-payment-api', credentials['client_id'])
        raise RuntimeError('Wrong audience accepted')
    except ValueError:
        pass
    for extra in ({'grant_type': 'password', 'username': 'nonexistent', 'password': 'invalid'}, {'grant_type': 'client_credentials', 'scope': 'payment.create'}):
        try:
            request_json(endpoint, dict(credentials, **extra))
            raise RuntimeError('Forbidden grant/scope accepted')
        except urllib.error.HTTPError as failure:
            if failure.code != 400:
                raise
    print('PASS: discovery, RS256 signature, issuer, audience, expiry, subject, client, least-privilege roles/scope, wrong-audience rejection, disabled password grant and forbidden scope. No tokens displayed.')


if __name__ == '__main__':
    try:
        main()
    except Exception:
        # Avoid HTTP errors or diagnostic objects accidentally exposing credentials.
        raise SystemExit('FAIL: local token contract check failed; inspect private server diagnostics without sharing secrets.') from None
