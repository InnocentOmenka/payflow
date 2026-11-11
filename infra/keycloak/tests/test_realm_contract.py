import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3]
REALM = json.loads((ROOT / 'infra/keycloak/payflow-realm.json').read_text())
CLIENTS = {c['clientId']: c for c in REALM['clients']}
SCOPES = {s['name']: s for s in REALM['clientScopes']}


class RealmContractTest(unittest.TestCase):
    def test_realm_defaults(self):
        self.assertEqual('payflow', REALM['realm'])
        self.assertFalse(REALM['registrationAllowed'])
        self.assertFalse(REALM['resetPasswordAllowed'])
        self.assertTrue(REALM['bruteForceProtected'])
        self.assertTrue(REALM['revokeRefreshToken'])
        self.assertEqual(300, REALM['accessTokenLifespan'])
        self.assertEqual('RS256', REALM['defaultSignatureAlgorithm'])
        self.assertEqual([], REALM['defaultDefaultClientScopes'])

    def test_exact_clients(self):
        self.assertEqual({'payflow-portal', 'payflow-platform-console', 'payflow-merchant-api', 'payflow-payment-api', 'payflow-payment-service'}, set(CLIENTS))

    def test_no_unsafe_grants_or_full_scope(self):
        for c in CLIENTS.values():
            with self.subTest(client=c['clientId']):
                self.assertFalse(c['implicitFlowEnabled'])
                self.assertFalse(c['directAccessGrantsEnabled'])
                self.assertFalse(c['fullScopeAllowed'])
                for setting in ('oauth2.device.authorization.grant.enabled', 'oidc.ciba.grant.enabled', 'oauth2.token.exchange.grant.enabled'):
                    self.assertEqual('false', c['attributes'][setting])

    def test_browser_pkce_and_exact_redirects(self):
        for name, port in [('payflow-portal', 8181), ('payflow-platform-console', 8182)]:
            c = CLIENTS[name]
            self.assertTrue(c['publicClient'])
            self.assertTrue(c['standardFlowEnabled'])
            self.assertFalse(c['serviceAccountsEnabled'])
            self.assertEqual('S256', c['attributes']['pkce.code.challenge.method'])
            self.assertEqual([f'http://localhost:{port}/callback'], c['redirectUris'])
            self.assertEqual([f'http://localhost:{port}'], c['webOrigins'])
            self.assertNotIn('secret', c)
            self.assertNotIn('merchant.access.resolve', c['optionalClientScopes'])

    def test_resource_clients_cannot_login(self):
        for name in ('payflow-merchant-api', 'payflow-payment-api'):
            c = CLIENTS[name]
            self.assertTrue(c['bearerOnly'])
            self.assertFalse(c['standardFlowEnabled'])
            self.assertFalse(c['serviceAccountsEnabled'])
            self.assertEqual([], c['redirectUris'])
            self.assertEqual([], c['defaultClientScopes'])

    def test_machine_privileges_and_no_embedded_credentials(self):
        c = CLIENTS['payflow-payment-service']
        self.assertTrue(c['serviceAccountsEnabled'])
        self.assertFalse(c['standardFlowEnabled'])
        self.assertEqual(['merchant.access.resolve'], c['defaultClientScopes'])
        self.assertEqual([], c['optionalClientScopes'])
        self.assertEqual('${PAYFLOW_PAYMENT_SERVICE_SECRET}', c['secret'])
        self.assertEqual(1, len(REALM['users']))
        user = REALM['users'][0]
        self.assertEqual(c['clientId'], user['serviceAccountClientId'])
        self.assertEqual({'payflow-merchant-api': ['access-resolver']}, user['clientRoles'])
        self.assertNotIn('credentials', user)
        self.assertNotIn('realmRoles', user)

    def test_roles_are_noncomposite_and_api_specific(self):
        self.assertEqual({'payflow-merchant-api', 'payflow-payment-api'}, set(REALM['roles']['client']))
        for roles in REALM['roles']['client'].values():
            self.assertTrue(all(not r['composite'] for r in roles))

    def test_every_scope_has_one_explicit_access_token_audience(self):
        self.assertEqual(10, len(SCOPES))
        for scope in SCOPES.values():
            audience, roles = scope['protocolMappers']
            self.assertEqual('oidc-audience-mapper', audience['protocolMapper'])
            self.assertIn(audience['config']['included.client.audience'], REALM['roles']['client'])
            self.assertEqual('true', audience['config']['access.token.claim'])
            self.assertEqual('false', audience['config']['id.token.claim'])
            self.assertEqual('false', roles['config']['id.token.claim'])
            self.assertEqual('false', roles['config']['userinfo.token.claim'])
            self.assertEqual(audience['config']['included.client.audience'], roles['config']['usermodel.clientRoleMapping.clientId'])

    def test_scope_mappings_cover_only_defined_roles(self):
        seen = set()
        for api, mappings in REALM['clientScopeMappings'].items():
            defined = {r['name'] for r in REALM['roles']['client'][api]}
            for mapping in mappings:
                self.assertLessEqual(set(mapping['roles']), defined)
                self.assertTrue(mapping['roles'])
                seen.add(mapping['clientScope'])
        self.assertEqual(set(SCOPES), seen)
        internal = next(m for m in REALM['clientScopeMappings']['payflow-merchant-api'] if m['clientScope'] == 'merchant.access.resolve')
        self.assertEqual(['access-resolver'], internal['roles'])

    def test_compose_is_isolated_and_requires_private_secrets(self):
        text = (ROOT / 'docker-compose.keycloak.yml').read_text()
        self.assertIn('name: payflow-identity', text)
        self.assertIn('quay.io/keycloak/keycloak:26.8.2', text)
        self.assertIn('"127.0.0.1:8180:8080"', text)
        self.assertNotIn('container_name:', text)
        self.assertNotIn('external:', text)
        self.assertNotIn('5432:5432', text)
        self.assertNotIn('payflow-postgres-data', text)
        for variable in ('KEYCLOAK_DB_PASSWORD', 'KEYCLOAK_ADMIN_PASSWORD', 'PAYFLOW_PAYMENT_SERVICE_SECRET'):
            self.assertIn('${' + variable + ':?', text)
        example = (ROOT / '.env.keycloak.example').read_text()
        for line in example.splitlines():
            if line and not line.startswith('#') and 'USERNAME' not in line:
                self.assertEqual('', line.split('=', 1)[1])

    def test_token_validator_rejects_untrusted_algorithm_before_network(self):
        spec = importlib.util.spec_from_file_location('verify_tokens', ROOT / 'infra/keycloak/verify_tokens.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        import base64
        header = base64.urlsafe_b64encode(b'{"alg":"none"}').decode().rstrip('=')
        with self.assertRaisesRegex(ValueError, 'algorithm'):
            module.validate(header + '.e30.', 'payflow-merchant-api', 'payflow-payment-service')


if __name__ == '__main__':
    unittest.main()
