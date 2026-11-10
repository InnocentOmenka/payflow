package com.innocodes.payflow.ledger.service;

import com.innocodes.payflow.ledger.dto.JournalEntryRequest;
import com.innocodes.payflow.ledger.enums.EntryType;
import org.springframework.stereotype.Service;
import com.innocodes.payflow.ledger.entity.Journal;
import com.innocodes.payflow.ledger.entity.JournalLine;
import com.innocodes.payflow.ledger.entity.LedgerAccount;
import com.innocodes.payflow.ledger.enums.JournalStatus;
import com.innocodes.payflow.ledger.enums.JournalType;
import com.innocodes.payflow.ledger.repository.JournalRepository;
import com.innocodes.payflow.ledger.repository.JournalLineRepository;
import com.innocodes.payflow.ledger.repository.LedgerAccountRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class JournalPostingService {

    private final JournalRepository journalRepository;
    private final JournalLineRepository journalLineRepository;
    private final LedgerAccountRepository ledgerAccountRepository;

    public JournalPostingService(
            JournalRepository journalRepository,
            JournalLineRepository journalLineRepository,
            LedgerAccountRepository ledgerAccountRepository
    ) {
        this.journalRepository = journalRepository;
        this.journalLineRepository = journalLineRepository;
        this.ledgerAccountRepository = ledgerAccountRepository;
    }

    @Transactional
    public Journal postJournal(
            UUID paymentId,
            UUID sourceEventId,
            String merchantId,
            String transactionReference,
            JournalType journalType,
            String currency,
            String description,
            List<JournalEntryRequest> entries
    ) {
        if (paymentId == null || sourceEventId == null
                || merchantId == null || merchantId.isBlank()
                || transactionReference == null || transactionReference.isBlank()
                || journalType == null
                || currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Invalid journal metadata");
        }

        validateEntries(entries);

        var existingJournal = journalRepository.findByPaymentIdAndJournalType(
                paymentId,
                journalType
        );

        if (existingJournal.isPresent()) {
            Journal existing = existingJournal.get();

            if (existing.getSourceEventId().equals(sourceEventId)
                    && existing.getMerchantId().equals(merchantId)
                    && existing.getTransactionReference().equals(transactionReference)
                    && existing.getCurrency().equals(currency)
                    && Objects.equals(existing.getDescription(), description)
                    && entryCounts(entries).equals(entryCounts(
                            journalLineRepository.findByJournal_Id(existing.getId()).stream()
                                    .map(line -> new JournalEntryRequest(
                                            line.getLedgerAccount().getAccountCode(),
                                            line.getEntryType(),
                                            line.getAmount()
                                    ))
                                    .toList()
                    ))) {
                return existing;
            }

            throw new IllegalStateException(
                    "Conflicting journal operation for payment "
                            + paymentId
                            + " and type "
                            + journalType
            );
        }
        Instant now = Instant.now();

        Journal journal = new Journal(
                UUID.randomUUID(),
                paymentId,
                sourceEventId,
                merchantId,
                transactionReference,
                journalType,
                JournalStatus.POSTED,
                currency,
                description,
                now,
                now
        );

        List<JournalLine> lines = new ArrayList<>();

        for (JournalEntryRequest entry : entries) {
            LedgerAccount account = ledgerAccountRepository
                    .findByAccountCodeAndCurrency(entry.accountCode(), currency)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Ledger account not found: "
                                    + entry.accountCode() + " / " + currency
                    ));

            if (!account.isActive()) {
                throw new IllegalStateException(
                        "Ledger account is inactive: " + entry.accountCode()
                );
            }

            lines.add(new JournalLine(
                    UUID.randomUUID(),
                    journal,
                    account,
                    entry.entryType(),
                    entry.amount(),
                    now
            ));
        }

        journalRepository.save(journal);
        journalLineRepository.saveAll(lines);

        return journal;
    }

    private Map<JournalEntryRequest, Long> entryCounts(List<JournalEntryRequest> entries) {
        return entries.stream()
                .map(entry -> new JournalEntryRequest(
                        entry.accountCode(),
                        entry.entryType(),
                        entry.amount().stripTrailingZeros()
                ))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

     void validateEntries(List<JournalEntryRequest> entries) {

        if (entries == null || entries.size() < 2) {
            throw new IllegalArgumentException(
                    "A journal must contain at least two entries"
            );
        }

        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;

        for (JournalEntryRequest entry : entries) {

            if (entry == null
                    || entry.accountCode() == null
                    || entry.accountCode().isBlank()
                    || entry.entryType() == null
                    || entry.amount() == null) {
                throw new IllegalArgumentException(
                        "Journal entries must contain an account code, entry type, and amount"
                );
            }

            if (entry.amount().signum() <= 0) {
                throw new IllegalArgumentException(
                        "Journal entry amount must be greater than zero"
                );
            }

            if (entry.amount().scale() > 2) {
                throw new IllegalArgumentException(
                        "Journal entry amount cannot exceed two decimal places"
                );
            }

            if (entry.amount().precision() - entry.amount().scale() > 17) {
                throw new IllegalArgumentException(
                        "Journal entry amount exceeds the supported NUMERIC(19,2) range"
                );
            }

            if (entry.entryType() == EntryType.DEBIT) {
                totalDebits = totalDebits.add(entry.amount());
            } else if (entry.entryType() == EntryType.CREDIT) {
                totalCredits = totalCredits.add(entry.amount());
            } else {
                throw new IllegalArgumentException("Unsupported journal entry type");
            }
        }

        if (totalDebits.signum() == 0 || totalCredits.signum() == 0) {
            throw new IllegalArgumentException(
                    "A journal must contain both debit and credit entries"
            );
        }

        if (totalDebits.compareTo(totalCredits) != 0) {
            throw new IllegalArgumentException(
                    "Journal is not balanced. Total debits: "
                            + totalDebits
                            + ", total credits: "
                            + totalCredits
            );
        }
    }
}