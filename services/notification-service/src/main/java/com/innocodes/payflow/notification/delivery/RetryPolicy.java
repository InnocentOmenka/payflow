package com.innocodes.payflow.notification.delivery;

import com.innocodes.payflow.notification.config.WebhookProperties;
import org.springframework.stereotype.Component;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class RetryPolicy {
    private final WebhookProperties properties;

    public RetryPolicy(WebhookProperties properties) {
        this.properties = properties;
    }

    public long delayMillis(int attempt) {
        return delayMillis(attempt, ThreadLocalRandom.current().nextDouble());
    }

    public long delayMillis(int attempt, double jitter) {
        if (attempt < 1 || jitter < 0 || jitter >= 1) {
            throw new IllegalArgumentException("Invalid retry attempt or jitter");
        }
        long cap = properties.maxBackoff().toMillis();
        long delay = properties.initialBackoff().toMillis();
        for (int i = 1; i < attempt && delay < cap; i++) {
            delay = delay > cap / 2 ? cap : delay * 2;
        }
        return Math.max(1, (long) (Math.min(delay, cap) * (0.5 + jitter * 0.5)));
    }
}
