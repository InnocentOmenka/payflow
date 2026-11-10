package com.innocodes.payflow.ledger.entity;

import com.innocodes.payflow.ledger.enums.JournalStatus;
import com.innocodes.payflow.ledger.enums.JournalType;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "journals",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_journal_payment_type",
                        columnNames = {"payment_id", "journal_type"}
                )
        }
)
public class Journal {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "source_event_id", nullable = false, updatable = false)
    private UUID sourceEventId;

    @Column(name = "merchant_id", nullable = false, updatable = false, length = 100)
    private String merchantId;

    @Column(name = "transaction_reference", nullable = false, updatable = false)
    private String transactionReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "journal_type", nullable = false, updatable = false, length = 30)
    private JournalType journalType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false, length = 20)
    private JournalStatus status;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "description", length = 500, updatable = false)
    private String description;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private Instant postedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Journal() {
        // Required by JPA
    }

    public Journal(
            UUID id,
            UUID paymentId,
            UUID sourceEventId,
            String merchantId,
            String transactionReference,
            JournalType journalType,
            JournalStatus status,
            String currency,
            String description,
            Instant postedAt,
            Instant createdAt
    ) {
        this.id = id;
        this.paymentId = paymentId;
        this.sourceEventId = sourceEventId;
        this.merchantId = merchantId;
        this.transactionReference = transactionReference;
        this.journalType = journalType;
        this.status = status;
        this.currency = currency;
        this.description = description;
        this.postedAt = postedAt;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getSourceEventId() {
        return sourceEventId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getTransactionReference() {
        return transactionReference;
    }

    public JournalType getJournalType() {
        return journalType;
    }

    public JournalStatus getStatus() {
        return status;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDescription() {
        return description;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}