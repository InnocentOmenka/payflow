package com.innocodes.payflow.merchant.repository;

import com.innocodes.payflow.merchant.entity.Merchant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.Optional;
import java.util.UUID;

public interface MerchantRepository extends JpaRepository<Merchant, UUID>, JpaSpecificationExecutor<Merchant> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select m from Merchant m where m.id = :id")
    Optional<Merchant> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);

    Optional<Merchant> findByMerchantId(String merchantId);
}
