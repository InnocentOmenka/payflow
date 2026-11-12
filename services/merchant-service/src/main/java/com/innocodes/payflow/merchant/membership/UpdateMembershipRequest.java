package com.innocodes.payflow.merchant.membership;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.innocodes.payflow.merchant.enums.MerchantMembershipRole;
import jakarta.validation.constraints.*;

public record UpdateMembershipRequest(@NotNull @PositiveOrZero Long version, @NotNull MerchantMembershipRole role) {
    @JsonAnySetter
    public void unknown(String name, Object value) { throw new IllegalArgumentException("Unknown request field"); }
}
