"""Verify real RSA signatures with ephemeral keys; no Docker dependency."""
import base64
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('verify_tokens', Path(__file__).resolve().parents[1] / 'verify_tokens.py')
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)


def encoded(value):
    raw = value if isinstance(value, bytes) else json.dumps(value).encode()
    return base64.urlsafe_b64encode(raw).decode().rstrip('=')


class SignedTokenTest(unittest.TestCase):
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

    def token(self, **changes):
        claims = dict(iss=validator.ISSUER, aud='payflow-merchant-api', sub='opaque-subject', azp='payflow-payment-service', typ='Bearer', exp=int(time.time()) + 300, iat=int(time.time()))
        claims.update(changes)
        content = encoded({'alg': 'RS256', 'kid': 'test'}) + '.' + encoded(claims)
        signature = subprocess.run(['openssl', 'dgst', '-sha256', '-sign', str(self.key)], input=content.encode(), capture_output=True, check=True).stdout
        return content + '.' + encoded(signature)

    def validate(self, token):
        with patch.object(validator, 'request_json', return_value=self.jwks):
            return validator.validate(token, 'payflow-merchant-api', 'payflow-payment-service')

    def test_valid_signature_and_claims(self):
        self.assertEqual('opaque-subject', self.validate(self.token())['sub'])

    def test_wrong_issuer_audience_client_and_token_type(self):
        for change in [dict(iss='https://attacker.invalid'), dict(aud='payflow-payment-api'), dict(azp='other-client'), dict(typ='ID'), dict(sub='')]:
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.validate(self.token(**change))

    def test_expired_and_future_token(self):
        for change in [dict(exp=int(time.time()) - 1), dict(nbf=int(time.time()) + 60), dict(iat=int(time.time()) + 60)]:
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.validate(self.token(**change))

    def test_static_per_condition_identifiers(self):
        cases = [('issuer', dict(iss='https://other.invalid')),
                 ('audience', dict(aud='payflow-payment-api')),
                 ('client', dict(azp='other-client')), ('token-type', dict(typ='ID')),
                 ('subject', dict(sub='')), ('time-expired', dict(exp=int(time.time()) - 10)),
                 ('time-not-before', dict(nbf=int(time.time()) + 100)),
                 ('time-issued-at', dict(iat=int(time.time()) + 100)), ('time-claims', dict(iat=None))]
        for expected, changes in cases:
            with self.subTest(condition=expected):
                with self.assertRaises(validator.TokenValidationFailure) as failure:
                    self.validate(self.token(**changes))
                self.assertEqual(expected, failure.exception.condition)
                self.assertEqual(validator.VALIDATION_MESSAGES[expected], str(failure.exception))

    def test_signature_is_checked_before_claim_diagnostics(self):
        token = self.token()
        header, payload, signature = token.split('.')
        claims = json.loads(validator.decode(payload)); claims['iss'] = 'https://other.invalid'
        with self.assertRaises(validator.TokenValidationFailure) as failure:
            self.validate(header + '.' + encoded(claims) + '.' + signature)
        self.assertEqual('signature', failure.exception.condition)

    def test_payload_tampering_rejected(self):
        token = self.token()
        header, payload, signature = token.split('.')
        payload = json.loads(validator.decode(payload))
        payload['sub'] = 'another-user'
        with self.assertRaisesRegex(ValueError, 'signature'):
            self.validate(header + '.' + encoded(payload) + '.' + signature)
