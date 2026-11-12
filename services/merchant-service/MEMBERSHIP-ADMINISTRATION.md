# Checkpoint 3, Slice 3, Step 2 — platform membership administration

Only membership administration is enabled. Merchant registration, tenant profiles,
webhook configuration, signing-secret provisioning, lifecycle operations, Payment
routes and protected Actuator endpoints remain denied over HTTP. Existing domain
services, Kafka consumers, snapshot jobs and outbox workers keep their previous
behavior and do not require an end-user principal.

## Existing Keycloak prerequisites (verified in source)

No realm/client/credential changes are needed:

- Issuer: the configured, validated PayFlow issuer.
- Audience: `payflow-merchant-api`.
- Authorized OAuth client (`azp`): **payflow-platform-console** only.
- API client role: `resource_access.payflow-merchant-api.roles` contains
  **platform-admin** (realm roles and other API roles do not count).
- Scope: **merchant.bindings.manage** (available to that console client).
- The existing console is public Authorization Code + S256 PKCE with service
  accounts and password/implicit grants disabled. Its exact local callback is
  `http://localhost:8182/callback`. Keep it a human-login-only client.

An administrator must assign the existing platform role to the intended human
operator in Keycloak. No human users, credentials or administrative role grants
are seeded by this change. An existing persisted realm can differ from the JSON;
verify its configuration manually before live use. Realm import does not update
an existing realm automatically. Do not weaken client/role/scope checks to repair
an onboarding mismatch. Do not use Keycloak master-realm administrator tokens for
PayFlow membership APIs.

The portal and `payflow-payment-service` machine client are explicitly rejected,
even if a token were to contain the platform role and binding scope. The machine
client's existing Merchant audience alone grants no membership-management access.
Production also requires protected administrator login and MFA policy, trusted
HTTPS routing, and tightly controlled IdP role assignment.

## Authorization boundary

Spring Security validates the signature, issuer, audience, time, subject, access
claim type and claim structure using the existing shared decoder. A Merchant-local
PlatformAuthorization checks the approved client, API role and operation scope
from that authenticated JWT, both in the HTTP policy and inside each transactional
administration method. Actor issuer, subject and client are derived only from this
framework-validated identity, never DTO fields, email, usernames or headers.

The shared security change only separates JWT/stateless setup from route policy.
Merchant has one explicit filter chain. Payment, Ledger and Notification retain
the existing fail-closed policy, using the same shared stateless setup. There is
no generic authenticated-access rule, alternate permissive profile, broad route
allowance or default-user authentication fallback.

Target membership issuer/subject are explicit administrative inputs, not proof of
ownership. The target issuer must exactly equal the trusted issuer. Administrators
must verify that the target subject is an intended human user through their
approved onboarding process. This step intentionally does not add Keycloak Admin
API credentials, per-request user lookup or a human-directory synchronization
system. It cannot independently attest that an arbitrary target subject exists
or represents a human. No request automatically claims a merchant.

## APIs

All requests require a fresh console access token with the role/scope above.
Only exact GET /actuator/health remains anonymous. POST/PUT use JSON.
`merchantId` below is the Merchant UUID, not the human-readable MER identifier.

| Method | Path | Success |
| --- | --- | --- |
| POST | /api/v1/merchants/{merchantId}/memberships | 201 + Location |
| GET | /api/v1/merchants/{merchantId}/memberships?page=0&size=20 | 200 paginated DTO |
| PUT | /api/v1/merchants/{merchantId}/memberships/{membershipId} | 200 |
| POST | /api/v1/merchants/{merchantId}/memberships/{membershipId}/revoke | 200 |

Create body (illustrative subject; use a verified existing human identity):

```json
{"issuer":"http://localhost:8180/realms/payflow","subject":"verified-human-subject","role":"MERCHANT_OPERATOR"}
```

Role-update body:

```json
{"version":0,"role":"MERCHANT_ADMIN"}
```

Revoke body:

```json
{"version":1}
```

Only MERCHANT_ADMIN and MERCHANT_OPERATOR may be assigned. Creation is active;
revocation retains the binding and its global (issuer,subject) uniqueness. No
reactivation, transfer, hard delete, self-registration or single-record GET route
is exposed in this step. No-op role changes/revocation with the current version
return current state without a false audit event; stale versions still return 409.
Issuer, subject, merchant binding and actor fields cannot be supplied to update
or revoke requests. Unknown fields are rejected, not silently applied/ignored.
Membership listing is parent-scoped and bounded to 1–100 records per page.

Membership administration itself does not change merchant lifecycle; future
merchant-user operations must additionally enforce existing lifecycle restrictions.

## Responses and information disclosure

