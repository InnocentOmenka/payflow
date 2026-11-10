package com.innocodes.payflow.contracts.merchant;

import java.time.Instant;
import java.util.UUID;

public record MerchantWebhookConfigurationEvent(
        UUID eventId,
        String merchantId,
        long configurationVersion,
        String destinationUrl,
        boolean enabled,
        String merchantStatus,
        UUID secretReference,
        Long secretVersion,
        Instant occurredAt) {}
