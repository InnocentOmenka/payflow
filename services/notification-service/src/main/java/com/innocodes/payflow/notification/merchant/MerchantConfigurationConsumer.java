package com.innocodes.payflow.notification.merchant;

import com.innocodes.payflow.contracts.merchant.MerchantWebhookConfigurationEvent;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class MerchantConfigurationConsumer {
    private final JsonMapper mapper;
    private final MerchantProjectionService projection;

    public MerchantConfigurationConsumer(JsonMapper mapper, MerchantProjectionService projection) {
        this.mapper = mapper;
        this.projection = projection;
    }

    @KafkaListener(
            id = "notification-merchant-config",
            topics = "${payflow.kafka.merchant-config-topic:merchant-webhook-config}",
            groupId = "${payflow.kafka.merchant-config-group:notification-merchant-config-group}",
            containerFactory = "merchantConfigurationListenerFactory",
            autoStartup = "${spring.kafka.listener.auto-startup:true}",
            properties = "allow.auto.create.topics=false")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        if (record.value() == null)
            throw new IllegalArgumentException(
                    "Configuration tombstones are not supported; publish an explicit disabled"
                        + " configuration");
        MerchantWebhookConfigurationEvent event;
        try {
            event = mapper.readValue(record.value(), MerchantWebhookConfigurationEvent.class);
        } catch (JacksonException invalid) {
            throw new IllegalArgumentException("Invalid merchant configuration JSON", invalid);
        }
        if (event == null || !java.util.Objects.equals(record.key(), event.merchantId()))
            throw new IllegalArgumentException(
                    "Configuration partition key must match the merchant identity");
        projection.apply(event);
        acknowledgment.acknowledge();
    }
}
