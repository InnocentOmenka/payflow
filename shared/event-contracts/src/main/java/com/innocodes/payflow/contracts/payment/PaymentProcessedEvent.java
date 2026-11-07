package com.innocodes.payflow.contracts.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentProcessedEvent(
        UUID eventId,
        UUID paymentId,
        String merchantId,
        String reference,
        BigDecimal amount,
        String currency,
        String status,
        String failureReason,
        Instant processedAt
) {
}