# Merchant webhook configuration — Checkpoint 2

## Security and deployment boundary

Merchant management remains **unauthenticated and not production-ready**. Keycloak and authorization
are not implemented. Do not expose management or provisioning endpoints to production traffic.
Production secret distribution is an integration boundary, not a claim made by this checkpoint.
Payment and Ledger behavior and the payment event contract are unchanged.

`shared/webhook-support` supplies `SecretReader`, `SecretWriter`, redacted provisioning values,
an isolated development file adapter, and the outbound destination policy reused by both services.
`shared/event-contracts` contains only the new merchant event record. No cross-service JPA relations
or per-delivery HTTP requests to Merchant Service are introduced.

The default secret adapter is `unavailable`: resolution/provisioning fails closed. The only concrete
adapter is `local-files`, permitted only with the explicit `dev` profile. It generates 32 random bytes
encoded as base64url for each signing key and stores AES-256-GCM ciphertext in private files, using
an externally provisioned 32-byte master-key file. Merchant ID, reference UUID and key version are
associated data; a key cannot be rebound to a different merchant/version unnoticed. Database tables
and Kafka contain references/versions only, never the signing key or ciphertext.

This is development protection for stored files, **not production KMS, IAM, hardware isolation, or a
complete access-control model**. The master key is available to both services; a process with access
to it and the files can decrypt that namespace. Reader/writer interfaces are capability boundaries
inside the application, not isolation against a compromised same-UID process. Use a read-only secret
volume and read-only master-key mount for Notification, a writable volume for Merchant, private host
permissions (0700 directories / 0600 files), and never mount either into Payment or Ledger. Different
host/container identities and ACLs require deployment configuration; none is claimed by environment
variables alone. Symlink final components and group/world-accessible key files are rejected.

A production implementation should replace these adapters with Google Secret Manager or Vault:
Merchant has scoped key creation/version access; Notification has read-only access only to authorized
webhook secret versions, using workload identity, audited policy and authenticated transport. Preserve
the `merchantId/reference/version` binding. Introduce an authorized onboarding/provisioning workflow
before enabling production secret creation. Do not enable the development reveal controller there.

## APIs and rotation

Use the merchant UUID returned by registration as `{id}`:

| Endpoint | Behavior |
| --- | --- |
| GET `/api/v1/merchants/{id}/webhook` | Configuration metadata only; 404 if absent |
| PUT `/api/v1/merchants/{id}/webhook` | Create/update with expected configuration version; initially 0 |
| POST `/api/v1/merchants/{id}/webhook/secrets` | Dev-only, explicit opt-in, loopback-only one-time candidate key reveal |
| POST `/api/v1/merchants/{id}/webhook/replay` | Transactionally append current snapshot to outbox; 202 |

Provisioning is available only with `dev` and `payflow.secrets.provisioning-enabled=true`. It ignores
forwarded client addresses and requires the direct client to be loopback. The response is 201 with
`Cache-Control: no-store`, `Pragma: no-cache`, and `{reference, version, secret}`. There is no GET reveal
endpoint. Repeating POST creates a different candidate; it does not reveal a previous key. Do not
capture response bodies in proxy/access tracing. Losing a provisioning response requires another
candidate. Candidate metadata persists; external encrypted files orphaned by rollback require an
operator cleanup policy. No automatic destructive cleanup is implemented.

Rotation is explicitly two phase:

1. Provision a candidate and install it at the merchant receiver without removing the prior key.
2. PUT the configuration with that candidate's reference/version. Only this operation activates it.

Example configuration body (never include the secret value):

```json
{
  "version": 0,
  "destinationUrl": "https://merchant.example/webhook",
  "enabled": true,
  "secretRef": "REPLACE_WITH_PROVISIONED_REFERENCE_UUID",
  "secretVersion": 1
}
```

A disabled initial configuration may omit both key fields. Enabling requires an ACTIVE merchant and
a resolvable key registered to that merchant. Once assigned, key versions cannot decrease or be
removed; rotation must select a newer provisioned version. The configuration version is independent
of the merchant profile's optimistic version and increments for every update and lifecycle change.
Provisioning alone does not change configuration/version or emit an event.

