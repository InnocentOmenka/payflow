package com.innocodes.payflow.ledger.repository;

import com.innocodes.payflow.ledger.entity.ProcessedPaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProcessedPaymentEventRepository extends JpaRepository<ProcessedPaymentEvent, UUID> {

    Optional<ProcessedPaymentEvent> findByPaymentId(UUID paymentId);
}
