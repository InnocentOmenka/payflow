package com.innocodes.payflow.notification.model;

import java.time.Instant;
import java.util.UUID;

public record Notification(
        UUID eventId, UUID paymentId, String merchantId, String eventType, String paymentStatus,
        String eventPayload, String webhookPayload, String destinationRef, String deliveryState,
        int attemptCount, Instant nextAttemptAt, UUID claimToken, Instant leaseUntil,
        Instant createdAt, Instant updatedAt, Instant deliveredAt, String lastFailure
) {}
