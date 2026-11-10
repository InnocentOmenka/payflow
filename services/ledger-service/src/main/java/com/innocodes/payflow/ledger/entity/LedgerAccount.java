package com.innocodes.payflow.ledger.entity;

import com.innocodes.payflow.ledger.enums.AccountStatus;
import com.innocodes.payflow.ledger.enums.AccountType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "ledger_accounts",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_ledger_account_code_currency",
                        columnNames = {"account_code", "currency"}
                )
        }
)
public class LedgerAccount {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "account_code", nullable = false, length = 20)
    private String accountCode;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType accountType;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerAccount() {
        // Required by JPA
    }

    public LedgerAccount(
            UUID id,
            String accountCode,
            String name,
            AccountType accountType,
            String currency,
            AccountStatus status,
            Instant createdAt
    ) {
        this.id = id;
        this.accountCode = accountCode;
        this.name = name;
        this.accountType = accountType;
        this.currency = currency;
        this.status = status;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getAccountCode() {
        return accountCode;
    }

    public String getName() {
        return name;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public String getCurrency() {
        return currency;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void deactivate() {
        this.status = AccountStatus.INACTIVE;
    }

    public boolean isActive() {
        return this.status == AccountStatus.ACTIVE;
    }
}