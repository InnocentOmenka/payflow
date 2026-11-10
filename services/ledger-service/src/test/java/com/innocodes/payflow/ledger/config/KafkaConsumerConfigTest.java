package com.innocodes.payflow.ledger.config;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KafkaConsumerConfigTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldNotCommitSourceOffsetWhenDltSendFails() {
        KafkaTemplate<Object, Object> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class))).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("DLT unavailable")));
        var config = new KafkaConsumerConfig();
        var recoverer = config.ledgerDeadLetterRecoverer(template);
        // No live metadata lookup in this unit test.
        recoverer.setVerifyPartition(false);
        var handler = config.kafkaErrorHandler(recoverer, 0);
        Consumer<String, String> consumer = mock(Consumer.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        var properties = new ContainerProperties("payment-processed");
        properties.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        when(container.getContainerProperties()).thenReturn(properties);
        var record = new ConsumerRecord<>("payment-processed", 0, 4L, "key", "bad-json");

        assertThrows(RuntimeException.class, () -> handler.handleRemaining(
                new IllegalArgumentException("Invalid"), List.of(record), consumer, container));
        verify(template).send(argThat((ProducerRecord<Object, Object> sent) ->
                sent.topic().equals("payment-processed-ledger-dlt") && sent.value().equals("bad-json")));
        verify(consumer, never()).commitSync(anyMap(), any());
        verify(consumer, never()).commitAsync(anyMap(), any());
    }
}
