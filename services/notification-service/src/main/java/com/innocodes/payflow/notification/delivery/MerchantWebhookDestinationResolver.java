package com.innocodes.payflow.notification.delivery;

import java.net.URI;

public interface MerchantWebhookDestinationResolver {
    Destination resolve(String reference);

    record Destination(URI url, String secret, java.util.UUID secretReference, Long secretVersion) {
        public Destination(URI url,String secret) {this(url,secret,null,null);}
        @Override
        public String toString() {
            return "Destination[redacted]";
        }
    }
}
