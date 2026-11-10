# Merchant Service — Checkpoint 1

Merchant registration, profiles and lifecycle management. Java 21 / Spring Boot 4.1.2,
PostgreSQL, JPA, Liquibase and Jakarta Bean Validation. No dependency on another service,
shared database, Kafka, webhook configuration, credentials or authentication implementation.

**These management endpoints are unauthenticated and NOT production-ready.** Keep the service
restricted to development/internal environments until Keycloak authentication and authorization
are integrated. In particular, access to personal contact data and activation/suspension/closure
must be authorized. No fake administrator, API key, or hardcoded administrative credential exists.
Future access controls belong at the HTTP/application boundary; the merchant domain is independent
of a particular identity provider. Authentication/ownership and audit policy remain separate work.

## Domain and identifiers

Each merchant has a server-generated UUID `id` and a required caller-supplied `merchantId`.
The merchant identifier is immutable, at most 100 characters, normalized to uppercase and follows
`MER-[A-Z0-9]+(-[A-Z0-9]+)*`, supporting existing references such as `MER-1001` and `MER-TEST-001`.
Checkpoint 1 deliberately does not introduce a sequential identifier allocator, auto-import existing
merchants, or rewrite identifiers already held by Payment/Ledger/Notification Services.

Profiles contain business name (1–200 characters), business email (up to 254), optional contact
phone (7–15 digits, optional leading `+`), status, creation/update timestamps, and version.
Inputs are stripped of surrounding whitespace. Email is normalized to lowercase; email uniqueness
is deliberately case-insensitive for the entire address. Registration always starts PENDING.
The API does not accept a registration status or modification of the UUID/merchant identifier.

| Current status | Permitted next status |
| --- | --- |
| PENDING | ACTIVE, CLOSED |
| ACTIVE | SUSPENDED, CLOSED |
| SUSPENDED | ACTIVE, CLOSED |
| CLOSED | None |

Repeated transitions to the same status return 409. CLOSED is terminal and its profile is read-only.
Profile changes remain allowed while PENDING, ACTIVE or SUSPENDED. Closure retains the record and
its reserved identifier/email; no deletion or reopening endpoint exists.

## APIs

Base path: `/api/v1/merchants`. Responses are DTOs, never JPA entities.

| Method and path | Behavior |
| --- | --- |
| POST `/api/v1/merchants` | Register; 201 and Location header |
| GET `/api/v1/merchants/{id}` | Retrieve by UUID; 200 or 404 |
| GET `/api/v1/merchants/by-identifier/{merchantId}` | Retrieve by human identifier, case-insensitively |
| PUT `/api/v1/merchants/{id}/profile` | Replace profile fields with required version; 200 |
| GET `/api/v1/merchants?page=0&size=20&status=ACTIVE&search=shop` | Paginate/filter; 200 |
| POST `/api/v1/merchants/{id}/activate` | Activate PENDING or SUSPENDED merchant |
| POST `/api/v1/merchants/{id}/suspend` | Suspend ACTIVE merchant |
| POST `/api/v1/merchants/{id}/close` | Close any nonterminal merchant |

Register:

```sh
curl -i http://localhost:8086/api/v1/merchants \
  -H 'Content-Type: application/json' \
  -d '{"merchantId":"MER-1001","businessName":"Example Store","businessEmail":"owner@example.com","contactPhone":"+2348012345678"}'
```

Responses include `id`, `merchantId`, `businessName`, `businessEmail`, `contactPhone`, `status`,
`createdAt`, `updatedAt` and `version` (initially 0). Read back the returned UUID and current version
for later requests. Timestamps are normalized to PostgreSQL microsecond precision.

Replace profile fields; null/omitted contact phone clears it:

```sh
curl -X PUT http://localhost:8086/api/v1/merchants/REPLACE_WITH_UUID/profile \
  -H 'Content-Type: application/json' \
  -d '{"version":0,"businessName":"Example Store Ltd","businessEmail":"accounts@example.com","contactPhone":null}'
```

