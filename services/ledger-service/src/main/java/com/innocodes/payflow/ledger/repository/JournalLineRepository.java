package com.innocodes.payflow.ledger.repository;

import com.innocodes.payflow.ledger.entity.JournalLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JournalLineRepository extends JpaRepository<JournalLine, UUID> {

    List<JournalLine> findByJournal_Id(UUID journalId);

    List<JournalLine> findByLedgerAccount_Id(UUID ledgerAccountId);
}