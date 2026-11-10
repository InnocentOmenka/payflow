package com.innocodes.payflow.notification.support;

import com.innocodes.payflow.notification.config.WebhookProperties;
import com.innocodes.payflow.notification.delivery.MerchantWebhookDestinationResolver;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class StaticTestDestinations {
    @Bean
    MerchantWebhookDestinationResolver testDestinations(WebhookProperties properties) {
        return reference -> {
            var merchant = properties.merchants().get(reference);
            if (merchant == null) throw new IllegalStateException("DESTINATION_NOT_CONFIGURED");
            return new MerchantWebhookDestinationResolver.Destination(
                    merchant.url(), merchant.secret());
        };
    }
}
