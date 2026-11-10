package com.innocodes.payflow.merchant.enums;

public enum MerchantStatus {
    PENDING, ACTIVE, SUSPENDED, CLOSED;

    public boolean canTransitionTo(MerchantStatus target) {
        return switch (this) {
            case PENDING -> target == ACTIVE || target == CLOSED;
            case ACTIVE -> target == SUSPENDED || target == CLOSED;
            case SUSPENDED -> target == ACTIVE || target == CLOSED;
            case CLOSED -> false;
        };
    }
}
