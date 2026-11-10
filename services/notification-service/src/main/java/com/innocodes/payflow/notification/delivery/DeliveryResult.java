package com.innocodes.payflow.notification.delivery;

public record DeliveryResult(Integer status, long durationMillis, String failure, boolean retryable) {
    public boolean delivered() {
        return status != null && status >= 200 && status < 300;
    }
}
