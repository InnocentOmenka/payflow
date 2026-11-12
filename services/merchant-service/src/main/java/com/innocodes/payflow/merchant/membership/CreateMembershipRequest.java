package com.innocodes.payflow.merchant.membership;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.innocodes.payflow.merchant.enums.MerchantMembershipRole;
import jakarta.validation.constraints.*;

public record CreateMembershipRequest(@NotBlank @Size(max = 512) String issuer,
                                      @NotBlank @Size(max = 255) String subject,
                                      @NotNull MerchantMembershipRole role) {
    @JsonAnySetter
    public void unknown(String name, Object value) { throw new IllegalArgumentException("Unknown request field"); }
}
