package com.innocodes.payflow.notification.consumer;

import com.innocodes.payflow.contracts.payment.PaymentProcessedEvent;
import com.innocodes.payflow.notification.config.KafkaTopicConfig;
import com.innocodes.payflow.notification.service.NotificationIngestionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PaymentProcessedConsumer {

    private final JsonMapper jsonMapper;
    private final NotificationIngestionService notificationIngestionService;

    public PaymentProcessedConsumer(JsonMapper jsonMapper, NotificationIngestionService notificationIngestionService) {
        this.jsonMapper = jsonMapper;
        this.notificationIngestionService = notificationIngestionService;
    }

    @KafkaListener(
            id = "notification-payment-processed",
            topics = KafkaTopicConfig.PAYMENT_PROCESSED_TOPIC,
            groupId = "notification-service-group",
            autoStartup = "${spring.kafka.listener.auto-startup:true}"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        if (record.value() == null) {
            throw new IllegalArgumentException("Payment processed payload cannot be null");
        }
        PaymentProcessedEvent event;
        try {
            event = jsonMapper.readValue(record.value(), PaymentProcessedEvent.class);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException(
                    "Invalid payment processed JSON at " + record.topic()
                            + "-" + record.partition() + " offset " + record.offset(), exception
            );
        }
        // The service proxy commits the database transaction before returning.
        // Business identity comes from the payload, never the outbox event-id header.
        notificationIngestionService.process(event);
        acknowledgment.acknowledge();
    }
}
