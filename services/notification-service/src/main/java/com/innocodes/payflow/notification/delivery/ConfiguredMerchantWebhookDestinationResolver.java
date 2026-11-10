package com.innocodes.payflow.notification.delivery;

import com.innocodes.payflow.notification.config.WebhookProperties;
import org.springframework.stereotype.Component;

@Component
public class ConfiguredMerchantWebhookDestinationResolver implements MerchantWebhookDestinationResolver {
    private final WebhookProperties properties;

    public ConfiguredMerchantWebhookDestinationResolver(WebhookProperties properties, DestinationPolicy policy) {
        this.properties = properties;
        properties.merchants().values().forEach(merchant -> {
            policy.validateUri(merchant.url());
            if (merchant.secret() == null || merchant.secret().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
                throw new IllegalArgumentException("Webhook signing secrets must contain at least 32 UTF-8 bytes");
            }
        });
    }

    @Override
    public Destination resolve(String reference) {
        var merchant = properties.merchants().get(reference);
        if (merchant == null) {
            throw new IllegalStateException("DESTINATION_NOT_CONFIGURED");
        }
        return new Destination(merchant.url(), merchant.secret());
    }
}
