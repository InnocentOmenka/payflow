package com.innocodes.payflow.merchant.access;

import com.innocodes.payflow.merchant.enums.MerchantMembershipRole;
import com.innocodes.payflow.merchant.enums.MerchantStatus;
import com.innocodes.payflow.merchant.repository.MerchantMembershipRepository;
import com.innocodes.payflow.merchant.repository.MerchantRepository;
import com.innocodes.payflow.merchant.security.AccessResolverAuthorization;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantAccessService {
    private final AccessResolverAuthorization authorization;
    private final MerchantMembershipRepository memberships;
    private final MerchantRepository merchants;
    private final String issuer;

    public MerchantAccessService(AccessResolverAuthorization authorization, MerchantMembershipRepository memberships,
            MerchantRepository merchants, @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
        this.authorization = authorization;
        this.memberships = memberships;
        this.merchants = merchants;
        this.issuer = issuer;
    }

    @Transactional(readOnly = true)
    public Access resolve(MerchantAccessRequest request) {
        authorization.require();
        if (!issuer.equals(request.issuer())) throw denied();
        var membership = memberships.findByIssuerAndSubject(request.issuer(), request.subject())
                .filter(m -> m.isActive()).orElseThrow(MerchantAccessService::denied);
        var merchant = merchants.findById(membership.getMerchantId()).orElseThrow(MerchantAccessService::denied);
        if (merchant.getStatus() != MerchantStatus.ACTIVE && merchant.getStatus() != MerchantStatus.SUSPENDED) throw denied();
        return new Access(request.issuer(), request.subject(), merchant.getId(), merchant.getMerchantId(),
                membership.getRole(), merchant.getStatus());
    }

    private static AccessDeniedException denied() { return new AccessDeniedException("Access is denied"); }

    public record Access(String issuer, String subject, UUID merchantUuid, String merchantId,
                         MerchantMembershipRole role, MerchantStatus status) {}
}
