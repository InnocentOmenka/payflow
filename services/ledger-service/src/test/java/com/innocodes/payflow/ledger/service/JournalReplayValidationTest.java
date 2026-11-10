package com.innocodes.payflow.ledger.service;

import com.innocodes.payflow.ledger.dto.JournalEntryRequest;
import com.innocodes.payflow.ledger.entity.Journal;
import com.innocodes.payflow.ledger.entity.JournalLine;
import com.innocodes.payflow.ledger.entity.LedgerAccount;
import com.innocodes.payflow.ledger.enums.EntryType;
import com.innocodes.payflow.ledger.enums.JournalStatus;
import com.innocodes.payflow.ledger.enums.JournalType;
import com.innocodes.payflow.ledger.repository.JournalLineRepository;
import com.innocodes.payflow.ledger.repository.JournalRepository;
import com.innocodes.payflow.ledger.repository.LedgerAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JournalReplayValidationTest {

    private final JournalRepository journals = mock(JournalRepository.class);
    private final JournalLineRepository lines = mock(JournalLineRepository.class);
    private final LedgerAccountRepository accounts = mock(LedgerAccountRepository.class);
    private final JournalPostingService service = new JournalPostingService(journals, lines, accounts);
    private final UUID paymentId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private final Journal existing = new Journal(
            UUID.randomUUID(), paymentId, eventId, "MER-1001", "ORDER-10001",
            JournalType.PAYMENT_CAPTURE, JournalStatus.POSTED, "NGN", "Capture",
            Instant.now(), Instant.now()
    );

    @BeforeEach
    void setUp() {
        when(journals.findByPaymentIdAndJournalType(paymentId, JournalType.PAYMENT_CAPTURE))
                .thenReturn(Optional.of(existing));
        storedEntries(balancedEntries("100.00"));
    }

    @Test
    void shouldReturnExistingJournalForIdenticalReplay() {
        assertSame(existing, replay(eventId, "MER-1001", "ORDER-10001", "NGN", "Capture",
                balancedEntries("100.00")));
        verifyNoWrites();
    }

    @Test
    void shouldIgnoreEntryOrderAndAmountScale() {
        assertSame(existing, replay(eventId, "MER-1001", "ORDER-10001", "NGN", "Capture",
                List.of(entry("2001", EntryType.CREDIT, "100.0"),
                        entry("1001", EntryType.DEBIT, "100"))));
        verifyNoWrites();
    }

    @Test
    void shouldRejectChangedAmount() {
        assertConflict(eventId, "MER-1001", "ORDER-10001", "NGN", "Capture", balancedEntries("200.00"));
    }

    @Test
    void shouldRejectChangedMerchant() {
        assertConflict(eventId, "MER-OTHER", "ORDER-10001", "NGN", "Capture", balancedEntries("100.00"));
    }

    @Test
    void shouldRejectChangedCurrency() {
        assertConflict(eventId, "MER-1001", "ORDER-10001", "USD", "Capture", balancedEntries("100.00"));
    }

    @Test
    void shouldRejectChangedReference() {
        assertConflict(eventId, "MER-1001", "OTHER", "NGN", "Capture", balancedEntries("100.00"));
    }

    @Test
    void shouldRejectChangedDescriptionIncludingNull() {
        assertConflict(eventId, "MER-1001", "ORDER-10001", "NGN", "Other", balancedEntries("100.00"));
        assertConflict(eventId, "MER-1001", "ORDER-10001", "NGN", null, balancedEntries("100.00"));
    }

    @Test
    void shouldAcceptMatchingNullDescriptions() {
        Journal withoutDescription = new Journal(existing.getId(), paymentId, eventId,
                "MER-1001", "ORDER-10001", JournalType.PAYMENT_CAPTURE,
                JournalStatus.POSTED, "NGN", null, Instant.now(), Instant.now());
        when(journals.findByPaymentIdAndJournalType(paymentId, JournalType.PAYMENT_CAPTURE))
                .thenReturn(Optional.of(withoutDescription));
        assertSame(withoutDescription, replay(eventId, "MER-1001", "ORDER-10001", "NGN", null,
                balancedEntries("100.00")));
        verifyNoWrites();
    }

    @Test
    void shouldRejectDifferentSourceEvent() {
        assertConflict(UUID.randomUUID(), "MER-1001", "ORDER-10001", "NGN", "Capture", balancedEntries("100.00"));
    }

    @Test
    void shouldRejectChangedAccount() {
        assertConflict(eventId, "MER-1001", "ORDER-10001", "NGN", "Capture",
                List.of(entry("1002", EntryType.DEBIT, "100"), entry("2001", EntryType.CREDIT, "100")));
    }

    @Test
    void shouldRejectChangedDirection() {
        assertConflict(eventId, "MER-1001", "ORDER-10001", "NGN", "Capture",
                List.of(entry("1001", EntryType.CREDIT, "100"), entry("2001", EntryType.DEBIT, "100")));
    }

    @Test
    void shouldRejectSplitEntriesEvenWhenTotalsMatch() {
        assertConflict(eventId, "MER-1001", "ORDER-10001", "NGN", "Capture",
                List.of(entry("1001", EntryType.DEBIT, "50"), entry("1001", EntryType.DEBIT, "50"),
                        entry("2001", EntryType.CREDIT, "100")));
    }

    @Test
    void shouldCompareDuplicateCountsRatherThanOnlyDistinctEntries() {
        JournalEntryRequest debit100 = entry("1001", EntryType.DEBIT, "100");
        JournalEntryRequest debit200 = entry("1001", EntryType.DEBIT, "200");
        JournalEntryRequest credit100 = entry("2001", EntryType.CREDIT, "100");
        JournalEntryRequest credit200 = entry("2001", EntryType.CREDIT, "200");
        storedEntries(List.of(debit100, debit100, debit200, credit100, credit100, credit200));

        assertSame(existing, replay(eventId, "MER-1001", "ORDER-10001", "NGN", "Capture",
                List.of(credit200, debit100, credit100, debit200, credit100, debit100)));
        assertConflict(eventId, "MER-1001", "ORDER-10001", "NGN", "Capture",
                List.of(debit100, debit200, debit200, credit100, credit200, credit200));
    }

    private void storedEntries(List<JournalEntryRequest> entries) {
        List<JournalLine> storedLines = entries.stream().map(entry -> {
            LedgerAccount account = mock(LedgerAccount.class);
            when(account.getAccountCode()).thenReturn(entry.accountCode());
            return new JournalLine(UUID.randomUUID(), existing, account, entry.entryType(),
                    entry.amount(), Instant.now());
        }).toList();
        when(lines.findByJournal_Id(existing.getId())).thenReturn(storedLines);
    }

    private Journal replay(UUID sourceEventId, String merchant, String reference, String currency,
                           String description, List<JournalEntryRequest> entries) {
        return service.postJournal(paymentId, sourceEventId, merchant, reference,
                JournalType.PAYMENT_CAPTURE, currency, description, entries);
    }

    private void assertConflict(UUID sourceEventId, String merchant, String reference, String currency,
                                String description, List<JournalEntryRequest> entries) {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> replay(sourceEventId, merchant, reference, currency, description, entries));
        assertTrue(exception.getMessage().contains("Conflicting journal operation"));
        verifyNoWrites();
    }

    private void verifyNoWrites() {
        verify(journals, never()).save(any(Journal.class));
        verify(lines, never()).saveAll(any());
        verifyNoInteractions(accounts);
    }

    private List<JournalEntryRequest> balancedEntries(String amount) {
        return List.of(entry("1001", EntryType.DEBIT, amount), entry("2001", EntryType.CREDIT, amount));
    }

    private JournalEntryRequest entry(String account, EntryType direction, String amount) {
        return new JournalEntryRequest(account, direction, new BigDecimal(amount));
    }
}
