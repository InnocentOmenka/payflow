# Checkpoint 2 — Slice D validation and review boundaries

Validated on 2026-11-10. This record contains no signing keys, master keys, database passwords,
or provisioning response bodies. It records actual executed validation, not proposed checks.

## Fresh regression results

```sh
mvn -pl services/merchant-service,services/notification-service,services/ledger-service,services/transaction-processor -am verify
mvn -pl services/payment-service -am test -Dspring.kafka.listener.auto-startup=false -Dspring.kafka.admin.auto-create=false
```

The local Docker endpoint was supplied through `DOCKER_HOST` for Testcontainers. Payment's existing
database tests explicitly disabled Kafka startup/admin so they did not depend on or join a running
application broker. No Payment, Ledger or Transaction Processor source/test files were changed.

| Module | Tests | Failures/errors/skips |
| --- | ---: | --- |
| Shared webhook support | 2 | 0/0/0 |
| Merchant Service | 78 | 0/0/0 |
| Notification Service | 83 | 0/0/0 |
| Ledger Service | 79 | 0/0/0 |
| Payment Service | 11 | 0/0/0 |
| **Total** | **253** | **0/0/0** |

Transaction Processor compiled and packaged successfully; it has no tests. Event Contracts also
compiled and packaged. `git diff --check` and read-only Docker Compose validation passed.

Coverage includes configuration registration/updates, destination restrictions, lifecycle restrictions,
secret generation/ownership/rotation/non-disclosure, concurrent updates, atomic outbox rollback,
bounded publisher retries and replay, real Kafka publication, consumer duplicates/out-of-order events
and DLT, projection persistence, missing-key recovery, freshness expiry, queued delivery key selection,
HTTP signature verification, notification leases and all existing service regressions.

## Isolated live validation

The existing application PostgreSQL and Kafka containers were stopped when this continuation began.
They were left stopped. Starting Docker Desktop restored its existing UI container according to its
own restart policy; no application container was restarted, reset or reconfigured.

New standalone PostgreSQL and Kafka containers, random loopback ports, separate databases, isolated
topics/groups, and temporary Merchant/Notification JVMs were used. The applications were packaged from
the freshly verified source. No messages were published to the normal payment/configuration topics.
The receiver was the repository's loopback-only development mock with a private versioned key map.

The completed sequence was:

1. Register a new merchant through HTTP and provision a candidate signing key through the explicit
   development-only, loopback-only reveal endpoint.
2. Install that key at the receiver, activate the merchant, and PUT enabled configuration version 1.
3. Observe the committed outbox event cross Kafka, persist in Notification's projection, and deliver
   a processed payment result over HTTP with a valid version-1 HMAC signature.
4. Provision/install a second key. Confirm provisioning alone does not change the active key, then
   activate it through configuration version 2 and verify another HTTP delivery with key version 2.
5. Stop and restart only the temporary Notification JVM. Verify the existing projection persists,
   then issue explicit configuration replay without resetting any database or consumer offsets.
6. Suspend the merchant (configuration version 3), publish another isolated payment result, and
   confirm it remains queued without an HTTP call. Reactivate (version 4) and verify delivery with
   the current version-2 key.
7. Close the merchant (version 5), publish another result, and confirm terminal `MERCHANT_CLOSED`
   handling without a fourth HTTP call.

Observed results:

- **3 verified HTTP deliveries**, signed with versions **1, 2, 2**.
- **1 closure-blocked notification**, terminal FAILED with no HTTP delivery.
- **6 outbox events PUBLISHED** (including replay); no publisher dead letters.
- Final projection configuration version **5**.
- Both isolated consumer groups reached **zero lag**.
- Exact provisioned signing-key values were absent from both database data dumps, configuration
  Kafka message values, and Merchant/Notification/receiver logs.

Non-secret resource references for this run:

- Containers: `payflow-cp2-d-postgres-86bfaedd`, `payflow-cp2-d-kafka-86bfaedd`.
- Topics: `cp2-86bfaedd-config`, `cp2-86bfaedd-payments`.
- Private local evidence directory: `/private/tmp/payflow-cp2-d-86bfaedd`.

Temporary application/receiver JVMs/processes were stopped. Only the two newly created demo containers
were then stopped; their databases, containers and volumes were retained. Temporary evidence directories
may be cleared by the operating system, so the durable results are summarized here. Private provisioning
and master-key files are outside the repository and must never be staged or uploaded.

## Security review conclusions and limits

- Configuration APIs return metadata, not signing-key values. The only reveal path creates a fresh
  candidate and is dev-profile gated, explicit opt-in, direct-loopback restricted and no-store.
- Key material is encrypted in the isolated development file adapter, not ordinary database columns
  or Kafka payloads. It is bound to merchant/reference/version; reader and writer capabilities are
  separated. Tamper, missing-key and unsafe-permission cases fail closed.
- The shared destination policy and actual HTTP client's DNS pinning/redirect restrictions remain
  active. Loopback HTTP is an explicit development allowance, not a production setting.
- Newer unresolved/disabled configuration fences older usable configuration. At delivery time the
  resolver checks lifecycle, freshness and exact key availability, then fences its readiness update.
- Secret-manager IAM and authorized management APIs are **not implemented**. A shared local master key
  and filesystem roles do not protect against a compromised same-UID process. Production needs an
  audited secret-manager adapter and deployment-enforced read/write permissions.
- Production also needs authenticated/TLS Kafka and topic ACLs: only Merchant should publish trusted
  configuration, and Notification should consume it. Metadata events are not independently signed.
- Configuration propagation is bounded eventual consistency, not immediate distributed revocation.
  The default freshness limit is five minutes; already selected/in-flight requests cannot be revoked.
  Receivers need timestamp checks, event deduplication and old/new key overlap during rotation.
- Long suspension/unavailability may exhaust notification retries. Terminal redrive, audit/retention
  and orphaned-file cleanup remain operational work; no destructive cleanup was added.

See [WEBHOOKS.md](WEBHOOKS.md) for the full API, secret-provider and reconciliation contract.

## Suggested independently reviewable commit order

The final A/B service and test files share implementation dependencies. Do not stage directory fragments
that leave constructor/import references unresolved. Combining A+B is the simplest buildable boundary.

1. **Shared foundation** — root Maven module entry, `shared/webhook-support` and the merchant event
   contract. Suggested message: `feat(webhooks): add shared configuration and secret-provider contracts`.
2. **Merchant A+B** — Merchant dependencies, webhook persistence/APIs, candidate provisioning,
   transactional outbox, lifecycle integration, Kafka publisher, migrations and their tests/docs.
   Suggested message: `feat(merchant): manage webhook keys and publish versioned configuration`.
3. **Notification C** — projection/consumer/resolver, key-version headers, shared policy wiring,
   static production-adapter removal, migration, regression fixtures, mock receiver and documentation.
   Suggested message: `feat(notification): consume durable merchant webhook configuration`.
4. **Validation D** — this executed validation/security review record.
   Suggested message: `docs(webhooks): record rotation and recovery validation`.

Review and run the corresponding Maven tests against each intended staged snapshot before committing.
No staging, commits, pushes, resets or discarded work were performed by the agent. Existing `.idea`,
`AGENTS.md`, `frontend/` and generated bytecode changes are outside these commit boundaries.
