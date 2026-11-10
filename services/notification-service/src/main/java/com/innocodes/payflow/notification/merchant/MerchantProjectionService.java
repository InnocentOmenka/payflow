package com.innocodes.payflow.notification.merchant;

import com.innocodes.payflow.contracts.merchant.MerchantWebhookConfigurationEvent;
import com.innocodes.payflow.notification.delivery.DestinationPolicy;
import com.innocodes.payflow.notification.exception.NotificationConflictException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

@Service
public class MerchantProjectionService {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final DestinationPolicy policy;

    public MerchantProjectionService(
            JdbcTemplate jdbc, JsonMapper mapper, DestinationPolicy policy) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.policy = policy;
    }

    @Transactional
    public void apply(MerchantWebhookConfigurationEvent e) {
        if (e == null
                || e.destinationUrl() == null
                || e.destinationUrl().isBlank()
                || e.eventId() == null
                || e.merchantId() == null
                || e.merchantId().length() > 100
                || !e.merchantId().matches("MER-[A-Z0-9]+(-[A-Z0-9]+)*")
                || e.configurationVersion() < 1
                || e.occurredAt() == null
                || e.occurredAt().isAfter(Instant.now().plusSeconds(60))
                || !Set.of("PENDING", "ACTIVE", "SUSPENDED", "CLOSED")
                        .contains(e.merchantStatus() == null ? "" : e.merchantStatus())
                || (e.secretReference() == null) != (e.secretVersion() == null)
                || (e.secretVersion() != null && e.secretVersion() < 1)
                || (e.enabled() && e.secretReference() == null))
            throw new IllegalArgumentException("Invalid merchant configuration event");
        policy.validateUri(URI.create(e.destinationUrl()));
        String payload = mapper.writeValueAsString(e);
        // Serialize even first-time inserts with a transaction-scoped database lock, never an
        // in-memory lock.
        jdbc.execute(
                (java.sql.Connection c) -> {
                    var statement =
                            c.prepareStatement(
                                    "SELECT pg_advisory_xact_lock(hashtextextended(?,0))");
                    statement.setString(1, e.merchantId());
                    return statement;
                },
                (org.springframework.jdbc.core.PreparedStatementCallback<Boolean>)
                        java.sql.PreparedStatement::execute);
        var receipts =
                jdbc.queryForList(
                        "SELECT payload FROM merchant_configuration_receipts WHERE event_id=?",
                        String.class,
                        e.eventId());
        if (!receipts.isEmpty()) {
            if (!receipts.getFirst().equals(payload))
                throw new NotificationConflictException("Conflicting configuration event identity");
            return;
        }
        // Event ID and timestamp are transport identity/freshness, not configuration content.
        var canonical =
                mapper.createObjectNode()
                        .put("destinationUrl", e.destinationUrl())
                        .put("enabled", e.enabled())
                        .put("merchantStatus", e.merchantStatus())
                        .put(
                                "secretReference",
                                e.secretReference() == null
                                        ? null
                                        : e.secretReference().toString());
        if (e.secretVersion() == null) canonical.putNull("secretVersion");
        else canonical.put("secretVersion", e.secretVersion());
        String business = mapper.writeValueAsString(canonical);
        var current =
                jdbc.queryForList(
                        "SELECT config_version,canonical_payload FROM merchant_webhook_projection"
                            + " WHERE merchant_id=?",
                        e.merchantId());
        if (!current.isEmpty()
                && ((Number) current.getFirst().get("config_version")).longValue()
                        == e.configurationVersion()
                && !business.equals(current.getFirst().get("canonical_payload")))
            throw new NotificationConflictException("Conflicting merchant configuration version");
        jdbc.update(
                "INSERT INTO"
                    + " merchant_configuration_receipts(event_id,merchant_id,config_version,payload)"
                    + " VALUES(?,?,?,?)",
                e.eventId(),
                e.merchantId(),
                e.configurationVersion(),
                payload);
        if (!current.isEmpty()
                && ((Number) current.getFirst().get("config_version")).longValue()
                        > e.configurationVersion()) return;
        jdbc.update(
                """
                INSERT INTO merchant_webhook_projection(merchant_id,config_version,destination_url,enabled,merchant_status,
                  secret_ref,secret_version,canonical_payload,confirmed_at)
                VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(merchant_id) DO UPDATE SET
                  config_version=excluded.config_version,destination_url=excluded.destination_url,enabled=excluded.enabled,
                  merchant_status=excluded.merchant_status,secret_ref=excluded.secret_ref,secret_version=excluded.secret_version,
                  canonical_payload=excluded.canonical_payload,
                  secret_ready=CASE WHEN merchant_webhook_projection.config_version=excluded.config_version THEN merchant_webhook_projection.secret_ready ELSE false END,
                  confirmed_at=CASE WHEN merchant_webhook_projection.config_version=excluded.config_version
                    THEN greatest(merchant_webhook_projection.confirmed_at,excluded.confirmed_at) ELSE excluded.confirmed_at END,
                  updated_at=clock_timestamp()
                """,
                e.merchantId(),
                e.configurationVersion(),
                e.destinationUrl(),
                e.enabled(),
                e.merchantStatus(),
                e.secretReference(),
                e.secretVersion(),
                business,
                Timestamp.from(e.occurredAt().truncatedTo(ChronoUnit.MICROS)));
    }
}
