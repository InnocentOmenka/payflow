package com.innocodes.payflow.merchant.dto;

import com.innocodes.payflow.merchant.dto.request.RegisterMerchantRequest;
import com.innocodes.payflow.merchant.dto.request.UpdateMerchantProfileRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class MerchantValidationTest {
    private static final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    private static final Validator validator = factory.getValidator();
    @AfterAll static void close() { factory.close(); }

    static Stream<RegisterMerchantRequest> invalidRegistrations() {
        return Stream.of(
                new RegisterMerchantRequest(null, "Name", "a@example.com", null),
                new RegisterMerchantRequest(" ", "Name", "a@example.com", null),
                new RegisterMerchantRequest("INVALID", "Name", "a@example.com", null),
                new RegisterMerchantRequest("MER-" + "A".repeat(100), "Name", "a@example.com", null),
                new RegisterMerchantRequest("MER-1", null, "a@example.com", null),
                new RegisterMerchantRequest("MER-1", "  ", "a@example.com", null),
                new RegisterMerchantRequest("MER-1", "N".repeat(201), "a@example.com", null),
                new RegisterMerchantRequest("MER-1", "Name\0", "a@example.com", null),
                new RegisterMerchantRequest("MER-1", "Name", null, null),
                new RegisterMerchantRequest("MER-1", "Name", "bad", null),
                new RegisterMerchantRequest("MER-1", "Name", "a b@example.com", null),
                new RegisterMerchantRequest("MER-1", "Name", "x".repeat(255) + "@example.com", null),
                new RegisterMerchantRequest("MER-1", "Name", "a@example.com", "abc"),
                new RegisterMerchantRequest("MER-1", "Name", "a@example.com", "123"),
                new RegisterMerchantRequest("MER-1", "Name", "a@example.com", "+" + "1".repeat(16))
        );
    }

    @ParameterizedTest @MethodSource("invalidRegistrations")
    void shouldRejectInvalidRegistration(RegisterMerchantRequest request) {
        assertFalse(validator.validate(request).isEmpty());
    }

    @Test
    void shouldNormalizeRegistrationAndAcceptOptionalPhone() {
        var request = new RegisterMerchantRequest(" mer-1001 ", " Business ", " OWNER@Example.com ", null);
        assertTrue(validator.validate(request).isEmpty());
        assertEquals("MER-1001", request.merchantId());
        assertEquals("Business", request.businessName());
        assertEquals("owner@example.com", request.businessEmail());
    }

    @Test
    void shouldRequireNonNegativeVersionForProfileUpdates() {
        assertFalse(validator.validate(new UpdateMerchantProfileRequest(null, "Name", "a@example.com", null)).isEmpty());
        assertFalse(validator.validate(new UpdateMerchantProfileRequest(-1L, "Name", "a@example.com", null)).isEmpty());
        assertTrue(validator.validate(new UpdateMerchantProfileRequest(0L, "Name", "a@example.com", null)).isEmpty());
    }
}
