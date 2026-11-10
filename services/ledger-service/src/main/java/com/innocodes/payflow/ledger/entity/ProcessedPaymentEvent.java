package com.innocodes.payflow.ledger.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "processed_payment_events",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_processed_payment_events_payment_id",
                columnNames = "payment_id"
        )
)
public class ProcessedPaymentEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "merchant_id", nullable = false, updatable = false, length = 100)
    private String merchantId;

    @Column(name = "reference", nullable = false, updatable = false, length = 255)
    private String reference;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "status", nullable = false, updatable = false, length = 20)
    private String status;

    @Column(name = "failure_reason", nullable = true, updatable = false, length = 500)
    private String failureReason;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    protected ProcessedPaymentEvent() {
        // Required by JPA
    }

    public ProcessedPaymentEvent(
            UUID eventId,
            UUID paymentId,
            String merchantId,
            String reference,
            BigDecimal amount,
            String currency,
            String status,
            String failureReason,
            Instant processedAt,
            Instant recordedAt
    ) {
        this.eventId = eventId;
        this.paymentId = paymentId;
        this.merchantId = merchantId;
        this.reference = reference;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.failureReason = failureReason;
        this.processedAt = processedAt;
        this.recordedAt = recordedAt;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getReference() {
        return reference;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
