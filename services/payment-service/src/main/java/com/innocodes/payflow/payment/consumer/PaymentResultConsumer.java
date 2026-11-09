package com.innocodes.payflow.payment.consumer;

import com.innocodes.payflow.contracts.payment.PaymentProcessedEvent;
import com.innocodes.payflow.payment.service.PaymentResultService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PaymentResultConsumer {

    private final JsonMapper jsonMapper;
    private final PaymentResultService paymentResultService;

    public PaymentResultConsumer(
            JsonMapper jsonMapper,
            PaymentResultService paymentResultService
    ) {
        this.jsonMapper = jsonMapper;
        this.paymentResultService = paymentResultService;
    }

    @KafkaListener(
            topics = "payment-processed",
            groupId = "payment-service-group"
    )
    public void consume(
            ConsumerRecord<String, String> record,
            Acknowledgment acknowledgment
    ) {
        try {
            PaymentProcessedEvent event = jsonMapper.readValue(
                    record.value(),
                    PaymentProcessedEvent.class
            );

            paymentResultService.process(event);

            // Acknowledge only after the transactional service call succeeds.
            acknowledgment.acknowledge();

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed to process payment result from topic "
                            + record.topic()
                            + ", partition "
                            + record.partition()
                            + ", offset "
                            + record.offset(),
                    exception
            );
        }
    }
}