PENDING merchants can prepare disabled configuration and provision candidates. ACTIVE merchants can
configure/enable/disable. SUSPENDED merchants can only disable an existing destination/key, not change
it or provision keys. CLOSED merchants cannot mutate configuration or provision keys. Suspension and
closure publish newer lifecycle snapshots automatically. `enabled` records merchant intent; delivery
also requires lifecycle ACTIVE. Reactivation resumes enabled intent. A disabled/closed/suspended
configuration always fences a previous usable configuration.

## Persistence, outbox and Kafka

Merchant migrations:

- `002-create-webhook-configuration.yaml`: one versioned configuration per merchant.
- `003-create-webhook-secrets-outbox.yaml`: candidate key metadata and leased transactional outbox.

The merchant row is locked for configuration/key operations. Expected configuration versions and
key ownership are checked while locked. Lifecycle changes, configuration revision and outbox append
share the same PostgreSQL transaction. Concurrent updates with the same expected version produce one
success and one conflict. Failures roll back metadata and outbox together. Secrets in the external
provider cannot participate in that transaction; unused files are harmless but require retention work.

`MerchantWebhookConfigurationEvent` fields:
`eventId`, `merchantId`, `configurationVersion`, `destinationUrl`, `enabled`, `merchantStatus`,
`secretReference`, `secretVersion`, `occurredAt`.

Topic: `merchant-webhook-config`, keyed by merchant ID, compacted, defaults three partitions and
one replica for local development. Configure `payflow.kafka.config.partitions/replicas` for deployment.
The leased publisher sends the committed stored payload outside DB transactions, waits for Kafka
confirmation, then fences its completion update. Crash-after-send may duplicate the same event ID.
Publication gets eight attempts with bounded exponential delays (2–256 seconds); expired claims are
recoverable. Exhausted rows stay in **DEAD**, the durable publisher dead-letter state. No Kafka DLT
send is pretended possible when the broker itself is unavailable. Replay creates a new current
snapshot; DEAD rows remain for investigation. Published rows also remain; retention is not automatic.

The Notification consumer uses `notification-merchant-config-group`, manual acknowledgment after
projection commit, three retries for transient errors, and
`merchant-webhook-config-notification-dlt` for permanent invalid/conflicting or exhausted messages.
Failed DLT publication leaves the source offset uncommitted. Tombstones are rejected: disable/closure
must be represented by explicit versioned snapshots, not ambiguous null records.

## Notification projection and queued deliveries

Notification migration `002-create-merchant-projection.yaml` adds persistent projection and event
receipt tables. A PostgreSQL transaction-scoped per-merchant advisory lock serializes first inserts
and concurrent updates. Event IDs are durable idempotency keys. Same-version, same business content
with a new event ID/time is a valid reconciliation snapshot; same-version changed content is a
conflict. Reusing an event ID with different payload is also a conflict. Older versions are recorded
and acknowledged but never applied or used to refresh freshness. The latest version supersedes the
old one even if its key is missing. There is **no static/previous-key fallback**.

Selection is at **delivery time**, after the notification's leased claim commits. The immutable
payment body/event ID is preserved, but each attempt uses the latest projected destination/key.
A projection is not usable until its exact bound secret resolves; `secret_ready` is initially false
and is fenced true after resolution. A missing key stays durable/unusable and can recover on a later
attempt without replaying Kafka. Resolution rechecks freshness/version before returning. Newer config
arriving after selection cannot revoke an already selected/in-flight request.

`PayFlow-Webhook-Key-Id` and `PayFlow-Webhook-Key-Version` identify the signing key. The existing
signature scheme remains `v1=hex(HMAC-SHA256(secret, timestamp + "." + exact_body))`; event body and
webhook event ID remain stable. Receivers must verify the exact bytes, check timestamp freshness,
select the identified key and deduplicate the event ID. Keep old keys through propagation and
in-flight overlap; do not retire them merely because PUT returned. Observe projection versions/lag
and allow at least configuration max age, tolerated clock skew and request deadline before retiring
old keys. There is no automatic key deletion or hard revocation guarantee for in-flight requests.

