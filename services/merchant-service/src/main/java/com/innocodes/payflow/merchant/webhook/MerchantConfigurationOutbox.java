package com.innocodes.payflow.merchant.webhook;

import com.innocodes.payflow.contracts.merchant.MerchantWebhookConfigurationEvent;
import com.innocodes.payflow.merchant.entity.Merchant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class MerchantConfigurationOutbox {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    public MerchantConfigurationOutbox(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void append(Merchant merchant, WebhookConfiguration configuration) {
        var event =
                new MerchantWebhookConfigurationEvent(
                        UUID.randomUUID(),
                        merchant.getMerchantId(),
                        configuration.version(),
                        configuration.destinationUrl(),
                        configuration.enabled(),
                        merchant.getStatus().name(),
                        configuration.secretRef(),
                        configuration.secretVersion(),
                        Instant.now().truncatedTo(ChronoUnit.MICROS));
        jdbc.update(
                "INSERT INTO merchant_config_outbox(event_id,merchant_id,config_version,payload)"
                    + " VALUES(?,?,?,?)",
                event.eventId(),
                event.merchantId(),
                event.configurationVersion(),
                mapper.writeValueAsString(event));
    }
}
