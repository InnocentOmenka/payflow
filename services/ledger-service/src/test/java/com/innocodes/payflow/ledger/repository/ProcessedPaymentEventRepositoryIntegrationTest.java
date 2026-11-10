package com.innocodes.payflow.ledger.repository;

import com.innocodes.payflow.ledger.entity.ProcessedPaymentEvent;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false"
})
@Testcontainers
class ProcessedPaymentEventRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private ProcessedPaymentEventRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @ParameterizedTest
    @CsvSource(value = {"SUCCESSFUL,NULL", "FAILED,Provider declined"}, nullValues = "NULL")
    void shouldPersistAcceptedPayload(String status, String failureReason) {
        ProcessedPaymentEvent event = event(UUID.randomUUID(), UUID.randomUUID(), status, failureReason);
        repository.saveAndFlush(event);

        // Repository calls use separate transactions, so this reads the stored row.
        ProcessedPaymentEvent saved = repository.findById(event.getEventId()).orElseThrow();
        assertEquals(event.getEventId(), saved.getEventId());
        assertEquals(event.getPaymentId(), saved.getPaymentId());
        assertEquals(event.getMerchantId(), saved.getMerchantId());
        assertEquals(event.getReference(), saved.getReference());
        assertEquals(event.getAmount(), saved.getAmount());
        assertEquals(event.getCurrency(), saved.getCurrency());
        assertEquals(status, saved.getStatus());
        assertEquals(failureReason, saved.getFailureReason());
        assertEquals(event.getProcessedAt(), saved.getProcessedAt());
        assertEquals(event.getRecordedAt(), saved.getRecordedAt());
        assertEquals(event.getEventId(), repository.findByPaymentId(event.getPaymentId())
                .orElseThrow().getEventId());
    }

    @Test
    void shouldRejectDuplicateEventIdForAnotherPayment() {
        UUID eventId = UUID.randomUUID();
        ProcessedPaymentEvent first = event(eventId, UUID.randomUUID(), "SUCCESSFUL", null);
        repository.saveAndFlush(first);

        // Force INSERT: JpaRepository.save with an assigned existing ID uses merge.
        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    entityManager.persist(event(eventId, UUID.randomUUID(), "FAILED", "Declined"));
                    entityManager.flush();
                }));
        assertTrue(rootMessage(exception).contains("pk_processed_payment_events"));
        assertEquals(first.getPaymentId(), repository.findById(eventId).orElseThrow().getPaymentId());
    }

    @Test
    void shouldRejectAnotherEventForSamePayment() {
        UUID paymentId = UUID.randomUUID();
        ProcessedPaymentEvent first = event(UUID.randomUUID(), paymentId, "FAILED", "Declined");
        repository.saveAndFlush(first);
        ProcessedPaymentEvent conflicting = event(UUID.randomUUID(), paymentId, "SUCCESSFUL", null);

        DataIntegrityViolationException exception = assertThrows(DataIntegrityViolationException.class,
                () -> repository.saveAndFlush(conflicting));
        assertTrue(rootMessage(exception).contains("uk_processed_payment_events_payment_id"));
        assertFalse(repository.existsById(conflicting.getEventId()));
        assertEquals(first.getEventId(), repository.findByPaymentId(paymentId).orElseThrow().getEventId());
    }

    @Test
    void shouldRejectUnsupportedStatus() {
        ProcessedPaymentEvent event = event(UUID.randomUUID(), UUID.randomUUID(), "PENDING", null);
        DataIntegrityViolationException exception = assertThrows(DataIntegrityViolationException.class,
                () -> repository.saveAndFlush(event));
        assertTrue(rootMessage(exception).contains("chk_processed_payment_events_status"));
        assertFalse(repository.existsById(event.getEventId()));
    }

    private ProcessedPaymentEvent event(UUID eventId, UUID paymentId, String status, String failureReason) {
        return new ProcessedPaymentEvent(eventId, paymentId, "MER-1001", "ORDER-10001",
                new BigDecimal("50000.00"), "NGN", status, failureReason,
                Instant.parse("2026-11-10T10:00:00Z"), Instant.parse("2026-11-10T10:00:01Z"));
    }

    private String rootMessage(Throwable exception) {
        while (exception.getCause() != null) {
            exception = exception.getCause();
        }
        return exception.getMessage();
    }
}
