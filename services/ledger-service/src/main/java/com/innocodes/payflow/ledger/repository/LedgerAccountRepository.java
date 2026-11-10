package com.innocodes.payflow.ledger.repository;

import com.innocodes.payflow.ledger.entity.LedgerAccount;
import com.innocodes.payflow.ledger.enums.AccountStatus;
import com.innocodes.payflow.ledger.enums.AccountType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {

    Optional<LedgerAccount> findByAccountCodeAndCurrency(
            String accountCode,
            String currency
    );

    List<LedgerAccount> findByAccountTypeAndCurrencyAndStatus(
            AccountType accountType,
            String currency,
            AccountStatus status
    );

    boolean existsByAccountCodeAndCurrency(String accountCode, String currency);
}