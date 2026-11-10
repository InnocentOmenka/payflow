package com.innocodes.payflow.notification.delivery;

import java.net.URI;

public interface MerchantWebhookDestinationResolver {
    Destination resolve(String reference);

    record Destination(URI url, String secret) {
        @Override
        public String toString() {
            return "Destination[redacted]";
        }
    }
}
