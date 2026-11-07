package com.innocodes.payflow.processor.entity;

import com.innocodes.payflow.processor.enums.ProcessingStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processed_payments")
public class ProcessedPayment {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "payment_id", nullable = false, unique = true)
    private UUID paymentId;

    @Column(name = "merchant_id", nullable = false, length = 100)
    private String merchantId;

    @Column(nullable = false, length = 150)
    private String reference;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProcessingStatus status;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedPayment() {
    }

    public ProcessedPayment(
            UUID id,
            UUID eventId,
            UUID paymentId,
            String merchantId,
            String reference,
            BigDecimal amount,
            String currency,
            ProcessingStatus status,
            String failureReason,
            Instant processedAt
    ) {
        this.id = id;
        this.eventId = eventId;
        this.paymentId = paymentId;
        this.merchantId = merchantId;
        this.reference = reference;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.failureReason = failureReason;
        this.processedAt = processedAt;
    }

    public UUID getId() {
        return id;
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

    public ProcessingStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}