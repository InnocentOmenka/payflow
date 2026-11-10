package com.innocodes.payflow.ledger.consumer;

import com.innocodes.payflow.contracts.payment.PaymentProcessedEvent;
import com.innocodes.payflow.ledger.config.KafkaTopicConfig;
import com.innocodes.payflow.ledger.service.PaymentProcessedService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PaymentProcessedConsumer {

    private final JsonMapper jsonMapper;
    private final PaymentProcessedService paymentProcessedService;

    public PaymentProcessedConsumer(JsonMapper jsonMapper, PaymentProcessedService paymentProcessedService) {
        this.jsonMapper = jsonMapper;
        this.paymentProcessedService = paymentProcessedService;
    }

    @KafkaListener(
            id = "ledger-payment-processed",
            topics = KafkaTopicConfig.PAYMENT_PROCESSED_TOPIC,
            groupId = "ledger-service-group",
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
        paymentProcessedService.process(event);
        acknowledgment.acknowledge();
    }
}
