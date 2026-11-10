package com.innocodes.payflow.notification.merchant;

public class DestinationUnavailableException extends IllegalStateException {
    private final boolean retryable;

    public DestinationUnavailableException(String reason, boolean retryable) {
        super(reason);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
