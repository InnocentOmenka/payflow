package com.innocodes.payflow.webhook;

public class SecretUnavailableException extends RuntimeException {
    public SecretUnavailableException() {
        super("Signing secret is unavailable");
    }
}
