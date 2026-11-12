# PayFlow local identity — Checkpoint 3, Slice 1

This is an isolated development identity provider, not production security and not
application API protection. Existing services still have their previous security
behavior. No frontend or service code is changed.

## Isolation and versions

Keycloak is pinned to `quay.io/keycloak/keycloak:26.8.2`, the November 5, 2026
security patch release: https://www.keycloak.org/2026/11/keycloak-2682-released.
PostgreSQL is pinned to `postgres:17.6`. Version tags are reproducible choices,
not immutable digest pins or a promise of ongoing vulnerability coverage. Review
both images before production use and upgrades.

Compose project `payflow-identity` owns its network, database and
`payflow-identity_keycloak-postgres-data` volume. PostgreSQL has no published port.
Keycloak binds only `127.0.0.1:8180`. No existing PayFlow Compose file, network,
database or volume is referenced. Never combine the two Compose files with `-f`.

HTTP, `start-dev`, realm `sslRequired=none`, local bootstrap administration and
loopback callbacks are intentional DEVELOPMENT ONLY choices. No production
hostname, credentials, merchant binding or production authorization is claimed.

## Start independently

From the repository root, inspect both Docker publications and host listeners:

```sh
docker ps --format '{{.Names}}\t{{.Ports}}'
lsof -nP -iTCP:8180 -sTCP:LISTEN
lsof -nP -iTCP:8181 -sTCP:LISTEN
lsof -nP -iTCP:8182 -sTCP:LISTEN
```

If a port is occupied, do not stop its owner. Choose a coordinated alternate
configuration later; issuer, callbacks, tests and helpers deliberately use fixed
local addresses.

```sh
python3 infra/keycloak/create-local-env.py
python3 -m unittest discover -s infra/keycloak/tests -v
docker compose --env-file .env.keycloak.local -f docker-compose.keycloak.yml config --quiet
docker compose --env-file .env.keycloak.local -f docker-compose.keycloak.yml up -d
docker compose --env-file .env.keycloak.local -f docker-compose.keycloak.yml ps
```

The generator creates `.env.keycloak.local` exclusively with random credentials
and mode 0600; it refuses to overwrite an existing file. Keep this ignored file
private. `.env.keycloak.example` has no usable passwords. Compose rejects missing
or empty credentials. Do not display rendered `docker compose config`, inspect
environment dumps, or share logs containing credentials. Docker-daemon operators
can read container environments: this is not a production secret store.

Open http://localhost:8180/admin/ and use the local bootstrap username/password
from the private file through a private editor/password manager. Never paste the
file into chat, screenshots or source control. The bootstrap account is a local
Keycloak administrator, not a PayFlow platform administrator.

Create separate non-production test users in the **payflow** realm through the
administration console. Set their passwords privately and assign the API client
roles described below. No human accounts or passwords are imported from Git.

Realm import occurs only when `payflow` does not already exist. Restarting with
changed JSON does NOT update an existing realm. Apply reviewed changes through
administration or use a separately named fresh project/volume for future tests;
never delete the existing database to force an import. Changing the environment
machine secret after import also does not rotate the stored client secret.

## Realm and token contract

Issuer: `http://localhost:8180/realms/payflow`. Tokens use RS256, five-minute
access lifetimes, refresh-token rotation, disabled public registration/password
reset, and brute-force protection. No business roles are granted by default.

| Client | Grants and permissions |
| --- | --- |
| payflow-portal | Public code flow, mandatory S256 PKCE; exact `http://localhost:8181/callback`; own-origin CORS; optional merchant self-management/payment scopes |
| payflow-platform-console | Public code flow, mandatory S256 PKCE; exact `http://localhost:8182/callback`; own-origin CORS; merchant administration scopes, no payment scopes |
| payflow-merchant-api | Bearer-only resource, no login or service account |
| payflow-payment-api | Bearer-only resource, no login or service account |
| payflow-payment-service | Confidential client credentials only; `merchant.access.resolve`; only Merchant API audience and `access-resolver` role |

All five disable implicit, password, device, CIBA and token-exchange grants and
full-scope access. Browser clients have no secret. Offline access is not assigned.

Merchant API client roles:
`merchant-admin`, `merchant-operator`, `platform-admin`, `access-resolver`.
Payment API client roles: `merchant-admin`, `merchant-operator`.
Roles are noncomposite and must be assigned deliberately to users. A human user
must never receive `access-resolver` or Keycloak realm-management roles as part of
PayFlow onboarding.

| Scope | Resource audience | Scope-role allowance |
| --- | --- | --- |
| merchant.read | payflow-merchant-api | merchant-admin, merchant-operator, platform-admin |
| merchant.profile.write | payflow-merchant-api | merchant-admin, platform-admin |
| merchant.lifecycle.manage | payflow-merchant-api | platform-admin |
| merchant.bindings.manage | payflow-merchant-api | platform-admin |
| webhook.read / webhook.manage / webhook.key.provision | payflow-merchant-api | merchant-admin, platform-admin |
| merchant.access.resolve | payflow-merchant-api | access-resolver |
| payment.read / payment.create | payflow-payment-api | merchant-admin, merchant-operator |

