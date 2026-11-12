package com.innocodes.payflow.merchant.membership;

import com.innocodes.payflow.merchant.entity.MerchantMembership;
import com.innocodes.payflow.merchant.enums.MerchantMembershipRole;
import java.time.Instant;
import java.util.UUID;

public record MembershipResponse(UUID id, UUID merchantId, String issuer, String subject,
                                 MerchantMembershipRole role, boolean active, Long version,
                                 Instant createdAt, Instant updatedAt) {
    public static MembershipResponse from(MerchantMembership membership) {
        return new MembershipResponse(membership.getId(), membership.getMerchantId(), membership.getIssuer(),
                membership.getSubject(), membership.getRole(), membership.isActive(), membership.getVersion(),
                membership.getCreatedAt(), membership.getUpdatedAt());
    }
}
