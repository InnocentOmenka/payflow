# Notification Service

Consumes the shared `PaymentProcessedEvent` from `payment-processed` using
`notification-service-group`. Both successful and failed payment results produce webhooks.
The independent recovery topic is `payment-processed-notification-dlt`.

## Persistence and transaction boundaries

1. The Kafka consumer deserializes with Jackson 3 and invokes the public transactional
   ingestion service. Validation rejects malformed identities, status, currency and amount.
2. Ingestion stores the canonical event and the exact immutable webhook body in `notifications`.
   `event_id` is the primary key and `payment_id` is unique. Equivalent amount scales and
   timestamps within the same microsecond compare equally; conflicting results are rejected.
   The payload event ID is authoritative; outbox headers are not business identity.
3. The transaction commits before manual Kafka acknowledgment. Uniqueness failures propagate;
   redelivery starts a new transaction. Permanent invalid/conflicting messages go straight to
   the DLT. Other failures get three retries one second apart. Recovery offsets are committed
   only after confirmed DLT publication. DLT publication can itself be duplicated after a crash.
4. A separate worker claims one due row with `FOR UPDATE SKIP LOCKED` in a short transaction,
   increments the attempt number, and creates a STARTED attempt with a unique lease token.
5. HTTP runs after claim commit and without a database transaction. A separate completion
   transaction checks the live lease/token and atomically records the outcome and next state.
   An expired claim is recovered by marking its attempt LEASE_EXPIRED and issuing a new token.
   Even crashes before HTTP count against the attempt limit; final expired claims become FAILED.

JDBC is intentional for explicit PostgreSQL locking and fenced updates. No cross-service
relationships or changes to the shared event contract are introduced.

`destination_ref` is the merchant ID, resolved at delivery time through
`MerchantWebhookDestinationResolver`. The configured adapter is isolated so a Merchant API
or local configuration projection can replace it. URL/secret changes affect future retries;
configuration is not persisted in notification rows. Missing destinations remain durable and
retry until exhaustion. Replays never reset attempts, state, destination reference or body.

## Webhook contract and verification

The exact persisted UTF-8 JSON object has fields:
`version` (1), `type` (`PaymentProcessed`), `eventId`, `paymentId`, `merchantId`,
`reference`, `status`, `amount`, `currency`, and `timestamp` (payment processing time).
`Content-Type` is `application/json`. The `eventId` and body remain stable on every retry.

Headers:

- `PayFlow-Webhook-Id`: payload event UUID.
- `PayFlow-Webhook-Timestamp`: current Unix epoch seconds for this delivery.
- `PayFlow-Webhook-Signature`: `v1=` followed by lowercase hexadecimal HMAC-SHA256.

The signed bytes are `timestamp + "." + exact UTF-8 request body`, using the configured
UTF-8 merchant secret (at least 32 bytes; generate cryptographically random secrets).
Receivers should check timestamp freshness (for example five minutes with synchronized clocks),
verify the signature with a constant-time comparison over the original body bytes, and durably
deduplicate `eventId` before applying side effects. Rotate secrets through secured configuration;
coordinate receiver overlap during rotation. This version sends one signature, not multiple keys.

Delivery is **at least once**: a merchant may accept a request and the sender may crash before
recording success. Expired leases can overlap with a slow/stalled previous process. Fencing
protects database state, not external side effects. Merchant deduplication is required.

## HTTP, retries, and security

- Any 2xx: DELIVERED. HTTP 408, 429 and 5xx: retry. Other responses (including redirects and
  other 4xx): terminal FAILED. Network errors, timeouts, and missing configuration: retry.
  Unsafe destinations: terminal FAILED. Response bodies are discarded and never logged.
- Eight attempts by default. Equal-jitter exponential backoff: for failed attempt `n`,
  `cap = min(1 hour, 5 seconds * 2^(n-1))`, delay in `[cap/2, cap)`. Retry-After is not used.
