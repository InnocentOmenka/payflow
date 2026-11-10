package com.innocodes.payflow.notification.delivery;

import com.innocodes.payflow.notification.service.DeliveryStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "payflow.webhook.worker-enabled", havingValue = "true", matchIfMissing = true)
public class DeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);
    private final DeliveryStore store;
    private final WebhookHttpClient client;
    private final MeterRegistry metrics;

    public DeliveryWorker(DeliveryStore store, WebhookHttpClient client, MeterRegistry metrics) {
        this.store = store;
        this.client = client;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${payflow.webhook.poll-interval-ms:1000}")
    public void runOnce() {
        var claim = store.claim(); // Commits before returning; HTTP is outside database transactions.
        if (claim.isEmpty()) {
            return;
        }
        var notification = claim.get();
        var result = client.deliver(notification);
        boolean completed = store.complete(notification, result); // Separate, fenced DB transaction.
        metrics.counter("payflow.webhook.attempts", "outcome",
                !completed ? "stale" : result.delivered() ? "delivered" : "failed").increment();
        metrics.timer("payflow.webhook.duration").record(result.durationMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        log.info("Webhook event={} attempt={} completed={} delivered={} result={}", notification.eventId(),
                notification.attemptCount(), completed, result.delivered(), result.failure());
    }
}
