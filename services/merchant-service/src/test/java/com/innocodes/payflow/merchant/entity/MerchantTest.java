package com.innocodes.payflow.merchant.entity;

import com.innocodes.payflow.merchant.enums.MerchantStatus;
import com.innocodes.payflow.merchant.exception.MerchantConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MerchantTest {
    private final Instant created = Instant.parse("2026-11-10T12:00:00Z");
    private final Instant changed = created.plusSeconds(10);

    @ParameterizedTest
    @CsvSource({"PENDING,PENDING,false", "PENDING,ACTIVE,true", "PENDING,SUSPENDED,false", "PENDING,CLOSED,true",
            "ACTIVE,PENDING,false", "ACTIVE,ACTIVE,false", "ACTIVE,SUSPENDED,true", "ACTIVE,CLOSED,true",
            "SUSPENDED,PENDING,false", "SUSPENDED,ACTIVE,true", "SUSPENDED,SUSPENDED,false", "SUSPENDED,CLOSED,true",
            "CLOSED,PENDING,false", "CLOSED,ACTIVE,false", "CLOSED,SUSPENDED,false", "CLOSED,CLOSED,false"})
    void shouldDefineCompleteTransitionMatrix(MerchantStatus source, MerchantStatus target, boolean allowed) {
        assertEquals(allowed, source.canTransitionTo(target));
        if (target == MerchantStatus.PENDING) return; // No public operation returns a merchant to PENDING.
        var merchant = merchant();
        switch (source) {
            case ACTIVE -> merchant.activate(created);
            case SUSPENDED -> { merchant.activate(created); merchant.suspend(created); }
            case CLOSED -> merchant.close(created);
            default -> { }
        }
        Runnable operation = switch (target) {
            case ACTIVE -> () -> merchant.activate(changed);
            case SUSPENDED -> () -> merchant.suspend(changed);
            case CLOSED -> () -> merchant.close(changed);
            default -> throw new AssertionError();
        };
        if (allowed) {
            operation.run();
            assertEquals(target, merchant.getStatus());
            assertEquals(changed, merchant.getUpdatedAt());
        } else {
            assertThrows(MerchantConflictException.class, operation::run);
            assertEquals(source, merchant.getStatus());
            assertEquals(created, merchant.getUpdatedAt());
        }
        assertEquals(created, merchant.getCreatedAt());
    }

    @Test
    void shouldStartPendingWithImmutableIdentityAndCreationTime() {
        var merchant = merchant();
        assertEquals(MerchantStatus.PENDING, merchant.getStatus());
        var id = merchant.getId();
        merchant.updateProfile("Updated", "updated@example.com", null, changed);
        assertEquals(id, merchant.getId());
        assertEquals("MER-1001", merchant.getMerchantId());
        assertEquals(created, merchant.getCreatedAt());
        assertEquals(changed, merchant.getUpdatedAt());
        assertNull(merchant.getContactPhone());
    }

    @Test
    void shouldRejectProfileUpdatesAfterClosure() {
        var merchant = merchant();
        merchant.close(changed);
        assertThrows(MerchantConflictException.class,
                () -> merchant.updateProfile("Changed", "changed@example.com", null, changed.plusSeconds(1)));
        assertEquals("Business", merchant.getBusinessName());
        assertEquals(changed, merchant.getUpdatedAt());
    }

    private Merchant merchant() {
        return new Merchant(UUID.randomUUID(), "MER-1001", "Business", "business@example.com", "+2348012345678", created);
    }
}
