package com.innocodes.payflow.webhook;

import java.util.UUID;

public record ProvisionedSecret(UUID reference, long version, String secret) {
    @Override
    public String toString() {
        return "ProvisionedSecret[redacted]";
    }
}
