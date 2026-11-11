# Slice 2 live Postman validation

Preparation only: these instructions and assets do not start applications, mutate
business data or change security policy. Do not run the entire collection: send
setup requests individually, then run API folders 01–05 with fresh tokens.

## Observed configuration and prerequisites

- Merchant: `http://localhost:8086`, database `payflow_merchant` on localhost:5432.
- Payment: `http://localhost:8081`, database `payflow_payment` on localhost:5432.
- Issuer: `http://localhost:8180/realms/payflow`.
- JWKS: `http://localhost:8180/realms/payflow/protocol/openid-connect/certs`.
- Audience: Merchant `payflow-merchant-api`; Payment `payflow-payment-api`.
- Only exact `GET /actuator/health` is anonymous. Every other route is denied.
- Both applications have only a base application.yml; no dedicated dev YAML is
  required. The launch profile below is an explicit local label, not a new
  configuration file. Do not enable the dev secret adapter/provisioning profile.

At preparation time, `lsof` found no listeners on 5432, 8081, 8086, 8180, 8181,
8182 or 9092. Docker showed existing stopped `payflow-postgres`, `payflow-kafka`
and both identity containers; only the existing Kafka UI was running. Recheck
before starting: this is a snapshot, not a guarantee about future port availability.

Use Java 21, Maven, Docker Desktop and Postman Desktop (or its local Desktop
Agent). Do not use the cloud agent for localhost. Do not send an `Origin` header:
no cross-origin API origin is allowed. Turn off automatic OAuth token refresh and
inherited auth, and clear/disable cookies for these requests. Do not sync tokens,
open token-bearing Postman console dumps, export a populated environment, or
upload JWTs to online decoders. Share only the committed sanitized template.

## Exact startup sequence (run yourself)

From the repository root:

```sh
cd /Users/mac/Projects/payflow
docker ps -a --format '{{.Names}}\t{{.Status}}\t{{.Ports}}'
lsof -nP -iTCP:5432 -iTCP:8081 -iTCP:8086 -iTCP:8180 -iTCP:8181 -sTCP:LISTEN
```

If an API port has an existing application, do not kill it. Confirm its revision
or coordinate a separate instance/port and update Postman base URLs explicitly.
Do not point this collection at production.

**Keycloak and its own database:** the observed identity containers already exist.
The existing private `.env.keycloak.local` must be present; do not regenerate it
or change its values.

```sh
docker compose --env-file .env.keycloak.local -f docker-compose.keycloak.yml config --quiet
docker compose --env-file .env.keycloak.local -f docker-compose.keycloak.yml start
docker compose --env-file .env.keycloak.local -f docker-compose.keycloak.yml ps
curl --fail --silent --show-error \
  http://localhost:8180/realms/payflow/.well-known/openid-configuration
```

Wait for discovery to return 200 and the exact issuer. A `start` does not recreate
containers or rotate credentials. On a genuinely fresh checkout only, initial
provisioning is documented in `infra/keycloak/README.md`; it is not needed for the
observed local installation. Never combine the identity Compose file with the
main Compose file. Startup import skips an existing realm: source JSON does not
prove a persisted realm has no administrative drift.

**Existing application PostgreSQL only:** if `payflow-postgres` is already running,
skip its start. Otherwise:

```sh
docker start payflow-postgres
docker exec payflow-postgres pg_isready -U payflow_user -d postgres
docker exec payflow-postgres psql -U payflow_user -d postgres -tAc \
  "SELECT datname FROM pg_database WHERE datname IN ('payflow_merchant','payflow_payment') ORDER BY datname;"
docker exec payflow-postgres psql -U payflow_user -d payflow_merchant -c \
  "SELECT id, filename, exectype FROM databasechangelog ORDER BY orderexecuted;"
docker exec payflow-postgres psql -U payflow_user -d payflow_payment -c \
  "SELECT id, filename, exectype FROM databasechangelog ORDER BY orderexecuted;"
```

