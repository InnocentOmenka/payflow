package com.innocodes.payflow.merchant.entity;

import com.innocodes.payflow.merchant.enums.MerchantStatus;
import com.innocodes.payflow.merchant.exception.MerchantConflictException;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "merchants", uniqueConstraints = {
        @UniqueConstraint(name = "uk_merchants_merchant_id", columnNames = "merchant_id"),
        @UniqueConstraint(name = "uk_merchants_business_email", columnNames = "business_email")
})
public class Merchant {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false, length = 100)
    private String merchantId;

    @Column(name = "business_name", nullable = false, length = 200)
    private String businessName;

    @Column(name = "business_email", nullable = false, length = 254)
    private String businessEmail;

    @Column(name = "contact_phone", length = 30)
    private String contactPhone;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MerchantStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Merchant() {
        // Required by JPA
    }

    public Merchant(UUID id, String merchantId, String businessName, String businessEmail,
                    String contactPhone, Instant now) {
        this.id = id;
        this.merchantId = merchantId;
        this.businessName = businessName;
        this.businessEmail = businessEmail;
        this.contactPhone = contactPhone;
        this.status = MerchantStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void updateProfile(String businessName, String businessEmail, String contactPhone, Instant now) {
        if (status == MerchantStatus.CLOSED) {
            throw new MerchantConflictException("Closed merchant profiles cannot be updated");
        }
        this.businessName = businessName;
        this.businessEmail = businessEmail;
        this.contactPhone = contactPhone;
        this.updatedAt = now;
    }

    public void activate(Instant now) { transitionTo(MerchantStatus.ACTIVE, now); }
    public void suspend(Instant now) { transitionTo(MerchantStatus.SUSPENDED, now); }
    public void close(Instant now) { transitionTo(MerchantStatus.CLOSED, now); }

    private void transitionTo(MerchantStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new MerchantConflictException("Cannot transition merchant from " + status + " to " + target);
        }
        status = target;
        updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getMerchantId() { return merchantId; }
    public String getBusinessName() { return businessName; }
    public String getBusinessEmail() { return businessEmail; }
    public String getContactPhone() { return contactPhone; }
    public MerchantStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
