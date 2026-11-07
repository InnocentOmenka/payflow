package com.innocodes.payflow.processor.consumer;

import com.innocodes.payflow.contracts.payment.PaymentInitiatedEvent;
import com.innocodes.payflow.processor.service.PaymentProcessingService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PaymentInitiatedConsumer {

    private static final String PAYMENT_EVENTS_TOPIC = "payment-events";

    private final PaymentProcessingService paymentProcessingService;
    private final JsonMapper jsonMapper;

    public PaymentInitiatedConsumer(
            PaymentProcessingService paymentProcessingService,
            JsonMapper jsonMapper
    ) {
        this.paymentProcessingService = paymentProcessingService;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(
            topics = PAYMENT_EVENTS_TOPIC,
            groupId = "transaction-processor-group"
    )
    public void consume(
            ConsumerRecord<String, String> record,
            Acknowledgment acknowledgment
    ) {
        try {
            PaymentInitiatedEvent event = jsonMapper.readValue(
                    record.value(),
                    PaymentInitiatedEvent.class
            );

            paymentProcessingService.process(event);

            acknowledgment.acknowledge();

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed to process payment event from Kafka. "
                            + "Topic: " + record.topic()
                            + ", Partition: " + record.partition()
                            + ", Offset: " + record.offset(),
                    exception
            );
        }
    }
}