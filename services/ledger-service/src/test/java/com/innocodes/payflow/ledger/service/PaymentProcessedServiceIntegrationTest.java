package com.innocodes.payflow.ledger.service;

import com.innocodes.payflow.contracts.payment.PaymentProcessedEvent;
import com.innocodes.payflow.ledger.enums.EntryType;
import com.innocodes.payflow.ledger.enums.JournalType;
import com.innocodes.payflow.ledger.repository.JournalLineRepository;
import com.innocodes.payflow.ledger.repository.JournalRepository;
import com.innocodes.payflow.ledger.repository.ProcessedPaymentEventRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false"
})
@Testcontainers
class PaymentProcessedServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private PaymentProcessedService service;
    @Autowired
    private ProcessedPaymentEventRepository events;
    @Autowired
    private JournalRepository journals;
    @Autowired
    private JournalLineRepository lines;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @MockitoSpyBean
    private JournalPostingService postingService;

    @Test
    void shouldPostSuccessfulPaymentWithEventMetadata() {
        PaymentProcessedEvent event = event("SUCCESSFUL");
        service.process(event);

        var accepted = events.findById(event.eventId()).orElseThrow();
        assertEquals(event.paymentId(), accepted.getPaymentId());
        assertEquals(event.processedAt().truncatedTo(ChronoUnit.MICROS), accepted.getProcessedAt());
        assertNotNull(accepted.getRecordedAt());
        var journal = journals.findByPaymentIdAndJournalType(event.paymentId(), JournalType.PAYMENT_CAPTURE)
                .orElseThrow();
        assertEquals(event.eventId(), journal.getSourceEventId());
        assertEquals(event.merchantId(), journal.getMerchantId());
        assertEquals(event.reference(), journal.getTransactionReference());
        assertEquals(event.currency(), journal.getCurrency());
        var entries = lines.findByJournal_Id(journal.getId());
        assertEquals(2, entries.size());
        for (var line : entries) {
            assertEquals(0, event.amount().compareTo(line.getAmount()));
        }
        // Account associations are lazy; inspect them inside a transaction below.
        var accountCodes = new TransactionTemplate(transactionManager)
                .execute(status -> lines.findByJournal_Id(journal.getId()).stream()
                        .map(line -> line.getLedgerAccount().getAccountCode() + ":" + line.getEntryType())
                        .toList());
        assertEquals(Set.of("1001:" + EntryType.DEBIT, "2001:" + EntryType.CREDIT),
                new HashSet<>(accountCodes));
    }

    @Test
    void shouldPersistFailedPaymentWithoutJournal() {
        PaymentProcessedEvent event = event("FAILED");
        service.process(event);
        assertEquals("Declined", events.findById(event.eventId()).orElseThrow().getFailureReason());
        assertFalse(journals.existsByPaymentIdAndJournalType(event.paymentId(), JournalType.PAYMENT_CAPTURE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESSFUL", "FAILED"})
    void shouldAcceptReplayWithEquivalentAmountAndTimestampPrecision(String status) {
        PaymentProcessedEvent event = event(status);
        service.process(event);
        Instant recordedAt = events.findById(event.eventId()).orElseThrow().getRecordedAt();
        PaymentProcessedEvent replay = copy(event, "amount", new BigDecimal("100.0"));
        service.process(replay);
        service.process(copy(replay, "processedAt", event.processedAt().truncatedTo(ChronoUnit.MICROS)));
        assertEquals(recordedAt, events.findById(event.eventId()).orElseThrow().getRecordedAt());
        var journal = journals.findByPaymentIdAndJournalType(event.paymentId(), JournalType.PAYMENT_CAPTURE);
        if ("SUCCESSFUL".equals(status)) {
            assertEquals(2, lines.findByJournal_Id(journal.orElseThrow().getId()).size());
        } else {
            assertTrue(journal.isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"paymentId", "merchantId", "reference", "amount", "currency", "status",
            "failureReason", "processedAt"})
    void shouldRejectChangedPayloadForSameEventId(String field) {
        PaymentProcessedEvent event = event("SUCCESSFUL");
        service.process(event);
        Object changed = switch (field) {
            case "paymentId" -> UUID.randomUUID();
            case "amount" -> new BigDecimal("200.00");
            case "currency" -> "USD";
            case "status" -> "FAILED";
            case "processedAt" -> event.processedAt().plus(1, ChronoUnit.MICROS);
            default -> "Changed";
        };
        assertThrows(IllegalStateException.class, () -> service.process(copy(event, field, changed)));
        assertEquals("SUCCESSFUL", events.findById(event.eventId()).orElseThrow().getStatus());
        assertEquals(2, lines.findByJournal_Id(journals.findByPaymentIdAndJournalType(
                event.paymentId(), JournalType.PAYMENT_CAPTURE).orElseThrow().getId()).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESSFUL", "FAILED"})
    void shouldRejectDifferentEventIdAndContradictoryStatusForAcceptedPayment(String status) {
        PaymentProcessedEvent event = event(status);
        service.process(event);
        PaymentProcessedEvent other = copy(event, "eventId", UUID.randomUUID());
        assertThrows(IllegalStateException.class, () -> service.process(other));
        PaymentProcessedEvent contradiction = copy(other, "status",
                "SUCCESSFUL".equals(status) ? "FAILED" : "SUCCESSFUL");
        assertThrows(IllegalStateException.class, () -> service.process(contradiction));
        assertFalse(events.existsById(other.eventId()));
        assertEquals(status, events.findByPaymentId(event.paymentId()).orElseThrow().getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "paymentId", "merchantId", "reference", "amount", "currency",
            "status", "processedAt"})
    void shouldRejectMissingRequiredFields(String field) {
        PaymentProcessedEvent invalid = copy(event("FAILED"), field, null);
        assertThrows(IllegalArgumentException.class, () -> service.process(invalid));
        if (invalid.eventId() != null) {
            assertFalse(events.existsById(invalid.eventId()));
        }
    }

    @Test
    void shouldRejectNullEvent() {
        assertThrows(IllegalArgumentException.class, () -> service.process(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"merchantId", "reference", "failureReason", "currency", "status",
            "zero", "negative", "scale", "overflow", "blank"})
    void shouldRejectValuesOutsideLedgerConstraints(String invalid) {
        String field = invalid;
        Object value;
        switch (invalid) {
            case "merchantId" -> value = "M".repeat(101);
            case "reference" -> value = "R".repeat(256);
            case "failureReason" -> value = "F".repeat(501);
            case "currency" -> value = "ngn";
            case "status" -> value = "PENDING";
            case "blank" -> { field = "merchantId"; value = " "; }
            default -> {
                field = "amount";
                value = new BigDecimal(switch (invalid) {
                    case "zero" -> "0";
                    case "negative" -> "-1";
                    case "scale" -> "1.001";
                    default -> "100000000000000000.00";
                });
            }
        }
        PaymentProcessedEvent event = copy(event("FAILED"), field, value);
        assertThrows(IllegalArgumentException.class, () -> service.process(event));
        assertFalse(events.existsById(event.eventId()));
    }

    @Test
    void shouldRollBackFlushedEventWhenAccountIsMissingAndAllowFreshAttempt() {
        PaymentProcessedEvent event = copy(event("SUCCESSFUL"), "currency", "EUR");
        assertThrows(IllegalArgumentException.class, () -> service.process(event));
        assertFalse(events.existsById(event.eventId()));
        assertTrue(events.findByPaymentId(event.paymentId()).isEmpty());
        assertFalse(journals.existsByPaymentIdAndJournalType(event.paymentId(), JournalType.PAYMENT_CAPTURE));
        service.process(copy(event, "currency", "NGN"));
        assertTrue(events.existsById(event.eventId()));
    }

    @Test
    void shouldRollBackEventJournalAndLinesAfterPostingWrites() {
        PaymentProcessedEvent event = event("SUCCESSFUL");
        AtomicReference<UUID> journalId = new AtomicReference<>();
        doAnswer(invocation -> {
            assertTrue(events.existsById(event.eventId()));
            var journal = (com.innocodes.payflow.ledger.entity.Journal) invocation.callRealMethod();
            journalId.set(journal.getId());
            entityManager.flush();
            assertTrue(journals.existsByPaymentIdAndJournalType(event.paymentId(), JournalType.PAYMENT_CAPTURE));
            throw new IllegalStateException("Simulated failure after journal writes");
        }).when(postingService).postJournal(eq(event.paymentId()), any(), anyString(), anyString(),
                any(), anyString(), anyString(), anyList());

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> service.process(event));
        assertEquals("Simulated failure after journal writes", exception.getMessage());
        assertFalse(events.existsById(event.eventId()));
        assertTrue(events.findByPaymentId(event.paymentId()).isEmpty());
        assertFalse(journals.existsByPaymentIdAndJournalType(event.paymentId(), JournalType.PAYMENT_CAPTURE));
        assertNotNull(journalId.get());
        assertTrue(lines.findByJournal_Id(journalId.get()).isEmpty());
    }

    private PaymentProcessedEvent event(String status) {
        return new PaymentProcessedEvent(UUID.randomUUID(), UUID.randomUUID(), "MER-1001", "ORDER-10001",
                new BigDecimal("100.00"), "NGN", status, "FAILED".equals(status) ? "Declined" : null,
                Instant.parse("2026-11-10T10:00:00.123456789Z"));
    }

    private PaymentProcessedEvent copy(PaymentProcessedEvent e, String field, Object value) {
        return new PaymentProcessedEvent(
                field.equals("eventId") ? (UUID) value : e.eventId(),
                field.equals("paymentId") ? (UUID) value : e.paymentId(),
                field.equals("merchantId") ? (String) value : e.merchantId(),
                field.equals("reference") ? (String) value : e.reference(),
                field.equals("amount") ? (BigDecimal) value : e.amount(),
                field.equals("currency") ? (String) value : e.currency(),
                field.equals("status") ? (String) value : e.status(),
                field.equals("failureReason") ? (String) value : e.failureReason(),
                field.equals("processedAt") ? (Instant) value : e.processedAt());
    }
}
