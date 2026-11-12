package com.innocodes.payflow.merchant.membership;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;

public record RevokeMembershipRequest(@NotNull @PositiveOrZero Long version) {
    @JsonAnySetter
    public void unknown(String name, Object value) { throw new IllegalArgumentException("Unknown request field"); }
}
