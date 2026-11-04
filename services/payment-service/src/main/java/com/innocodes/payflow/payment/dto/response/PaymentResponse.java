
package com.innocodes.payflow.payment.dto.response;

import com.innocodes.payflow.payment.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(
        String paymentId,
        String merchantId,
        String reference,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        Instant createdAt
) {
}
