package com.innocodes.payflow.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

@ConfigurationProperties("payflow.webhook")
public record WebhookProperties(
        @DefaultValue("8") int maxAttempts,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration responseTimeout,
        @DefaultValue("10s") Duration requestTimeout,
        @DefaultValue("30s") Duration leaseDuration,
        @DefaultValue("5s") Duration initialBackoff,
        @DefaultValue("1h") Duration maxBackoff,
        @DefaultValue("false") boolean allowLocal,
        Map<String, Merchant> merchants
) {
    public WebhookProperties {
        merchants = merchants == null ? Map.of() : Map.copyOf(merchants);
        if (maxAttempts < 1 || maxAttempts > 100
                || !positive(connectTimeout) || !positive(responseTimeout) || !positive(requestTimeout)
                || !positive(initialBackoff) || !positive(maxBackoff) || !positive(leaseDuration)
                || leaseDuration.compareTo(requestTimeout.plusSeconds(1)) <= 0
                || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("Invalid webhook timing or attempt configuration");
        }
    }

    private static boolean positive(Duration duration) {
        return duration != null && !duration.isNegative() && duration.toMillis() > 0;
    }

    public record Merchant(URI url, String secret) {
        @Override
        public String toString() {
            return "Merchant[redacted]";
        }
    }

    @Override
    public String toString() {
        return "WebhookProperties[merchant configuration redacted]";
    }
}
