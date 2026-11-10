package com.innocodes.payflow.webhook;

import java.util.UUID;

@FunctionalInterface
public interface SecretReader {
    String resolve(String merchantId, UUID reference, long version);
}
