package com.innocodes.payflow.processor.repository;

import com.innocodes.payflow.processor.entity.ProcessedPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProcessedPaymentRepository
        extends JpaRepository<ProcessedPayment, UUID> {

    Optional<ProcessedPayment> findByEventId(UUID eventId);

    Optional<ProcessedPayment> findByPaymentId(UUID paymentId);
    boolean existsByEventId(UUID eventId);

    boolean existsByPaymentId(UUID paymentId);
}