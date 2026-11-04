package com.innocodes.payflow.payment.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record CreatePaymentRequest(

        @NotBlank(message = "Merchant ID is required")
        String merchantId,

        @NotBlank(message = "Payment reference is required")
        String reference,

        @NotNull(message = "Payment amount is required")
        @DecimalMin(value = "0.01", message = "Payment amount must be greater than zero")
        BigDecimal amount,

        @NotBlank(message = "Currency is required")
        @Pattern(regexp = "NGN", message = "Only NGN is currently supported")
        String currency,

        String description

) {
}
