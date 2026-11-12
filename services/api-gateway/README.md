# API Gateway — Payment routing

Spring Cloud Gateway Server Web MVC on Java 21, Spring Boot 4.1.2,
Spring Cloud 2025.1.4 and Gateway 5.0.4. Dependencies remain gateway-local.

## Route and security contract

Only these Payment Controller endpoints are proxied, without path rewriting:

| Method and path | Required scope |
| --- | --- |
| POST `/api/v1/payments` | `payment.create` |
| GET `/api/v1/payments/{paymentId}` | `payment.read` |

Both require a framework-validated access token with trusted issuer/signature,
Payment audience `payflow-payment-api`, `azp=payflow-portal`, and Payment API
client role `merchant-admin` or `merchant-operator`. Both roles may perform both
operations. Realm/other-API roles and platform-console/machine clients do not grant
access. Missing/invalid JWTs return 401; insufficient capability or an unapproved
route returns 403. Exact GET `/actuator/health` alone remains anonymous.
Internal resolver routes, other management endpoints, unknown/nested paths and
unapproved HTTP methods are not exposed. Browser-origin restrictions are unchanged.

Gateway imports shared `ResourceServerJwtConfiguration` and calls the existing
`ResourceServerHttpSecurity.configure(...)`; its own single security chain adds
the two explicit operation grants before `anyRequest().denyAll()`. The shared
fail-closed configuration itself was not modified. No new authentication system,
service discovery, rate limiter, circuit breaker or retry filter is introduced.

The original Authorization bearer header, Idempotency-Key, JSON body, query and
Payment response status/body are forwarded. Servlet processing may normalize a
JSON Content-Type by adding a UTF-8 charset. The outgoing header filter removes
case-insensitive `X-User-*`, `X-Merchant-*`, `X-Principal-*`, `X-Authenticated-*`,
and exact `X-User`, `X-Merchant`, `X-Principal`, `X-Role`, `X-Roles`, `X-Subject`,
`X-Issuer` headers. These headers never establish identity. Gateway's existing
untrusted Forwarded/X-Forwarded handling is retained.

Payment independently validates the original JWT and still enters through its
`MerchantPaymentService`: it resolves current membership through Merchant's
machine-protected endpoint, checks lifecycle and creation ownership, and scopes
retrieval by payment UUID plus merchant ID. No Payment/Merchant business logic,
event contract, database schema or payment processing behavior changed.

## Configuration and operation

| Environment variable | Default |
| --- | --- |
| `GATEWAY_SERVER_PORT` | `8080` |
| `PAYFLOW_PAYMENT_SERVICE_URL` | `http://localhost:8081` (actual Payment default) |
| `PAYFLOW_GATEWAY_CONNECT_TIMEOUT` | `2s` |
| `PAYFLOW_GATEWAY_RESPONSE_TIMEOUT` | `3s` |
| `PAYFLOW_JWT_ISSUER` | `http://localhost:8180/realms/payflow` |
| `PAYFLOW_JWT_JWK_SET_URI` | `http://localhost:8180/realms/payflow/protocol/openid-connect/certs` |
| `PAYFLOW_GATEWAY_JWT_AUDIENCE` | `payflow-payment-api` |

For Kubernetes, supply the Payment ClusterIP service URL and reachable JWKS while
preserving the trusted issuer. No Kubernetes resources are created here. The
upstream URL is trusted operator configuration, never taken from caller input.
The configured JDK client does not follow redirects. Connect/response timeouts
are bounded, and there are no automatic Gateway POST retries. A pre-response
transport failure returns sanitized 502; a timeout returns sanitized 504. If an
upstream streaming response has already committed, a late body failure may end
that response rather than replace its status. A timeout does not prove Payment
failed to commit: any deliberate client retry must reuse the same Idempotency-Key.

Build/run from the repository root (check the selected port is free first):

```sh
mvn -pl services/api-gateway -am package
java -jar services/api-gateway/target/api-gateway-1.0.0-SNAPSHOT.jar
```

This does not start or reconfigure Payment, Merchant, Keycloak, Kafka or databases.
Application configuration reads no local `.env` file or stored bearer token.

## HTTP request flow

1. Tomcat receives the request; the shared bearer authentication filter validates
   the JWT using configured JWKS, issuer, audience, times and access-token claims.
2. Gateway's security chain checks the exact HTTP method/path, portal client,
   operation scope and API-specific role. Denied requests never reach upstream.
3. The matching YAML route selects the configured Payment URL. The request-header
   filter removes identity assertions while keeping the original bearer token.
4. Gateway's bounded HTTP client forwards the original method/path/body/query and
   Idempotency-Key. Payment performs its own authentication and tenant checks.
5. Payment's status/body return to the caller; transport failures use the sanitized
   Gateway error handler. No retry filter repeats a POST.

## Focused tests and validation — 2026-11-12

```sh
mvn -pl services/api-gateway -am test
```

Passed 107 tests, zero failures/errors/skips: shared security 59, Gateway 48.
Tests use the real embedded Gateway, ephemeral RSA/JWKS and a local mock Payment
server; no Docker, live Keycloak or application database is required. They check
health, exactly two configured routes, both merchant roles, token/body/path/query
and idempotency forwarding, identity-header stripping, operation scopes, client
and role denial, unsupported methods, invalid JWTs, internal/unknown route denial,
upstream rejection/status preservation, redirect non-following, connection loss,
bounded timeout and one POST attempt on failures. Payment ownership itself is
covered by the existing Slice 4 tests; the mock upstream does not replace that
service boundary. Whitespace checks passed.

## Changed files

```text
services/api-gateway/README.md
services/api-gateway/src/main/java/com/innocodes/payflow/gateway/ApiGatewayApplication.java
services/api-gateway/src/main/java/com/innocodes/payflow/gateway/GatewaySecurityConfiguration.java
services/api-gateway/src/main/java/com/innocodes/payflow/gateway/GatewayUpstreamErrors.java
services/api-gateway/src/main/resources/application.yml
services/api-gateway/src/test/java/com/innocodes/payflow/gateway/GatewaySecurityIntegrationTest.java
```
