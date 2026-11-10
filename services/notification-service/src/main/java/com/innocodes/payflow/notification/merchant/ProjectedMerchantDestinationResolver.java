package com.innocodes.payflow.notification.merchant;

import com.innocodes.payflow.notification.config.WebhookProperties;
import com.innocodes.payflow.notification.delivery.MerchantWebhookDestinationResolver;
import com.innocodes.payflow.webhook.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Component
@Profile("!test-static-webhooks")
public class ProjectedMerchantDestinationResolver implements MerchantWebhookDestinationResolver {
    private final JdbcTemplate jdbc;
    private final SecretReader secrets;
    private final Duration maxAge;

    public ProjectedMerchantDestinationResolver(
            JdbcTemplate jdbc,
            SecretReader secrets,
            WebhookProperties properties,
            @Value("${payflow.webhook.configuration-max-age:5m}") Duration maxAge) {
        if (!properties.merchants().isEmpty())
            throw new IllegalArgumentException(
                    "Static merchant destinations are no longer supported");
        if (maxAge.compareTo(Duration.ofSeconds(120)) < 0
                || maxAge.compareTo(Duration.ofHours(1)) > 0)
            throw new IllegalArgumentException(
                    "Configuration max age must be between 2 minutes and 1 hour");
        this.jdbc = jdbc;
        this.secrets = secrets;
        this.maxAge = maxAge;
    }

    @Override
    public Destination resolve(String merchant) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM merchant_webhook_projection WHERE merchant_id=?", merchant);
        if (rows.isEmpty())
            throw new DestinationUnavailableException("CONFIGURATION_MISSING", true);
        var c = rows.getFirst();
        if ("CLOSED".equals(c.get("merchant_status")))
            throw new DestinationUnavailableException("MERCHANT_CLOSED", false);
        if (!Boolean.TRUE.equals(c.get("enabled")) || !"ACTIVE".equals(c.get("merchant_status")))
            throw new DestinationUnavailableException("WEBHOOK_DISABLED", true);
        if (((java.sql.Timestamp) c.get("confirmed_at"))
                .toInstant()
                .isBefore(Instant.now().minus(maxAge)))
            throw new DestinationUnavailableException("CONFIGURATION_STALE", true);
        var reference = (UUID) c.get("secret_ref");
        long keyVersion = ((Number) c.get("secret_version")).longValue();
        String key;
        try {
            key = secrets.resolve(merchant, reference, keyVersion);
            if (key == null || key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32)
                throw new SecretUnavailableException();
        } catch (SecretUnavailableException unavailable) {
            jdbc.update(
                    "UPDATE merchant_webhook_projection SET secret_ready=false WHERE merchant_id=?"
                        + " AND config_version=?",
                    merchant,
                    c.get("config_version"));
            throw new DestinationUnavailableException("SIGNING_KEY_UNAVAILABLE", true);
        }
        int updated =
                jdbc.update(
                        """
                        UPDATE merchant_webhook_projection SET secret_ready=true WHERE merchant_id=? AND config_version=?
                          AND enabled AND merchant_status='ACTIVE' AND confirmed_at>clock_timestamp()-(? * interval '1 millisecond')
                        """,
                        merchant,
                        c.get("config_version"),
                        maxAge.toMillis());
        if (updated != 1) throw new DestinationUnavailableException("CONFIGURATION_CHANGED", true);
        return new Destination(
                URI.create((String) c.get("destination_url")), key, reference, keyVersion);
    }
}
