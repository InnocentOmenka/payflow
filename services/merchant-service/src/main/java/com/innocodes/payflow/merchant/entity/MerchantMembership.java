package com.innocodes.payflow.merchant.entity;

import com.innocodes.payflow.merchant.enums.MerchantMembershipRole;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** Local human membership. Identity and merchant assignment cannot be changed through this API. */
@Entity
@Table(name = "merchant_memberships", uniqueConstraints = {
        @UniqueConstraint(name = "uk_memberships_principal", columnNames = {"issuer", "subject"})
})
public class MerchantMembership {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "merchant_uuid", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(nullable = false, updatable = false, length = 512)
    private String issuer;

    @Column(nullable = false, updatable = false, length = 255)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MerchantMembershipRole role;

    @Column(nullable = false)
    private boolean active;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "granted_by_issuer", nullable = false, updatable = false, length = 512)
    private String grantedByIssuer;

    @Column(name = "granted_by_subject", nullable = false, updatable = false, length = 255)
    private String grantedBySubject;

    @Column(name = "changed_by_issuer", nullable = false, length = 512)
    private String changedByIssuer;

    @Column(name = "changed_by_subject", nullable = false, length = 255)
    private String changedBySubject;

    protected MerchantMembership() {
        // Required by JPA.
    }

    public MerchantMembership(UUID id, UUID merchantId, String issuer, String subject,
                              MerchantMembershipRole role, String actorIssuer, String actorSubject, Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId");
        this.issuer = identity(issuer, 512);
        this.subject = identity(subject, 255);
        this.role = Objects.requireNonNull(role, "role");
        this.grantedByIssuer = identity(actorIssuer, 512);
        this.grantedBySubject = identity(actorSubject, 255);
        this.changedByIssuer = grantedByIssuer;
        this.changedBySubject = grantedBySubject;
        this.createdAt = Objects.requireNonNull(now, "now").truncatedTo(ChronoUnit.MICROS);
        this.updatedAt = createdAt;
        this.active = true;
    }

    public void changeRole(MerchantMembershipRole role, String actorIssuer, String actorSubject, Instant now) {
        Objects.requireNonNull(role, "role");
        recordChange(actorIssuer, actorSubject, now);
        this.role = role;
    }

    public void activate(String actorIssuer, String actorSubject, Instant now) {
        recordChange(actorIssuer, actorSubject, now);
        active = true;
    }

    public void revoke(String actorIssuer, String actorSubject, Instant now) {
        recordChange(actorIssuer, actorSubject, now);
        active = false;
    }

    private void recordChange(String actorIssuer, String actorSubject, Instant now) {
        String issuer = identity(actorIssuer, 512);
        String subject = identity(actorSubject, 255);
        Instant timestamp = Objects.requireNonNull(now, "now").truncatedTo(ChronoUnit.MICROS);
        if (timestamp.isBefore(updatedAt)) {
            throw new IllegalArgumentException("Membership timestamps must be monotonic");
        }
        changedByIssuer = issuer;
        changedBySubject = subject;
        updatedAt = timestamp;
    }

    private static String identity(String value, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException("Invalid principal identity");
        }
        return value; // Exact opaque identity: never trim, lowercase or infer from email.
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public String getIssuer() { return issuer; }
    public String getSubject() { return subject; }
    public MerchantMembershipRole getRole() { return role; }
    public boolean isActive() { return active; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getGrantedByIssuer() { return grantedByIssuer; }
    public String getGrantedBySubject() { return grantedBySubject; }
    public String getChangedByIssuer() { return changedByIssuer; }
    public String getChangedBySubject() { return changedBySubject; }
}