- 401: missing/invalid bearer authentication, with Bearer WWW-Authenticate.
- 403: valid caller without the exact client/role/scope; object existence is not
  consulted before this decision.
- 404: approved administrator requests a missing merchant/membership, or a
  membership under the wrong merchant. All use `Resource not found`.
- 400: invalid DTO, unknown fields or a foreign target issuer.
- 409: duplicate principal binding, stale version or database consistency conflict;
  no existing owner's identifier, SQL or constraint details are returned.
- 500: unexpected failures use the existing generic error response. In particular,
  a failed audit write cannot leave an accepted membership mutation behind.

Responses are DTOs; no JPA entity, signing secret, bearer/refresh token or private
exception diagnostic is returned. Security exceptions arising inside application
methods are explicitly handled as 401/403 rather than the generic 500 fallback.
Existing filter error handling and CSRF/CORS/session defaults are preserved.

## Transactions, concurrency and audit

Every mutation locks the Merchant row first, reads the membership scoped to that
parent, checks its expected optimistic version, flushes the mutation, and inserts
the audit row in the SAME database transaction. Parent locking orders role
updates/revocations; @Version also protects against other JPA writers. Concurrent
duplicate grants across merchants rely on the existing principal uniqueness
constraint. Integrity failures propagate out of the transaction; no lookup is
attempted inside an aborted transaction.

Migration 005 adds `merchant_membership_audit`, indexes, version/event constraints,
and PostgreSQL triggers rejecting UPDATE, DELETE and TRUNCATE. Original migrations
and membership identities are unchanged. Each event preserves target identity,
merchant/membership UUID, old/new role and active state, before/after version,
actor identity/client and timestamp. There is no audit mutation or read API.

GRANTED, ROLE_CHANGED and REVOKED events are appended only for committed changes
made through this administration boundary. Reads, rejected requests and no-ops do
not create successful-change events. Direct repository/DB writes are trusted
internal operations, not an audited administration API; future management paths
must use this boundary. Denied-operation logging and external audit export are
separate production concerns.

Append-only is enforced against ordinary DML, not against a database owner or
superuser able to disable triggers/alter tables. Production needs separate
migration/runtime privileges, restricted database administration, backups and an
independent audit-retention/export policy. No production tamper-proof guarantee
is claimed by this local implementation.

## Validation

```sh
mvn -pl services/merchant-service,services/payment-service,services/ledger-service,services/notification-service,services/transaction-processor -am test
git diff --check
```

Tests use real RSA-signed JWTs and a controlled JWKS endpoint, disposable PostgreSQL
with Liquibase, real Spring security filters, and existing Kafka/worker regression
suites. They cover strict caller permissions, spoofing, unknown/immutable fields,
DTOs and pagination, wrong-parent 404, audit content/append-only enforcement,
transaction rollback, concurrent grants and role-change/revocation races.
No tests require changing the existing Keycloak credentials or realm, and no
migration has been applied to an existing application database during this task.
Live console PKCE/API validation remains a separate manual step.

## Validation record — 2026-11-11

The complete command above passed 394 tests (zero failures, errors or skips):
webhook-support 2, security-support 59, Merchant 154 (including 33 new administration
tests), Payment 13, Ledger 81, Notification 85. Event-contracts and Transaction
Processor built successfully but have no tests in this run. Shared decoder/HTTP
security regressions, Kafka/PostgreSQL integration and background-worker tests
passed. No existing application database or Docker volume was migrated or reset.
Live platform-console PKCE and membership API validation has not been performed.
Migration 005 does not fabricate audit history for pre-existing memberships.

## Completion validation — 2026-11-12

The full affected-service Maven regression was rerun successfully: 394 tests,
zero failures, errors or skips (webhook-support 2, security-support 59, Merchant
154, Payment 13, Ledger 81, Notification 85). Merchant's 33 administration cases
cover create/list/update/revoke, strict authorization, stale versions, wrong-parent
lookups, concurrency, audit content, append-only enforcement and rollback on audit
failure. Event Contracts and Transaction Processor also built successfully; this
run contains no Transaction Processor tests. The Keycloak realm/verifier/login
helper Python suite passed all 52 tests. Tests used disposable infrastructure;
existing application databases, services and the live realm were not reset.

Developer-confirmed live evidence: Keycloak authentication succeeds; the expected
403 on merchant listing remains enforced; the development merchant was provisioned;
migrations 004/005 and their tables were confirmed present; membership creation
returned 201 and membership listing returned 200 with the created membership.
Live update/revoke and negative authorization scenarios were not repeated during
completion; their evidence in this review is automated integration coverage.

Tenant business authorization and Payment ownership/access resolution remain
outside this milestone. Payment and Merchant business/webhook management routes
remain denied; a membership grant alone does not enable a Postman payment lifecycle.