These are read-only checks. Merchant must already contain migrations 001–003;
Payment 001–004, matching their master changelogs. The main Compose file initially
creates only `payflow_payment`; it does not guarantee `payflow_merchant` exists.
If either database/schema is missing or out of date, STOP and report the blocker.
Do not create, reset, or auto-migrate it as part of this validation.

Kafka is **not required for these HTTP-only checks**. The launch flags disable
listeners, Kafka admin topic creation and both outbox publishers (including the
Merchant snapshot job). This prevents validation startup from consuming backlog
or publishing/changing existing outbox records. Do not start Kafka or other
services solely for these tests.

Build the current security changes into executable JARs, without running tests:

```sh
mvn -pl services/merchant-service,services/payment-service -am package -DskipTests
```

In a dedicated terminal, start Merchant:

```sh
java -jar services/merchant-service/target/merchant-service-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=postman-validation \
  --server.address=127.0.0.1 --server.port=8086 \
  --spring.liquibase.enabled=false --spring.jpa.hibernate.ddl-auto=validate \
  --spring.kafka.listener.auto-startup=false --spring.kafka.admin.auto-create=false \
  --payflow.merchant.outbox.enabled=false \
  --payflow.secrets.adapter=unavailable --payflow.secrets.provisioning-enabled=false
```

In another dedicated terminal, start Payment:

```sh
java -jar services/payment-service/target/payment-service-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=postman-validation \
  --server.address=127.0.0.1 --server.port=8081 \
  --spring.liquibase.enabled=false --spring.jpa.hibernate.ddl-auto=validate \
  --spring.kafka.listener.auto-startup=false --spring.kafka.admin.auto-create=false \
  --payflow.outbox.publisher.enabled=false
```

These flags do not change HTTP authorization. Liquibase is deliberately disabled
and Hibernate only validates the existing schema. Do not remove those safeguards
to work around a startup failure. Defaults use the repository's existing local
datasource credentials. If your preserved database uses different credentials,
provide `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and
`SPRING_DATASOURCE_PASSWORD` through a private local mechanism; do not put passwords
in command arguments, this document or shared exports.

The configured issuer/JWKS defaults need no overrides for host-run services. If
your shell already exports PAYFLOW_JWT_ISSUER/PAYFLOW_JWT_JWK_SET_URI, confirm they
match the local issuer/JWKS above. Do not disable issuer or audience validation.

## Import sanitized Postman assets

1. Import `slice-2.postman_collection.json`.
2. Import `local.postman_environment.json` for the matrix/default URLs only, OR
   generate a private session below for browser PKCE.
3. Select the environment. Tokens/secrets must remain local and sensitive; never
   populate or overwrite the version-controlled template with live values.
4. API requests use No Auth or explicit Bearer variables. They must not inherit
   OAuth authentication. All API POST bodies below must be denied, not persisted.

The collection is v2.1, with status/JSON security response assertions. It does not
cryptographically validate token responses itself: the resource servers do that.
A matching valid token must yield 403 on business routes; a token for the wrong
API must yield 401. A 401 in a test expecting 403 is a FAILURE, not an equivalent
successful denial—it means authentication was not proven.

## Obtain tokens using the existing realm

The Keycloak administration console is `http://localhost:8180/admin/`. Use the
existing private development bootstrap credentials. Check the persisted payflow
client settings against the imported contract; do not change clients, callbacks,
secrets or role mappings to make a test pass.

### Option A: Merchant token from the existing machine client

No human test user is required for this option. Put the existing development
`PAYFLOW_PAYMENT_SERVICE_SECRET` from `.env.keycloak.local` into the private local
Postman `payment_service_client_secret` variable. Do not export/sync it.

Send the optional first request in folder 00:

```text
POST http://localhost:8180/realms/payflow/protocol/openid-connect/token
Authorization: No Auth
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
client_id=payflow-payment-service
client_secret=<private local variable>
scope=merchant.access.resolve
```

