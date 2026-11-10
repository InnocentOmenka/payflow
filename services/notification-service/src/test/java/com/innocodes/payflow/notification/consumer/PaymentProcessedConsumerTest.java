package com.innocodes.payflow.notification.consumer;

import com.innocodes.payflow.contracts.payment.PaymentProcessedEvent;
import com.innocodes.payflow.notification.exception.NotificationConflictException;
import com.innocodes.payflow.notification.service.NotificationIngestionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentProcessedConsumerTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final NotificationIngestionService service = mock(NotificationIngestionService.class);
    private final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    private final PaymentProcessedConsumer consumer = new PaymentProcessedConsumer(mapper, service);

    @Test
    void shouldUsePayloadIdentityAndAcknowledgeAfterServiceReturns() {
        PaymentProcessedEvent event = new PaymentProcessedEvent(UUID.randomUUID(), UUID.randomUUID(),
                "MER-1", "ORDER-1", new BigDecimal("100.00"), "NGN", "SUCCESSFUL", null, Instant.now());
        var record = record(mapper.writeValueAsString(event));
        record.headers().add("event-id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        consumer.consume(record, acknowledgment);
        var order = inOrder(service, acknowledgment);
        order.verify(service).process(event);
        order.verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldNotAcknowledgeMalformedJson() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> consumer.consume(record("{broken"), acknowledgment));
        assertTrue(exception.getMessage().contains("payment-processed-1 offset 42"));
        verifyNoInteractions(service, acknowledgment);
    }

    @Test
    void shouldRejectTombstone() {
        assertThrows(IllegalArgumentException.class, () -> consumer.consume(record(null), acknowledgment));
        verifyNoInteractions(service, acknowledgment);
    }

    @Test
    void shouldPropagateValidationFailureWithoutAcknowledgment() {
        doThrow(new IllegalArgumentException("Invalid event")).when(service).process(null);
        assertThrows(IllegalArgumentException.class, () -> consumer.consume(record("null"), acknowledgment));
        verifyNoInteractions(acknowledgment);
    }

    @Test
    void shouldPropagateConflictWithoutAcknowledgment() {
        doThrow(new NotificationConflictException("Conflict")).when(service).process(null);
        assertThrows(NotificationConflictException.class, () -> consumer.consume(record("null"), acknowledgment));
        verifyNoInteractions(acknowledgment);
    }

    @Test
    void shouldPropagateIntegrityFailureForFreshTransactionRetry() {
        var failure = new DataIntegrityViolationException("Concurrent insert");
        doThrow(failure).when(service).process(null);
        assertSame(failure, assertThrows(DataIntegrityViolationException.class,
                () -> consumer.consume(record("null"), acknowledgment)));
        verifyNoInteractions(acknowledgment);
    }

    private ConsumerRecord<String, String> record(String payload) {
        return new ConsumerRecord<>("payment-processed", 1, 42L, "payment-key", payload);
    }
}
