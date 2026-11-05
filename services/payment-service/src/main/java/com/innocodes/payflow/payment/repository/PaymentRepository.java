package com.innocodes.payflow.payment.repository;

import com.innocodes.payflow.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    boolean existsByMerchantIdAndReference(
            String merchantId,
            String reference
    );
}