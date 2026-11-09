package com.innocodes.payflow.payment.repository;

import com.innocodes.payflow.payment.entity.ProcessedPaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProcessedPaymentEventRepository
        extends JpaRepository<ProcessedPaymentEvent, UUID> {
}