package com.innocodes.payflow.merchant.webhook;

import jakarta.validation.constraints.*;

import java.util.UUID;

public record UpdateWebhookRequest(
        @NotNull @PositiveOrZero Long version,
        @NotBlank @Size(max = 2048) String destinationUrl,
        @NotNull Boolean enabled,
        UUID secretRef,
        @Positive Long secretVersion) {}
