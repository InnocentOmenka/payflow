# Slice 4 — Tenant authorization and Payment ownership

Only `POST /api/v1/payments` and `GET /api/v1/payments/{paymentId}` are enabled for
merchant users. Shared JWT verification, stateless bearer authentication, CORS,
anonymous health and deny-by-default policies remain in place. Gateway, event
contracts, payment processing, Ledger and Notification production code are unchanged.

The caller must use a `payflow-portal` access token with Payment audience,
Payment API client role `merchant-admin` or `merchant-operator`, and the exact
operation scope (`payment.create` or `payment.read`). Realm roles, platform-console
tokens, machine tokens, email, identity headers and request merchant IDs cannot
establish tenant ownership. Both database membership roles may perform both operations.

Payment calls `POST /internal/v1/merchant-access/resolve` with the issuer/subject
from its framework-validated user JWT. It authenticates using the existing
`payflow-payment-service` client-credentials grant. Merchant requires its own API
audience, exact machine client, `access-resolver` client role and
`merchant.access.resolve` scope at both HTTP and service boundaries. It trusts
only that machine identity to attest the already-validated human identity;
the user token is not forwarded across API audiences. The endpoint is not a
general human directory or tenant administration API.

Merchant queries its existing exact `(issuer, subject)` membership binding and
merchant record for every request. Revoked/absent memberships and PENDING/CLOSED
merchants are denied. ACTIVE permits create/read; SUSPENDED permits read only.
Machine tokens may be reused until expiry; membership responses are never cached.
A request already authorized before revocation/suspension may finish: there is
no distributed transaction or instantaneous cancellation across service databases.

Creation retains the existing JSON body and 202 response. The body `merchantId`
must exactly match the resolved canonical readable identifier (`MER-...`, not the
Merchant UUID). Ownership is checked before idempotency lookup. Payment reads
and idempotency result reads are scoped by both payment UUID and merchant ID.
Trusted internal core services remain available to workers/fixtures; HTTP always
enters through `MerchantPaymentService`, which independently requires the caller.

HTTP outcomes:

| Condition | Status |
| --- | --- |
| Missing/rejected user bearer authentication | 401 |
| Unapproved client/role/scope, missing/revoked membership, prohibited lifecycle | 403 |
| Creation names a foreign/unknown merchant | Same generic 403; supplied merchant is not looked up |
| Payment absent or belongs to another tenant | Same generic 404 |
| Missing machine credentials, token/transport failure, invalid resolver response | Generic 503, no payment writes |

Unknown resolver request fields are rejected. Resolver responses must match the
requested issuer/subject and contain valid merchant identity, role and lifecycle.
Outbound connections have a 2-second connect timeout, 3-second read timeout and
do not follow redirects. Credential, token and remote error bodies are not returned
in failure responses. An upstream resolver 403 is a generic denial; other upstream
HTTP failures fail closed as 503. No automatic payment write retries are added.

## Local configuration

Keep the existing Keycloak realm, portal scopes and machine-client grant. No new
realm privileges or migrations are needed. Configure Payment's process privately:

- `PAYFLOW_MERCHANT_BASE_URL` defaults to `http://localhost:8086`.
- `PAYFLOW_TOKEN_URI` defaults to the configured JWT issuer's token endpoint.
- `PAYFLOW_PAYMENT_SERVICE_SECRET` must be supplied from the existing private
  credential source. The application does not load `.env.keycloak.local` itself.

If the secret is absent the service can start, but authorized human requests fail
closed with 503. Startup never provisions users, memberships or merchant state.
Production deployment must use trusted HTTPS endpoints and protect the machine
credential. Existing browser-origin restrictions are unchanged; this slice adds
no portal frontend or CORS permissions.

## Tests

Run from the repository root; tests use only disposable containers and synthetic
signed identities, not the live Keycloak instance or application databases:

```sh
mvn -Ppayment-lifecycle -pl integration-tests/payment-lifecycle -am \
  -Dtest=PaymentLifecycleTest,MerchantAccessClientTest,ResourceServerSecurityTest,HttpSecurityIntegrationTest,MembershipAdministrationIntegrationTest,PaymentServiceTest,PaymentServiceIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The two existing pipeline scenarios now enter through authenticated HTTP: the
admin success path and operator decline path retain real outboxes, Kafka consumers,
ledger accounting and independent webhook signature validation. Tests also exercise
the resolver and client-credentials exchange over HTTP, tenant isolation, revocation,
lifecycle restrictions, invalid capabilities and unavailable authorization services.

## Validation — 2026-11-12

The focused command passed 124 tests with zero failures, errors or skips:
shared security 59, Merchant 35, Payment 13, Ledger 2, Notification 2, and
lifecycle/client authorization 13 (9 HTTP/pipeline cases plus 4 client cases).
Both success and decline completed through the authenticated Payment HTTP API,
real Kafka and PostgreSQL, Ledger and signed webhook delivery. The initial
redirect-status defect was fixed and its regression test passed. Whitespace and
private-key/token-pattern checks passed. No live Keycloak/Postman session or
existing application database was used.

## Slice 4 file manifest

Only these 22 files were added or modified for this milestone:

```text
integration-tests/payment-lifecycle/README.md
integration-tests/payment-lifecycle/src/test/java/com/innocodes/payflow/lifecycle/MerchantAccessClientTest.java
integration-tests/payment-lifecycle/src/test/java/com/innocodes/payflow/lifecycle/PaymentLifecycleTest.java
integration-tests/payment-lifecycle/src/test/java/com/innocodes/payflow/lifecycle/TestIdentityProvider.java
services/merchant-service/src/main/java/com/innocodes/payflow/merchant/access/MerchantAccessController.java
services/merchant-service/src/main/java/com/innocodes/payflow/merchant/access/MerchantAccessRequest.java
services/merchant-service/src/main/java/com/innocodes/payflow/merchant/access/MerchantAccessService.java
services/merchant-service/src/main/java/com/innocodes/payflow/merchant/config/SecurityConfig.java
services/merchant-service/src/main/java/com/innocodes/payflow/merchant/security/AccessResolverAuthorization.java
services/payment-service/TENANT-AUTHORIZATION.md
services/payment-service/pom.xml
services/payment-service/src/main/java/com/innocodes/payflow/payment/config/SecurityConfig.java
services/payment-service/src/main/java/com/innocodes/payflow/payment/controller/PaymentController.java
services/payment-service/src/main/java/com/innocodes/payflow/payment/exception/GlobalExceptionHandler.java
services/payment-service/src/main/java/com/innocodes/payflow/payment/merchant/AuthorizationUnavailableException.java
services/payment-service/src/main/java/com/innocodes/payflow/payment/merchant/MerchantAccessClient.java
services/payment-service/src/main/java/com/innocodes/payflow/payment/repository/PaymentRepository.java
services/payment-service/src/main/java/com/innocodes/payflow/payment/security/MerchantPaymentAuthorization.java
services/payment-service/src/main/java/com/innocodes/payflow/payment/service/MerchantPaymentService.java
services/payment-service/src/main/java/com/innocodes/payflow/payment/service/PaymentService.java
services/payment-service/src/main/resources/application.yml
services/payment-service/src/test/java/com/innocodes/payflow/payment/service/PaymentServiceTest.java
```
