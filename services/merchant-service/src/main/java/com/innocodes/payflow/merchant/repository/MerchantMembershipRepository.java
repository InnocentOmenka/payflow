package com.innocodes.payflow.merchant.repository;

import com.innocodes.payflow.merchant.entity.MerchantMembership;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantMembershipRepository extends JpaRepository<MerchantMembership, UUID> {
    Optional<MerchantMembership> findByIssuerAndSubject(String issuer, String subject);
    List<MerchantMembership> findByMerchantId(UUID merchantId);
    org.springframework.data.domain.Page<MerchantMembership> findByMerchantId(UUID merchantId, org.springframework.data.domain.Pageable pageable);
    Optional<MerchantMembership> findByIdAndMerchantId(UUID id, UUID merchantId);
}
