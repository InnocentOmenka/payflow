package com.innocodes.payflow.ledger.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.PostgreSQLContainer;
import com.innocodes.payflow.ledger.dto.JournalEntryRequest;
import com.innocodes.payflow.ledger.entity.Journal;
import com.innocodes.payflow.ledger.entity.JournalLine;
import com.innocodes.payflow.ledger.enums.EntryType;
import com.innocodes.payflow.ledger.enums.JournalStatus;
import com.innocodes.payflow.ledger.enums.JournalType;
import com.innocodes.payflow.ledger.repository.JournalLineRepository;
import com.innocodes.payflow.ledger.repository.JournalRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false"
})
@Testcontainers
class JournalPostingServiceIntegrationTest {

    @Autowired
    private JournalPostingService journalPostingService;

    @Autowired
    private JournalRepository journalRepository;

    @Autowired
    private JournalLineRepository journalLineRepository;

    @Test
    void shouldPostBalancedPaymentCaptureJournal() {
        UUID paymentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        List<JournalEntryRequest> entries = List.of(
                new JournalEntryRequest(
                        "1001",
                        EntryType.DEBIT,
                        new BigDecimal("50000.00")
                ),
                new JournalEntryRequest(
                        "2001",
                        EntryType.CREDIT,
                        new BigDecimal("50000.00")
                )
        );

        Journal journal = journalPostingService.postJournal(
                paymentId,
                eventId,
                "MER-1001",
                "ORDER-10001",
                JournalType.PAYMENT_CAPTURE,
                "NGN",
                "Successful payment capture",
                entries
        );

        assertNotNull(journal.getId());
        assertEquals(JournalStatus.POSTED, journal.getStatus());
        assertEquals(paymentId, journal.getPaymentId());

        List<JournalLine> savedLines =
                journalLineRepository.findByJournal_Id(journal.getId());

        assertEquals(2, savedLines.size());

        BigDecimal totalDebits = savedLines.stream()
                .filter(line -> line.getEntryType() == EntryType.DEBIT)
                .map(JournalLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCredits = savedLines.stream()
                .filter(line -> line.getEntryType() == EntryType.CREDIT)
                .map(JournalLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(0, totalDebits.compareTo(totalCredits));
        assertEquals(0, totalDebits.compareTo(new BigDecimal("50000.00")));

        assertTrue(
                journalRepository.existsByPaymentIdAndJournalType(
                        paymentId,
                        JournalType.PAYMENT_CAPTURE
                )
        );
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17");

    @Test
    void shouldStartApplicationWithPostgresContainer() {
        assertNotNull(postgres.getJdbcUrl());
    }

    @Test
    void shouldNotPersistJournalWhenLedgerAccountDoesNotExist() {
        UUID paymentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        List<JournalEntryRequest> entries = List.of(
                new JournalEntryRequest(
                        "1001",
                        EntryType.DEBIT,
                        new BigDecimal("50000.00")
                ),
                new JournalEntryRequest(
                        "9999",
                        EntryType.CREDIT,
                        new BigDecimal("50000.00")
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> journalPostingService.postJournal(
                        paymentId,
                        eventId,
                        "MER-1001",
                        "ORDER-10001",
                        JournalType.PAYMENT_CAPTURE,
                        "NGN",
                        "Payment with invalid ledger account",
                        entries
                )
        );

        assertFalse(
                journalRepository.existsByPaymentIdAndJournalType(
                        paymentId,
                        JournalType.PAYMENT_CAPTURE
                )
        );
    }

    @Test
    void shouldRejectDuplicatePaymentCaptureJournal() {
        UUID paymentId = UUID.randomUUID();

        List<JournalEntryRequest> entries = List.of(
                new JournalEntryRequest(
                        "1001",
                        EntryType.DEBIT,
                        new BigDecimal("50000.00")
                ),
                new JournalEntryRequest(
                        "2001",
                        EntryType.CREDIT,
                        new BigDecimal("50000.00")
                )
        );

        journalPostingService.postJournal(
                paymentId,
                UUID.randomUUID(),
                "MER-1001",
                "ORDER-10001",
                JournalType.PAYMENT_CAPTURE,
                "NGN",
                "Successful payment capture",
                entries
        );

        assertThrows(
                IllegalStateException.class,
                () -> journalPostingService.postJournal(
                        paymentId,
                        UUID.randomUUID(),
                        "MER-1001",
                        "ORDER-10001",
                        JournalType.PAYMENT_CAPTURE,
                        "NGN",
                        "Duplicate payment capture",
                        entries
                )
        );

        assertEquals(
                1,
                journalRepository.findAll().stream()
                        .filter(journal -> journal.getPaymentId().equals(paymentId))
                        .filter(journal -> journal.getJournalType() == JournalType.PAYMENT_CAPTURE)
                        .count()
        );

        assertEquals(
                2,
                journalLineRepository.findByJournal_Id(
                        journalRepository.findByPaymentIdAndJournalType(
                                paymentId,
                                JournalType.PAYMENT_CAPTURE
                        ).orElseThrow().getId()
                ).size()
        );
    }

    @Test
    void shouldReturnExistingJournalWhenSameEventIsRedelivered() {
        UUID paymentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        List<JournalEntryRequest> entries = List.of(
                new JournalEntryRequest(
                        "1001",
                        EntryType.DEBIT,
                        new BigDecimal("50000.00")
                ),
                new JournalEntryRequest(
                        "2001",
                        EntryType.CREDIT,
                        new BigDecimal("50000.00")
                )
        );

        Journal firstJournal = journalPostingService.postJournal(
                paymentId,
                eventId,
                "MER-1001",
                "ORDER-10001",
                JournalType.PAYMENT_CAPTURE,
                "NGN",
                "Successful payment capture",
                entries
        );

        Journal replayedJournal = journalPostingService.postJournal(
                paymentId,
                eventId,
                "MER-1001",
                "ORDER-10001",
                JournalType.PAYMENT_CAPTURE,
                "NGN",
                "Successful payment capture",
                entries
        );

        assertEquals(firstJournal.getId(), replayedJournal.getId());

        List<JournalLine> savedLines =
                journalLineRepository.findByJournal_Id(firstJournal.getId());

        assertEquals(2, savedLines.size());

        long journalCount = journalRepository.findAll().stream()
                .filter(journal -> journal.getPaymentId().equals(paymentId))
                .filter(journal -> journal.getJournalType() == JournalType.PAYMENT_CAPTURE)
                .count();

        assertEquals(1, journalCount);
    }
}