Scopes explicitly add their API audience to access tokens, never ID tokens.
Role mappers emit API-specific `resource_access.<api>.roles`, constrained by
scope mappings and assigned user roles. Scope strings or audiences alone are
NOT permissions: future APIs must require appropriate roles AND ownership.
Requesting a scope does not grant its roles. No merchant-ID claim is generated;
future Merchant database bindings will own tenant identity.

Example access-token shape (illustrative values, not a usable token):

```json
{
  "iss": "http://localhost:8180/realms/payflow",
  "sub": "opaque-keycloak-subject",
  "aud": "payflow-merchant-api",
  "azp": "payflow-payment-service",
  "typ": "Bearer",
  "scope": "merchant.access.resolve",
  "resource_access": {"payflow-merchant-api": {"roles": ["access-resolver"]}}
}
```

## Discovery and safe token checks

```sh
curl --fail --silent --show-error \
  http://localhost:8180/realms/payflow/.well-known/openid-configuration \
  | python3 -m json.tool
python3 infra/keycloak/verify_tokens.py
```

The machine helper reads the private file without placing the secret in process
arguments, shell history or output. It obtains an actual client-credentials token,
checks its RS256 signature against the issuer JWKS with OpenSSL, checks issuer,
audience, expiry/not-before, subject, authorized client, access-token type and
exact machine roles/scopes, then discards it. It also checks wrong-audience
rejection, disabled password grant and forbidden machine scope. Python 3 and
OpenSSL are required. This small local verifier is a smoke-test utility, not a
replacement for Spring Security or a general JWT library.

For browser login, first create a local user with Merchant API `merchant-admin`
or `merchant-operator`. For platform login, assign Merchant API `platform-admin`.
Check callback ports before starting either helper:

```sh
python3 infra/keycloak/browser_login.py --client payflow-portal
python3 infra/keycloak/browser_login.py --client payflow-platform-console
```

The one-shot loopback receiver opens Keycloak login, uses random state, nonce and
S256 verifier, exchanges the code in memory, verifies signed access and ID tokens,
audiences and nonce, then discards tokens. It never logs callback codes. Complete
login within 180 seconds. No frontend application is created. The console client
is not itself an authorization boundary: future admin APIs must require the
platform role. A roleless user may authenticate but must not be authorized.

Do not use password grants, put secrets in curl arguments, paste JWTs into online
decoders, or call current PayFlow APIs expecting Slice 1 to protect them. Local
browser sessions can remain after the helper exits; log out through Keycloak when
finished. JWT access tokens remain valid until expiration unless later resource
server policy adds another revocation mechanism.

## Stop and recover without deleting data

```sh
docker compose --env-file .env.keycloak.local -f docker-compose.keycloak.yml stop
# Resume the same database:
docker compose --env-file .env.keycloak.local -f docker-compose.keycloak.yml start
```

Never use `down -v`, `volume rm`, prune commands or database resets. Preserve the
private environment file alongside the database. Bootstrap credentials only
initialize an empty installation; editing the file is not password rotation.

## Production boundary and later slices

Production requires HTTPS, a canonical public issuer, hardened `start` deployment,
protected administration, MFA policy, secret-manager integration, database backup,
network policy, monitored/audited access and reviewed image digests. Host localhost
is not container localhost; future services need coordinated discovery/JWKS
routing without disabling issuer validation. No service credentials are wired
into PayFlow yet. JWT resource servers, tenant bindings, Payment access resolution,
API authorization and production MFA enforcement belong to later approved slices.

## Slice 1 validation record — 2026-11-10

- 15 automated configuration and real-RSA token-validation tests passed.
- Standalone Compose configuration validated successfully.
- Host ports 8180, 8181 and 8182 were checked; no conflicts were found.
- Keycloak 26.8.2 started with its own PostgreSQL database and imported `payflow`.
- Actual discovery, client-credentials issuance and signature/claim checks passed.
- The machine token had exactly the Merchant API audience, `access-resolver` role
  and `merchant.access.resolve` scope. Wrong audience, password grant and an
  unauthorized requested scope were rejected by the verifier/server as applicable.
- Only the new identity containers were stopped after testing. Their volume and
  private credentials were retained. The existing Kafka UI remained running.
- Interactive human browser login was not exercised; the PKCE/state/nonce helper
  and exact client settings are provided for that manual check. No real merchant
  users or production credentials were provisioned.

For the current membership-administration scope and explicitly controlled private
Postman token export, see [the dedicated platform-admin helper](PLATFORM-ADMIN-POSTMAN.md).
