package com.innocodes.payflow.ledger.repository;

import com.innocodes.payflow.ledger.entity.Journal;
import com.innocodes.payflow.ledger.enums.JournalType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JournalRepository extends JpaRepository<Journal, UUID> {

    Optional<Journal> findByPaymentIdAndJournalType(
            UUID paymentId,
            JournalType journalType
    );

    boolean existsByPaymentIdAndJournalType(
            UUID paymentId,
            JournalType journalType
    );

    List<Journal> findByMerchantId(String merchantId);
}