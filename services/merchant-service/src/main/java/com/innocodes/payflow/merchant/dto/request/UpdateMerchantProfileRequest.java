package com.innocodes.payflow.merchant.dto.request;

import jakarta.validation.constraints.*;
import java.util.Locale;

public record UpdateMerchantProfileRequest(
        @NotNull @PositiveOrZero Long version,
        @NotBlank @Size(max = 200) @Pattern(regexp = "[^\\p{Cntrl}]*", message = "must not contain control characters") String businessName,
        @NotBlank @Email @Size(max = 254) @Pattern(regexp = "[^\\s\\p{Cntrl}]*", message = "must not contain whitespace or control characters") String businessEmail,
        @Pattern(regexp = "\\+?[0-9]{7,15}", message = "must contain 7 to 15 digits with an optional leading +") String contactPhone
) {
    public UpdateMerchantProfileRequest {
        businessName = businessName == null ? null : businessName.strip();
        businessEmail = businessEmail == null ? null : businessEmail.strip().toLowerCase(Locale.ROOT);
        contactPhone = contactPhone == null ? null : contactPhone.strip();
    }
}
