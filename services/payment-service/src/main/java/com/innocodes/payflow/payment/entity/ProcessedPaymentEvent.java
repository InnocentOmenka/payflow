package com.innocodes.payflow.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processed_payment_events")
public class ProcessedPaymentEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    protected ProcessedPaymentEvent() {
    }

    public ProcessedPaymentEvent(UUID eventId, UUID paymentId) {
        this.eventId = eventId;
        this.paymentId = paymentId;
        this.processedAt = Instant.now();
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}