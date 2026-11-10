package com.innocodes.payflow.ledger.service;

import com.innocodes.payflow.ledger.dto.JournalEntryRequest;
import com.innocodes.payflow.ledger.enums.EntryType;
import com.innocodes.payflow.ledger.repository.JournalLineRepository;
import com.innocodes.payflow.ledger.repository.JournalRepository;
import com.innocodes.payflow.ledger.repository.LedgerAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class JournalPostingServiceTest {

    private JournalPostingService service;

    @BeforeEach
    void setUp() {
        service = new JournalPostingService(
                mock(JournalRepository.class),
                mock(JournalLineRepository.class),
                mock(LedgerAccountRepository.class)
        );
    }

    @Test
    void shouldAcceptBalancedJournalEntries() {
        List<JournalEntryRequest> entries = List.of(
                entry("1001", EntryType.DEBIT, "50000.00"),
                entry("2001", EntryType.CREDIT, "50000.00")
        );

        assertDoesNotThrow(() -> service.validateEntries(entries));
    }

    @Test
    void shouldRejectUnbalancedJournalEntries() {
        List<JournalEntryRequest> entries = List.of(
                entry("1001", EntryType.DEBIT, "50000.00"),
                entry("2001", EntryType.CREDIT, "40000.00")
        );

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.validateEntries(entries)
        );

        assertTrue(exception.getMessage().contains("not balanced"));
    }

    @Test
    void shouldRejectNegativeAmounts() {
        List<JournalEntryRequest> entries = List.of(
                entry("1001", EntryType.DEBIT, "-50000.00"),
                entry("2001", EntryType.CREDIT, "50000.00")
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.validateEntries(entries)
        );
    }

    @Test
    void shouldRejectSingleEntry() {
        List<JournalEntryRequest> entries = List.of(
                entry("1001", EntryType.DEBIT, "50000.00")
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.validateEntries(entries)
        );
    }

    @Test
    void shouldRejectExcessiveDecimalPlaces() {
        List<JournalEntryRequest> entries = List.of(
                entry("1001", EntryType.DEBIT, "50000.123"),
                entry("2001", EntryType.CREDIT, "50000.123")
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.validateEntries(entries)
        );
    }

    private JournalEntryRequest entry(
            String accountCode,
            EntryType entryType,
            String amount
    ) {
        return new JournalEntryRequest(
                accountCode,
                entryType,
                new BigDecimal(amount)
        );
    }
}