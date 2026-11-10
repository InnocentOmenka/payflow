package com.innocodes.payflow.ledger.entity;

import com.innocodes.payflow.ledger.enums.EntryType;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "journal_lines",
        indexes = {
                @Index(
                        name = "idx_journal_lines_journal_id",
                        columnList = "journal_id"
                ),
                @Index(
                        name = "idx_journal_lines_account_id",
                        columnList = "ledger_account_id"
                )
        }
)
public class JournalLine {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "journal_id",
            nullable = false,
            updatable = false
    )
    private Journal journal;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "ledger_account_id",
            nullable = false,
            updatable = false
    )
    private LedgerAccount ledgerAccount;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "entry_type",
            nullable = false,
            updatable = false,
            length = 10
    )
    private EntryType entryType;

    @Column(
            name = "amount",
            nullable = false,
            updatable = false,
            precision = 19,
            scale = 2
    )
    private BigDecimal amount;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    protected JournalLine() {
        // Required by JPA
    }

    public JournalLine(
            UUID id,
            Journal journal,
            LedgerAccount ledgerAccount,
            EntryType entryType,
            BigDecimal amount,
            Instant createdAt
    ) {
        this.id = id;
        this.journal = journal;
        this.ledgerAccount = ledgerAccount;
        this.entryType = entryType;
        this.amount = amount;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public Journal getJournal() {
        return journal;
    }

    public LedgerAccount getLedgerAccount() {
        return ledgerAccount;
    }

    public EntryType getEntryType() {
        return entryType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}