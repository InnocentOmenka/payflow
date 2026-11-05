package com.innocodes.payflow.payment.dto.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentInitiatedEvent(
        UUID eventId,
        UUID paymentId,
        String merchantId,
        String reference,
        BigDecimal amount,
        String currency,
        String description,
        Instant occurredAt
) {
}