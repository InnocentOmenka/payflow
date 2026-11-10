package com.innocodes.payflow.ledger.dto;

import com.innocodes.payflow.ledger.enums.EntryType;

import java.math.BigDecimal;

public record JournalEntryRequest(
        String accountCode,
        EntryType entryType,
        BigDecimal amount
) {
}