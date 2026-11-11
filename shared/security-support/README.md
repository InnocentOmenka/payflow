# Slice 2: fail-closed HTTP resource servers

This small module shares JWT validation, authority extraction, filter error
responses and the temporary deny-all HTTP policy. It uses Boot 4.1.2 managed
Spring Security 7.1.2 and Nimbus; it does not issue tokens, authenticate passwords,
resolve merchant ownership or change Kafka/database transaction boundaries.

## Effective HTTP policy

| Request | Anonymous / invalid bearer | Valid access token |
| --- | --- | --- |
| GET /actuator/health | Anonymous allowed; an invalid bearer is rejected | Allowed |
| Any Merchant or Payment business operation | 401 | 403, including platform administrators |
| Development signing-secret provisioning | 401 | 403 |
| Actuator discovery, info, health subpaths, other management paths | 401 | 403 |
| Unknown paths and internal endpoints | 401 | 403 |

Only the exact GET health path is permitted. Health details and components are
hidden for everyone; Boot may include generic health-group names. Health may
return 503 when a dependency is unhealthy. HEAD, probe subpaths and alternative
management paths are not anonymous exceptions. Use GET for local health checks.

Do not replace denyAll with authenticated(): a valid JWT establishes identity,
not ownership. Tenant binding, resource authorization and operational permissions
belong to later slices. No method-security annotations are enabled in this slice;
trusted internal services, scheduled jobs and Kafka consumers do not acquire an
end-user SecurityContext or receive bearer tokens through events.

## JWT contract and trust boundary

- RS256 signature verification against the configured JWKS only.
- Exact configured issuer and required API audience (audience may be a string or list).
- Required nonblank string subject and authorized-party (`azp`), and `typ=Bearer`.
- Required numeric expiry/issued-at, optional numeric not-before, expiry after
  issued-at, and at most 30 seconds of clock skew for time validation.
- Original signed claim types are checked after signature verification, including
  subject/client types that Nimbus can otherwise coerce to strings.
- Optional scope must be a string; resource_access and its role arrays must have
  the expected structure. Unknown roles/scopes do not become authorities.
- Only the current API's allowlisted client roles and scopes map to ROLE_/SCOPE_
  authorities. Realm roles and other API roles never establish privileges.
- ID tokens, unsigned tokens, other algorithms, malformed identities and invalid
  signatures are rejected. `azp` is structurally validated, not yet a per-route
  client allowlist. All protected routes remain denied regardless.

JWKS retrieval has 2-second connection and 3-second read timeouts. Supplying the
explicit JWKS URI avoids discovery at startup; unavailable keys never cause an
unauthenticated fallback. Nimbus handles JWKS caching/refresh. Cached trusted keys
may validate unexpired tokens during an IdP outage; uncached/unavailable keys fail
closed. Access-token revocation/role changes are not instantaneous before expiry.

The stateless filter chain has no form login, Basic authentication, session-based
security context, saved-request redirect or logout endpoint. Only the standard
Authorization Bearer header is accepted. Query parameters and caller-supplied
identity/merchant headers are not authentication.

CSRF is disabled for these bearer-only APIs; this must be revisited if cookie
or browser-session authentication is introduced. Cross-origin API calls are not
approved yet: the CORS configuration has no allowed origins. CORS preflight
rejections use Spring's standard 403 response; authentication/authorization
failures use the PayFlow JSON error shape, generic messages and no-store. A 401
retains a Bearer WWW-Authenticate challenge. No JWT/secret diagnostic is returned.

## Service configuration and local operation

Each of Merchant, Payment, Ledger and Notification explicitly imports the shared
configuration and declares its own audience in application.yml:

- Merchant: payflow-merchant-api
- Payment: payflow-payment-api
- Ledger: payflow-ledger-api
- Notification: payflow-notification-api

Slice 1 intentionally defines no Ledger/Notification audiences or operations
roles. No existing token grants management access to those services; health works
without a token. Do not add audience mappings simply to bypass the deny-all policy.

All four default to the isolated local issuer/JWKS. For another environment set
BOTH `PAYFLOW_JWT_ISSUER` and `PAYFLOW_JWT_JWK_SET_URI` explicitly. These are trusted
operator settings, never token-supplied URLs. Production requires HTTPS/trusted
network routing; localhost is development only and differs inside containers.
No client secret is needed to validate resource-server JWTs, and no Keycloak
credentials or realm configuration changed in Slice 2.

Existing services can start without Keycloak; protected requests still require
valid keys/tokens. If local token testing is needed, use the separately managed
Slice 1 stack and helpers in infra/keycloak. Business APIs intentionally return
403 even with those tokens until tenant authorization is implemented.

## Validation

```sh
mvn -pl shared/security-support -am test
mvn -pl services/merchant-service,services/payment-service,services/ledger-service,services/notification-service,services/transaction-processor -am test
git diff --check
```

Security tests use a controlled loopback JWKS fixture with RSA-signed JWTs through
the real Nimbus decoder and Spring security filters, rather than mock JWT
principals. They cover malformed/missing claims, algorithm/signature/issuer/
audience/time failures, authority isolation, deny-all business operations,
401/403, stateless writes, health and management paths, CORS and header spoofing.
Each service also loads its actual YAML and imported security configuration in a
broker/database-independent HTTP security test context.

Existing Merchant controller/database tests now use standalone MVC with real
services and disposable PostgreSQL, explicitly outside the HTTP security boundary.
The development secret controller's response contract is exercised directly.
There is no runtime bypass, permissive security profile or allow-all test filter
chain. Existing Kafka and worker integration tests remain part of regression.

Reference: https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html

## Validation record — 2026-11-11

The full command above passed 320 tests, with zero failures, errors or skips:
webhook-support 2, security-support 59, Merchant 80, Payment 13, Ledger 81,
Notification 85. Event-contracts and Transaction Processor built successfully but
have no tests in this reactor run. This includes real Kafka/PostgreSQL integration
and Notification delivery/lease regressions. Testcontainers used disposable
infrastructure; existing application databases/volumes and the isolated Keycloak
stack were not modified. No live Keycloak login was performed for Slice 2.
