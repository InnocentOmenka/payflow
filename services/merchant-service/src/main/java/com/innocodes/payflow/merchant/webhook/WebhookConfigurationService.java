package com.innocodes.payflow.merchant.webhook;

import com.innocodes.payflow.merchant.enums.MerchantStatus;
import com.innocodes.payflow.merchant.exception.MerchantConflictException;
import com.innocodes.payflow.merchant.exception.MerchantNotFoundException;
import com.innocodes.payflow.merchant.repository.MerchantRepository;
import com.innocodes.payflow.webhook.OutboundDestinationPolicy;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Service
@Validated
public class WebhookConfigurationService {
    private final MerchantRepository merchants;
    private final JdbcTemplate jdbc;
    private final OutboundDestinationPolicy policy;
    private final com.innocodes.payflow.webhook.SecretReader secrets;
    private final MerchantConfigurationOutbox outbox;

    public WebhookConfigurationService(
            MerchantRepository merchants,
            JdbcTemplate jdbc,
            @Value("${payflow.webhook.allow-local:false}") boolean local,
            Environment environment,
            com.innocodes.payflow.webhook.SecretReader secrets,
            MerchantConfigurationOutbox outbox) {
        if (local && !environment.acceptsProfiles(Profiles.of("dev"))) {
            throw new IllegalArgumentException(
                    "Local webhook destinations require the dev profile");
        }
        this.secrets = secrets;
        this.outbox = outbox;
        this.merchants = merchants;
        this.jdbc = jdbc;
        this.policy = new OutboundDestinationPolicy(local);
    }

    @Transactional(readOnly = true)
    public WebhookConfiguration get(UUID id) {
        merchants.findById(id).orElseThrow(MerchantNotFoundException::new);
        return find(id).orElseThrow(
                        () ->
                                new org.springframework.web.server.ResponseStatusException(
                                        org.springframework.http.HttpStatus.NOT_FOUND,
                                        "Webhook configuration not found"));
    }

