package com.innocodes.payflow.payment.service;

import com.innocodes.payflow.payment.config.KafkaTopicConfig;
import com.innocodes.payflow.payment.entity.OutboxEvent;
import com.innocodes.payflow.payment.enums.OutboxEventStatus;
import com.innocodes.payflow.payment.repository.OutboxEventRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@ConditionalOnProperty(
        name = "payflow.outbox.publisher.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class OutboxEventPublisher {

    private static final int SEND_TIMEOUT_SECONDS = 10;
    private static final Logger log = LoggerFactory.getLogger(OutboxEventPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxEventPublisher(
            OutboxEventRepository outboxEventRepository,
            KafkaTemplate<String, String> kafkaTemplate
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelay = 1000)
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents =
                outboxEventRepository.findTop100ByStatusOrderByCreatedAtAsc(
                        OutboxEventStatus.PENDING
                );

        for (OutboxEvent event : pendingEvents) {
            try {
                publish(event);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | TimeoutException exception) {
                // Leave the event PENDING so a later scheduled run can retry it.
                log.error(
                        "Failed to publish outbox event {}. It will be retried.",
                        event.getId(),
                        exception
                );
            }
        }
    }

    private void publish(OutboxEvent event)
            throws InterruptedException, ExecutionException, TimeoutException {

        ProducerRecord<String, String> record = new ProducerRecord<>(
                KafkaTopicConfig.PAYMENT_EVENTS_TOPIC,
                event.getAggregateId().toString(),
                event.getPayload()
        );

        record.headers().add(
                "event-id",
                event.getId().toString().getBytes(StandardCharsets.UTF_8)
        );

        record.headers().add(
                "event-type",
                event.getEventType().getBytes(StandardCharsets.UTF_8)
        );

        // Wait for Kafka to acknowledge the message.
        kafkaTemplate.send(record).get(
                SEND_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
        );

        // Only mark as published after successful acknowledgement.
        event.markAsPublished();
        outboxEventRepository.save(event);
    }
}