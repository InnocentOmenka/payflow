package com.innocodes.payflow.merchant.access;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MerchantAccessRequest(@NotBlank @Size(max = 512) String issuer,
                                   @NotBlank @Size(max = 255) String subject) {
    @JsonAnySetter
    public void unknown(String name, Object value) { throw new IllegalArgumentException("Unknown request field"); }
}
