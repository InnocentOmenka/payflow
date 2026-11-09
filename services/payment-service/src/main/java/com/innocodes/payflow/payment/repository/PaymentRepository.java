package com.innocodes.payflow.payment.repository;

import com.innocodes.payflow.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    boolean existsByMerchantIdAndReference(
            String merchantId,
            String reference
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :paymentId")
    Optional<Payment> findByIdForUpdate(
            @Param("paymentId") UUID paymentId
    );
}