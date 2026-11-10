package com.innocodes.payflow.webhook;

@FunctionalInterface
public interface SecretWriter {
    ProvisionedSecret provision(String merchantId, long version);
}