    @Transactional
    public WebhookConfiguration update(
            @NotNull UUID id, @NotNull @Valid UpdateWebhookRequest request) {
        // Actual HTTP delivery revalidates and pins DNS, regardless of this registration-time
        // check.
        policy.validateDestination(URI.create(request.destinationUrl()));
        var merchant = merchants.findByIdForUpdate(id).orElseThrow(MerchantNotFoundException::new);
        var existing = find(id);
        long version = existing.map(WebhookConfiguration::version).orElse(0L);
        if (request.version() != version)
            throw new MerchantConflictException("Webhook configuration version is stale");
        if (merchant.getStatus() == MerchantStatus.CLOSED)
            throw new MerchantConflictException("Closed merchants cannot configure webhooks");
        if (merchant.getStatus() == MerchantStatus.SUSPENDED
                && (request.enabled()
                        || existing.isEmpty()
                        || !existing.get().destinationUrl().equals(request.destinationUrl())
                        || !Objects.equals(existing.get().secretRef(), request.secretRef())
                        || !Objects.equals(
                                existing.get().secretVersion(), request.secretVersion()))) {
            throw new MerchantConflictException(
                    "Suspended merchants may only disable their existing webhook");
        }
        if (request.enabled() && merchant.getStatus() != MerchantStatus.ACTIVE)
            throw new MerchantConflictException("Only active merchants can enable webhooks");
        if ((request.secretRef() == null) != (request.secretVersion() == null)
                || (request.enabled() && request.secretRef() == null)) {
            throw new MerchantConflictException(
                    "Enabled webhooks require a provisioned signing key");
        }
        validateKey(id, request, existing.orElse(null));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.update(
                """
                INSERT INTO merchant_webhooks(merchant_uuid,config_version,destination_url,enabled,secret_ref,secret_version,updated_at,last_snapshot_at)
                VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(merchant_uuid) DO UPDATE SET config_version=excluded.config_version,
                destination_url=excluded.destination_url,enabled=excluded.enabled,secret_ref=excluded.secret_ref,
                secret_version=excluded.secret_version,updated_at=excluded.updated_at,last_snapshot_at=excluded.last_snapshot_at
                """,
                id,
                version + 1,
                request.destinationUrl(),
                request.enabled(),
                request.secretRef(),
                request.secretVersion(),
                java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now));
        changed(merchant, find(id).orElseThrow());
        return find(id).orElseThrow();
    }

    private void validateKey(UUID id, UpdateWebhookRequest request, WebhookConfiguration existing) {
        if (existing != null
                && existing.secretVersion() != null
                && (request.secretVersion() == null
                        || request.secretVersion() < existing.secretVersion()
                        || (request.secretVersion().equals(existing.secretVersion())
                                && !Objects.equals(request.secretRef(), existing.secretRef()))))
            throw new MerchantConflictException("Signing key versions cannot be rolled back");
        if (request.secretRef() != null) {
            Integer count =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM merchant_webhook_secrets WHERE merchant_uuid=?"
                                + " AND reference=? AND version=?",
                            Integer.class,
                            id,
                            request.secretRef(),
                            request.secretVersion());
            if (count != 1)
                throw new MerchantConflictException("Signing key does not belong to this merchant");
            String merchant = merchants.findById(id).orElseThrow().getMerchantId();
            secrets.resolve(merchant, request.secretRef(), request.secretVersion());
        }
    }

    private void changed(
            com.innocodes.payflow.merchant.entity.Merchant merchant, WebhookConfiguration config) {
        outbox.append(merchant, config);
    }

    @Transactional
    public void merchantStatusChanged(com.innocodes.payflow.merchant.entity.Merchant merchant) {
        var config = find(merchant.getId());
        if (config.isPresent()) {
            jdbc.update(
                    "UPDATE merchant_webhooks SET"
                        + " config_version=config_version+1,updated_at=clock_timestamp(),last_snapshot_at=clock_timestamp()"
                        + " WHERE merchant_uuid=?",
                    merchant.getId());
            changed(merchant, find(merchant.getId()).orElseThrow());
        }
    }

    @Transactional
    public void replay(UUID id) {
        var merchant = merchants.findByIdForUpdate(id).orElseThrow(MerchantNotFoundException::new);
        changed(merchant, get(id));
        jdbc.update(
                "UPDATE merchant_webhooks SET last_snapshot_at=clock_timestamp() WHERE"
                    + " merchant_uuid=?",
                id);
    }

    @Transactional
    public void snapshotDue() {
        var ids =
                jdbc.query(
                        """
                        SELECT m.id FROM merchants m JOIN merchant_webhooks w ON w.merchant_uuid=m.id
                        WHERE w.last_snapshot_at < clock_timestamp()-interval '60 seconds'
                          AND NOT EXISTS(SELECT 1 FROM merchant_config_outbox o WHERE o.merchant_id=m.merchant_id
                            AND o.config_version=w.config_version AND o.state IN ('PENDING','IN_FLIGHT'))
                        ORDER BY w.last_snapshot_at LIMIT 100 FOR UPDATE OF m SKIP LOCKED
                        """,
                        (rs, row) -> rs.getObject(1, UUID.class));
        ids.forEach(this::replay);
    }

    public java.util.Optional<WebhookConfiguration> find(UUID id) {
        return jdbc
                .query(
                        "SELECT * FROM merchant_webhooks WHERE merchant_uuid=?",
                        (rs, row) ->
                                new WebhookConfiguration(
                                        rs.getObject("merchant_uuid", UUID.class),
                                        rs.getLong("config_version"),
                                        rs.getString("destination_url"),
                                        rs.getBoolean("enabled"),
                                        rs.getObject("secret_ref", UUID.class),
                                        (Long) rs.getObject("secret_version"),
                                        rs.getTimestamp("updated_at").toInstant()),
                        id)
                .stream()
                .findFirst();
    }
}