Expected: 200 Bearer access token; the script stores `merchant_token` locally.
The token must have Merchant API audience, `merchant.access.resolve` scope and
Merchant API `access-resolver` role only. This client has no Payment API scope,
audience, administrator role, browser login or password grant. **It cannot produce
the Payment token needed below.** A form `scope=payment.create` would be rejected.

### Option B: Browser PKCE for Payment (and optionally Merchant)

There are no human users in the realm import. Prerequisite: an existing local test
user with Payment API `merchant-operator` (or `merchant-admin`) and Merchant API
`merchant-operator` (or `merchant-admin`) for the respective positive token tests.
If absent, an administrator must provision a NON-production user privately in
Keycloak and assign those client roles. This changes only development identity
state, not PayFlow databases; it has not been performed during preparation.
Do not grant realm-management or access-resolver roles to that user.

The existing `payflow-portal` public client supports mandatory S256 PKCE with:

```text
Authorization endpoint: http://localhost:8180/realms/payflow/protocol/openid-connect/auth
Token endpoint:         http://localhost:8180/realms/payflow/protocol/openid-connect/token
Client ID:              payflow-portal
Client secret:          NONE
Redirect URI:           http://localhost:8181/callback
Merchant scope:         openid merchant.read
Payment scope:          openid payment.read payment.create
```

Request the two audiences in **separate** authorization flows. A dual-audience
token would invalidate the cross-audience negative tests.

Postman's default `https://oauth.pstmn.io/...` callbacks are NOT registered. Do not
assume “Get New Access Token” with its default callback will work, and do not widen
the realm. Use this manual browser/code exchange with the exact existing callback:

```sh
python3 infra/postman/slice-2/prepare_environment.py
```

It creates ignored `.local-private/postman-slice-2-session.json`, mode 0600, with
independent random PKCE verifiers, state and nonce for each audience. It reads no
credentials, makes no network requests and refuses to overwrite an existing file.
Import it into Postman as your PRIVATE local environment. It contains empty token
and secret values. If a previous session exists, keep it private and move/rename it
locally before generating another; do not delete or commit it.

For Payment:

1. Ensure port 8181 has no running callback listener (including browser_login.py).
2. Copy the environment's `payment_authorization_url` into your local browser.
   Log in through Keycloak using the development user's password; never send that
   password to Postman. Confirm the issuer host is localhost:8180.
3. Keycloak redirects to `http://localhost:8181/callback?state=...&code=...`.
   Because no receiver is running, connection refused is EXPECTED at this step;
   the address bar still contains the callback URL. Do not forward it elsewhere.
4. Copy the entire callback URL into the private `payment_callback_url` variable.
5. Promptly send folder 00's **Payment API token — exchange browser PKCE code**.
   Its pre-request script checks the exact callback prefix/state and submits the
   code with its matching verifier and redirect URI. Expected: 200, stored locally
   as `payment_token`. Codes are short-lived, single-use; do not retry a consumed
   code. Never substitute an ID token for the access token.
6. Run Payment tests immediately (access tokens last five minutes).

For a human Merchant token, repeat using `merchant_authorization_url`,
`merchant_callback_url` and the Merchant code-exchange request. That overwrites
only `merchant_token`, not the Payment token.

The existing platform console can issue Merchant-audience tokens through its own
PKCE callback `http://localhost:8182/callback` and administrative scopes, but this
collection deliberately uses the portal/machine procedures above. The API clients
are bearer-only, not token-generating clients. Password/implicit grants are off.
The existing browser_login.py helper discards tokens and therefore does not supply
a Postman token; do not modify it to print JWTs.

