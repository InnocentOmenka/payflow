"""Create local credentials exclusively; never print or overwrite secrets."""
import os
from pathlib import Path
import secrets

if __name__ == '__main__':
    path = Path(__file__).resolve().parents[2] / '.env.keycloak.local'
    values = {'KEYCLOAK_ADMIN_USERNAME': 'local-bootstrap-admin'}
    for key in ('KEYCLOAK_ADMIN_PASSWORD', 'KEYCLOAK_DB_PASSWORD', 'PAYFLOW_PAYMENT_SERVICE_SECRET'):
        values[key] = secrets.token_urlsafe(48)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as out:
        out.write(''.join(f'{key}={value}\n' for key, value in values.items()))
    print('Created private .env.keycloak.local (0600); values not displayed.')
