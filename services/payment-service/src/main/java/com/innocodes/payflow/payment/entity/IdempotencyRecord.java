package com.innocodes.payflow.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, length = 100)
    private String merchantId;

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IdempotencyRecord() {
        // Required by JPA
    }

    public IdempotencyRecord(
            UUID id,
            String merchantId,
            String idempotencyKey,
            String requestHash,
            UUID paymentId,
            Instant createdAt) {

        this.id = id;
        this.merchantId = merchantId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.paymentId = paymentId;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}