Decode token payloads only locally if inspecting claims. Confirm `iss`, `aud`,
`typ=Bearer`, nonblank `sub`/`azp`, `iat`, `exp`, scope and API roles. Machine `azp`
is payflow-payment-service; browser `azp` is payflow-portal. Decoding alone is NOT
signature verification. The matching API's 403 and the opposite API's 401 provide
the live decoder checks. For complete OIDC login validation use the existing
browser helper separately; this collection does not establish a user session or
use ID-token claims as an authentication decision.

## Requests, headers, bodies and expected results

[REQUESTS.md](REQUESTS.md) lists all 32 API requests with exact local URLs, auth and
expected statuses. Folder 00 has three optional/manual token-setup requests.

- Every API request sends `Accept: application/json`.
- No Auth means NO Authorization header; remove inherited authorization/cookies.
- Merchant bearer requests use `Authorization: Bearer {{merchant_token}}`.
- Payment bearer requests use `Authorization: Bearer {{payment_token}}`.
- Invalid-token requests use literal `Authorization: Bearer not-a-valid-jwt`.
- Wrong audience: send Payment-only token to Merchant and Merchant-only token to
  Payment. Do not use an expired token or a dual-audience token for this check.
- POST requests add `Content-Type: application/json`. Payment POST also sends
  `Idempotency-Key: postman-slice2-denied` (not an authentication credential).
- All GET requests have no body. Dummy UUID reads need no existing database record:
  the security filter must reject them before object lookup.

Merchant POST body:

```json
{
  "merchantId": "MER-POSTMAN",
  "businessName": "Postman Test Business",
  "businessEmail": "postman@example.test",
  "contactPhone": "+2348012345678"
}
```

Payment POST body:

```json
{
  "merchantId": "MER-POSTMAN",
  "reference": "POSTMAN-SLICE2-DENIED",
  "amount": 100.00,
  "currency": "NGN",
  "description": "Must be denied before persistence"
}
```

Healthy anonymous GET response: 200 with `status: UP`, no components/details;
Boot may include generic group names. A 503/DOWN indicates dependency health,
not an authentication failure; fix the prerequisite before recording a pass.

Anonymous/invalid/wrong-audience response: 401 and:

```json
{
  "timestamp": "<runtime timestamp>",
  "status": 401,
  "error": "Unauthorized",
  "message": "Bearer authentication is required",
  "path": "<requested path>",
  "details": []
}
```

Also expect `WWW-Authenticate: Bearer`, `Cache-Control: no-store`, JSON content.
Valid-token protected response: 403, the same shape with `error: Forbidden` and
`message: Access is denied`. No authentication diagnostic or resource data should
appear. Unknown routes intentionally return 401/403 rather than 404. No 2xx
business response is acceptable. Authentication failures should not be recorded
as successful valid-token authorization tests.

## Blockers, cleanup and evidence

- Docker Desktop unavailable or port occupied: stop this procedure; do not stop
  other applications or run an unqualified Compose up/down.
- Missing/outdated application schema: blocked by design, because migration is off.
- No human test user / Payment API role: Payment positive-token tests are blocked;
  the machine client cannot substitute for one.
- Stale/drifted persisted realm or rotated client secret: report mismatch; editing
  the import JSON or restarting will not repair it automatically.
- Token expired, callback mismatch, reused code or wrong verifier: obtain a fresh
  code/token using the same exact registered redirect; do not weaken validation.
- Request has an Origin header: CORS can produce a separate 403; remove it for
  these authentication tests so it does not mask a JWT problem.

Record request name, HTTP status and sanitized pass/fail assertions. Do not share
access/refresh tokens, authorization codes, private session exports or screenshots
of token responses. No live test results are claimed by this preparation.

After you finish, use Ctrl-C only in the terminals for JVMs you started. Preserve
all PostgreSQL and identity volumes. Leave previously running containers alone;
coordinate stopping only containers you personally started. Never use `down -v`,
volume removal, prune commands, or database resets.

Reference for Postman OAuth callback/PKCE behavior:
https://learning.postman.com/docs/use/send-requests/authorization/oauth-20/
