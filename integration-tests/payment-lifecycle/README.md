# Isolated payment lifecycle integration tests

Run from the repository root with Java 21+, Maven and Docker available:

```sh
mvn -Ppayment-lifecycle -pl integration-tests/payment-lifecycle -am \
  -Dtest=PaymentLifecycleTest,MerchantAccessClientTest -Dsurefire.failIfNoSpecifiedTests=false test
```

The opt-in profile adds only this test module. Use the `test` goal: the reactor
supplies service classes before executable Spring Boot jar repackaging. The
`failIfNoSpecifiedTests` option lets upstream modules build without running their
unrelated tests; it does not skip this module's lifecycle/authorization tests. To run
upstream regression suites too, omit both `-D` options.

The fixture starts real application contexts for Merchant, Payment, Transaction
Processor, Ledger and Notification. Each has a separate database created inside
one fresh PostgreSQL 17 Testcontainer; Kafka 4.1.2 is another fresh Testcontainer.
Service-specific resource loading keeps their identically named configuration
and Liquibase migration resources separate. No schemas or event contracts are
copied or replaced. Normal consumers, scheduled outbox publishers, merchant
configuration projection and the notification delivery worker perform the work.

HTTP services bind random loopback ports with their existing security chains.
Anonymous business/protected management requests must still return 401.
Transaction Processor has no HTTP server. No Keycloak instance, token, host
database, `.env` file or local configuration is needed. A local test identity
provider serves signed synthetic JWTs, JWKS and client-credentials responses.
Payment initiation and retrieval use the real authenticated HTTP API and the
real Merchant resolver, including Payment's Spring OAuth2 machine client.
This does not validate configuration of a separately running Keycloak realm.

For each scenario the fixture registers and activates a unique merchant through
Merchant's existing application service, provisions an ephemeral key using the
existing encrypted-file development adapter, configures the webhook and waits
for the real Kafka configuration projection. Keys and the 0600 master-key file
live only in JUnit's temporary directory, which JUnit removes after the contexts
close. No static destination resolver, mocked broker, mocked business service or
manually inserted payment result is used.

The two pipeline scenarios initiate through the HTTP API into `PaymentService`
and use bounded Awaitility waits:

- A normal reference reaches `SUCCESSFUL` (the existing success enum), with one
  `PAYMENT_CAPTURE` journal, equal amount debit 1001 / credit 2001, and a delivered
  webhook for the same payment/event/merchant/amount/currency.
- A reference containing `DECLINE` reaches `FAILED` with `SIMULATED_DECLINE`, a
  committed Ledger result receipt and zero journals, plus a delivered failure
  webhook. Waiting for the receipt prevents a false pass before Ledger consumes.

Both check published Payment/Processor outboxes, idempotent initiation, durable
Notification delivery/attempt records and key readiness. The loopback receiver
independently computes HMAC-SHA256 over `timestamp + "." + exact_body`, checks
timestamp freshness and event/key/version headers, and returns 204 only for valid
requests. It allows duplicate valid webhook deliveries because delivery is
at-least-once. No key values or raw request headers are logged.

Application contexts close before the containers and temporary files are removed.
Existing application services, databases, Docker volumes and realm state are not
started, stopped, migrated or reset. This is simulated authorization, not a real
payment provider transaction; fault injection/replay coverage remains in the
existing service integration suites. Additional HTTP cases cover cross-tenant
creation/retrieval/idempotency isolation, revocation using the same unexpired
token, ACTIVE/SUSPENDED/CLOSED policy, unbound/PENDING users, exact machine
capabilities and invalid user tokens/roles/scopes/clients. Client tests exercise
resolver failures, malformed responses, missing credentials and redirect denial.

## Original milestone validation — 2026-11-12

The focused command above passed both lifecycle scenarios with zero failures,
errors or skips. A second run including `ResourceServerSecurityTest`,
`HttpSecurityIntegrationTest` and `MembershipAdministrationIntegrationTest`
passed 102 tests total: shared security 59, Merchant 35, Payment 2, Ledger 2,
Notification 2, lifecycle 2. Both runs used fresh disposable containers. Working
tree and new-file whitespace checks passed. No production Java, schema or security
configuration changes were required.
