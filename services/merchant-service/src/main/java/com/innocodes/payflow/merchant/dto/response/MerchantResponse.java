package com.innocodes.payflow.merchant.dto.response;

import com.innocodes.payflow.merchant.entity.Merchant;
import com.innocodes.payflow.merchant.enums.MerchantStatus;
import java.time.Instant;
import java.util.UUID;

public record MerchantResponse(UUID id, String merchantId, String businessName, String businessEmail,
                               String contactPhone, MerchantStatus status, Instant createdAt,
                               Instant updatedAt, Long version) {
    public static MerchantResponse from(Merchant merchant) {
        return new MerchantResponse(merchant.getId(), merchant.getMerchantId(), merchant.getBusinessName(),
                merchant.getBusinessEmail(), merchant.getContactPhone(), merchant.getStatus(),
                merchant.getCreatedAt(), merchant.getUpdatedAt(), merchant.getVersion());
    }
}