Missing configuration/key, disabled/suspended state, and stale metadata use the existing bounded
notification retry policy. CLOSED is terminal. Long suspensions may exhaust queued notification
attempts; recovery then requires an operational notification redrive, not silently resetting delivery
state. Existing HTTP connection/response/deadline limits, signature format and lease fencing remain.

## Recovery and bounded staleness

Merchant emits current snapshots after 60 seconds without one, checking every ten seconds, in batches
of at most 100. It avoids generating another snapshot while the current version has a pending/in-flight
outbox record. Explicit replay is available after DLT remediation or missed events. Compaction retains
latest merchant state for new consumers; normal consumer restarts resume committed offsets and the
persistent projection. A new group/empty projection can rebuild from retained current snapshots.

Projection freshness is measured from the source event time, not consume/replay time. The default
`payflow.webhook.configuration-max-age=5m` is configurable between two minutes and one hour. If Kafka
or Merchant snapshots stop, Notification fails closed once that age is reached. Old event replay cannot
renew freshness. Same-version identical current snapshots renew it; stale versions do not. Future
source timestamps beyond 60 seconds are invalid. Synchronize clocks. This is bounded eventual
consistency, **not immediate distributed revocation**: suspension/rotation may take up to the freshness
window to fence an old projection during an outage. Monitor snapshot batch capacity for large fleets.

## Local setup and validation

Create a private master-key file and key directory outside the repository. Configure both services:

```yaml
spring:
  profiles:
    active: dev
payflow:
  secrets:
    adapter: local-files
    directory: /absolute/private/path/webhook-keys
    master-key-file: /absolute/private/path/master.key
  webhook:
    allow-local: true
```

Enable provisioning only in Merchant's private development configuration. Use an external config file
or config-tree; do not commit key files. For a mock receiver set `WEBHOOK_KEYS_FILE` to a private JSON
map `{ "reference-uuid": {"version": 1, "secret": "one-time-provisioned-value"} }` and run Notification's
`dev/mock_merchant.py`. It reads the key map per request so both rotation keys can overlap. This mock is
not a durable/deduplicating production receiver.

Production requires public HTTPS on 443, no credentials/fragments, and address validation. The shared
policy blocks loopback, private, metadata, link-local, multicast, and reserved addresses. Dev permits
only loopback plain HTTP. Notification revalidates every resolved address and pins DNS into the socket
resolver, disables redirects and HTTP automatic retries. Do not put credentials in destination query
strings or enable HTTP wire/body/header logging.

Static `payflow.webhook.merchants` configuration is no longer used in production and causes startup to
fail rather than silently override the projection. Legacy regression fixtures use an explicit test-only
adapter in test sources. Provision durable configurations and replay them when migrating the old setup.

For isolated demonstrations, override `payflow.kafka.merchant-config-topic` in both services and
`payflow.kafka.payment-topic`, `payflow.kafka.payment-group`, `payflow.kafka.merchant-config-group` in
Notification; defaults preserve existing topics/groups. `payflow.merchant.outbox.enabled=false` and
`spring.kafka.admin.auto-create=false` keep Merchant database-only tests independent of Kafka.
Notification database-only tests also disable listener startup.

Monitor outbox PENDING age/DEAD counts, configuration DLT and consumer lag, projection confirmed_at and
secret_ready, and existing notification attempt/failure metrics. No key values are needed in telemetry.
Example operational queries:

```sql
SELECT state,count(*),min(created_at) FROM merchant_config_outbox GROUP BY state;
SELECT merchant_id,config_version,confirmed_at,secret_ready FROM merchant_webhook_projection;
```

Run `mvn -pl services/merchant-service,services/notification-service -am test` for module/dependency tests.
Checkpoint recommendations: A `feat(merchant): persist versioned webhook configuration`; B
`feat(merchant): provision webhook keys and persist configuration outbox`; C
`feat(notification): project merchant webhook configuration from Kafka`; D
`test(webhooks): validate configuration propagation rotation and recovery`.

## Executed checkpoint validation

See [CHECKPOINT-2-VALIDATION.md](CHECKPOINT-2-VALIDATION.md) for the fresh 253-test regression,
actual isolated live validation, security findings, and buildable commit boundaries. Production
requires Kafka authentication/TLS and topic ACLs so only the Merchant authority can publish
configuration; metadata events themselves are not independently signed.