- Connect timeout 2 seconds, response/socket timeout 5 seconds, overall request deadline
  10 seconds (includes DNS), lease 30 seconds. Lease must exceed request deadline by more
  than one second. Automatic HTTP retries, redirects, cookies and system proxy configuration
  are disabled. Each tracked attempt makes at most one application HTTP request.
- Production requires HTTPS on port 443 with normal TLS hostname/certificate verification.
  Userinfo and fragments are rejected. Every resolved address is checked; loopback, private,
  link-local, metadata, multicast and reserved ranges are blocked. Validated DNS addresses
  are pinned into the actual client connection resolver, preventing DNS rebinding.
- Only the `dev` profile plus `payflow.webhook.allow-local=true` permits loopback mock receivers.
  Plain HTTP is permitted only to loopback, even in dev. Private-network ranges stay blocked.
- Secrets belong in environment/config-tree/external configuration, never source code. Do not
  enable HTTP wire/header logging or expose configuration endpoints: signatures and payloads
  are sensitive. Application logs contain event IDs, attempt counts and fixed failure codes.

## Configuration and local receiver

Create a dedicated PostgreSQL database `payflow_notification` (the application does not create
it). Configure `NOTIFICATION_DATABASE_URL`, `NOTIFICATION_DATABASE_USERNAME`,
`NOTIFICATION_DATABASE_PASSWORD`, and `KAFKA_BOOTSTRAP_SERVERS` as needed. Liquibase applies
only this service's additive migrations. Compose currently provides the shared local broker
and PostgreSQL server; it does not provision service-specific databases automatically.

An external, untracked configuration file can contain:

```yaml
payflow:
  webhook:
    merchants:
      "[MER-1001]":
        url: http://127.0.0.1:8090/webhook
        secret: ${WEBHOOK_SECRET}
```

The brackets preserve case and punctuation in Spring map keys. For local use, supply
`SPRING_PROFILES_ACTIVE=dev`, `PAYFLOW_WEBHOOK_ALLOW_LOCAL=true`, and
`SPRING_CONFIG_ADDITIONAL_LOCATION=file:/absolute/path/to/local-webhooks.yml`.
Run `WEBHOOK_SECRET` through your environment and launch `python3 dev/mock_merchant.py`.
The mock verifies the timestamp/signature and reports only event ID and status. It is a
verification aid, not a durable merchant application. It does not implement receiver deduplication.

The service defaults to port 8084. `payflow.webhook.worker-enabled=false` disables only the
worker. `spring.kafka.listener.auto-startup=false` plus `spring.kafka.admin.auto-create=false`
allow database-only tests or maintenance without a Kafka broker. DLT partition count (3) and
replication factor (1 for local development) are configurable under `payflow.kafka.dlt`.
Production topic provisioning should use the deployment's replication and retention policy.

## Observability and tests

`notifications` exposes delivery state, attempts, next due time, lease, timestamps and a fixed
last-failure code. `notification_attempts` retains every dispatch attempt (including missing
configuration and crashed claims), status if available, HTTP duration and completion time.
For expired attempts HTTP status/duration may be unknown. Terminal records are retained.

Micrometer records `payflow.webhook.attempts` with low-cardinality delivered/failed/stale
outcomes and `payflow.webhook.duration`. Only health/info HTTP endpoints are exposed by default.
Deploy monitoring for pending age, terminal failures, expired leases, DLT growth and Kafka lag.
Useful queries include:

```sql
SELECT delivery_state, count(*), min(created_at) AS oldest
FROM notifications GROUP BY delivery_state;
SELECT event_id, attempt_count, last_failure FROM notifications WHERE delivery_state='FAILED';
SELECT * FROM notification_attempts WHERE event_id = '<event UUID>' ORDER BY attempt_number;
```

No public redrive/admin API or retention cleanup is implemented. Manual redrive, archival and
alerting require an operational policy; do not edit terminal rows casually.

Run `mvn -pl services/notification-service -am test` from the repository root. Tests use
isolated PostgreSQL/Kafka Testcontainers and actual loopback HTTP receivers; the database-only
suite explicitly disables Kafka. Existing applications, volumes and databases are not reset.
