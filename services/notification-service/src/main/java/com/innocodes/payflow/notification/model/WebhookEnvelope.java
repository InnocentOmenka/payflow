package com.innocodes.payflow.notification.model;

import com.innocodes.payflow.contracts.payment.PaymentProcessedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WebhookEnvelope(
        int version, String type, UUID eventId, UUID paymentId, String merchantId,
        String reference, String status, BigDecimal amount, String currency, Instant timestamp
) {
    public static WebhookEnvelope from(PaymentProcessedEvent event) {
        return new WebhookEnvelope(1, "PaymentProcessed", event.eventId(), event.paymentId(),
                event.merchantId(), event.reference(), event.status(), event.amount(), event.currency(), event.processedAt());
    }
}
