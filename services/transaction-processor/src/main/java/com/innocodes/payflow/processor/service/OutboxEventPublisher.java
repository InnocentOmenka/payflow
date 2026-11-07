package com.innocodes.payflow.processor.service;

import com.innocodes.payflow.processor.config.KafkaTopicConfig;
import com.innocodes.payflow.processor.entity.OutboxEvent;
import com.innocodes.payflow.processor.enums.OutboxEventStatus;
import com.innocodes.payflow.processor.repository.OutboxEventRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(
        name = "payflow.outbox.publisher.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class OutboxEventPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${payflow.outbox.publisher.enabled:true}")
    private boolean publisherEnabled;

    public OutboxEventPublisher(
            OutboxEventRepository outboxEventRepository,
            KafkaTemplate<String, String> kafkaTemplate
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelay = 1000)
    public void publishPendingEvents() {

        if (!publisherEnabled) {
            return;
        }

        List<OutboxEvent> pendingEvents =
                outboxEventRepository.findTop100ByStatusOrderByCreatedAtAsc(
                        OutboxEventStatus.PENDING
                );

        for (OutboxEvent event : pendingEvents) {
            try {
                ProducerRecord<String, String> record =
                        new ProducerRecord<>(
                                KafkaTopicConfig.PAYMENT_PROCESSED_TOPIC,
                                event.getAggregateId().toString(),
                                event.getPayload()
                        );

                record.headers().add(
                        new RecordHeader(
                                "event-id",
                                event.getId().toString()
                                        .getBytes(StandardCharsets.UTF_8)
                        )
                );

                record.headers().add(
                        new RecordHeader(
                                "event-type",
                                event.getEventType()
                                        .getBytes(StandardCharsets.UTF_8)
                        )
                );

                kafkaTemplate.send(record)
                        .get(10, TimeUnit.SECONDS);

                event.markAsPublished();
                outboxEventRepository.save(event);

            } catch (Exception exception) {
                // Leave the event PENDING so a later polling cycle can retry.
                org.slf4j.LoggerFactory
                        .getLogger(OutboxEventPublisher.class)
                        .error(
                                "Failed to publish outbox event: {}",
                                event.getId(),
                                exception
                        );
            }
        }
    }
}