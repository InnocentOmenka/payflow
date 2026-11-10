package com.innocodes.payflow.merchant.webhook;

import java.time.Instant;
import java.util.UUID;

public record WebhookConfiguration(
        UUID merchantUuid,
        long version,
        String destinationUrl,
        boolean enabled,
        UUID secretRef,
        Long secretVersion,
        Instant updatedAt) {}
