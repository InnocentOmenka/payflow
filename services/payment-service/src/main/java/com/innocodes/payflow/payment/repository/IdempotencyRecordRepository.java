package com.innocodes.payflow.payment.repository;

import com.innocodes.payflow.payment.entity.IdempotencyRecord;
import jakarta.persistence.Id;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {
    Optional<IdempotencyRecord> findByMerchantIdAndIdempotencyKey(
            String merchantId,
            String idempotencyKey
    );
}