Activate using the most recently returned version:

```sh
curl -X POST http://localhost:8086/api/v1/merchants/REPLACE_WITH_UUID/activate \
  -H 'Content-Type: application/json' -d '{"version":1}'
```

The list response has `content`, `page`, `size`, `totalElements`, `totalPages`, `first` and `last`.
Page numbers are zero-based, size is 1–100, and status must match an enum value. Search is a
case-insensitive literal substring of business name; `%`, `_` and backslash are escaped rather
than treated as wildcards. Results sort by `createdAt DESC, id ASC` for deterministic ties.

Errors use the existing PayFlow shape:

```json
{
  "timestamp": "2026-11-10T12:00:00Z",
  "status": 409,
  "error": "Conflict",
  "message": "Merchant identifier or business email already exists",
  "path": "/api/v1/merchants",
  "details": []
}
```

Invalid DTOs, malformed JSON/UUIDs, invalid page/filter values and missing/negative mutation versions
return 400. Missing merchants return 404. Duplicate registration/profile email, invalid lifecycle
transitions, closed-profile writes and stale/concurrent versions return 409. Unsupported methods
return 405 (with Allow); unsupported content types return 415. Database details are not exposed.

## Transactions and concurrency

- Registration is one database transaction. Named unique constraints on merchant ID and canonical
  business email are authoritative, including concurrent registration attempts. There is no
  check-then-insert correctness assumption or in-memory locking. A repeated registration returns
  409, including identical data; registration does not implement an idempotency-key protocol.
- Profile/lifecycle writes require a last-seen version in the request. A stale request fails before
  applying changes. JPA `@Version` also includes the loaded version in the SQL UPDATE predicate,
  preventing lost updates when two transactions both initially observe the same version.
- Writes flush before mapping the returned DTO, so a successful response contains the resulting
  version. The transaction proxy commits before the controller returns the response. Constraint
  and optimistic-lock errors roll back and propagate; no queries are attempted inside an aborted
  transaction. Clients should reread and reassess a 409, not blindly retry a lifecycle command.
- Entity methods enforce lifecycle rules and read-only CLOSED profiles. Database constraints enforce
  identifiers, canonical email, basic profile shape, valid status/version and timestamp ordering.
  Database indexes support status-filtered/default pagination. Direct privileged SQL can bypass
  application lifecycle/version behavior; production database permissions must restrict writes.

## Setup, tests, and remaining boundaries

Default port is 8086 (8085 is already occupied by the local Kafka UI). Configure
`MERCHANT_SERVER_PORT`, `MERCHANT_DATABASE_URL`, `MERCHANT_DATABASE_USERNAME` and
`MERCHANT_DATABASE_PASSWORD`. The default database is `payflow_merchant` on localhost:5432.
Provision that dedicated database separately; Liquibase creates only the service's own tables.
Existing Compose configuration provides PostgreSQL but does not create every service database.
No Compose changes, resets or volume deletion are required. Local default database credentials
match the existing development stack; use secured external configuration for deployment.

Run `mvn -pl services/merchant-service -am test` from the repository root. Tests use an isolated
PostgreSQL Testcontainer and actual HTTP requests to a random-port application, never a running
service/database. Unit tests cover validation and the complete lifecycle transition matrix;
integration tests exercise APIs, uniqueness races, optimistic conflicts, pagination and rollback.

Keycloak/access policy, audit history, verification/onboarding review, webhook configuration,
API credentials and merchant events are outside this checkpoint. Business email validation is
syntactic, not proof of ownership. Contains-name filtering currently has no trigram index and
offset pagination is not a snapshot across concurrent requests; revisit these at larger scale.

## Checkpoint 2

Durable webhook configuration, secret provisioning, transactional Kafka propagation and Notification
projection are described in [WEBHOOKS.md](WEBHOOKS.md). Checkpoint 1 API and lifecycle behavior are retained.
