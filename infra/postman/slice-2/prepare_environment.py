"""Create an ignored, private Postman session with two PKCE challenges.
Does not contact Keycloak, read credentials, obtain tokens or change the realm.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import urllib.parse


def main():
    source = Path(__file__).resolve().parent
    root = source.parents[2]
    target_dir = root / '.local-private'
    target_dir.mkdir(mode=0o700, exist_ok=True)
    if target_dir.is_symlink():
        raise ValueError('Private directory must not be a symlink')
    template = json.loads((source / 'local.postman_environment.json').read_text())
    values = {item['key']: item for item in template['values']}
    for name, scope in [('merchant', 'openid merchant.read'), ('payment', 'openid payment.read payment.create')]:
        verifier, state, nonce = (secrets.token_urlsafe(48) for _ in range(3))
        challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('=')
        params = dict(client_id='payflow-portal', redirect_uri='http://localhost:8181/callback',
                      response_type='code', scope=scope, code_challenge_method='S256',
                      code_challenge=challenge, state=state, nonce=nonce)
        for key, value in {'code_verifier': verifier, 'state': state, 'nonce': nonce,
                           'authorization_url': 'http://localhost:8180/realms/payflow/protocol/openid-connect/auth?' + urllib.parse.urlencode(params)}.items():
            values[name + '_' + key]['value'] = value
    template['name'] = 'PayFlow Slice 2 — PRIVATE local session (do not sync/export)'
    target = target_dir / 'postman-slice-2-session.json'
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as output:
        json.dump(template, output, indent=2)
        output.write('\n')
    print('Created .local-private/postman-slice-2-session.json (0600). No secrets/tokens printed. Existing files are never overwritten.')


if __name__ == '__main__':
    main()
