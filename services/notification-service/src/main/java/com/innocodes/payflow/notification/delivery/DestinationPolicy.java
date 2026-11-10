package com.innocodes.payflow.notification.delivery;

import com.innocodes.payflow.notification.config.WebhookProperties;
import com.innocodes.payflow.webhook.OutboundDestinationPolicy;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class DestinationPolicy extends OutboundDestinationPolicy {
    public DestinationPolicy(WebhookProperties properties,Environment environment) {
        super(properties.allowLocal());
        if(properties.allowLocal() && !environment.acceptsProfiles(Profiles.of("dev")))
            throw new IllegalArgumentException("Local webhook destinations require the dev profile");
    }
}
