package com.innocodes.payflow.merchant.membership;

import com.innocodes.payflow.merchant.entity.MerchantMembership;
import com.innocodes.payflow.merchant.repository.MerchantMembershipRepository;
import com.innocodes.payflow.merchant.repository.MerchantRepository;
import com.innocodes.payflow.merchant.security.PlatformAuthorization;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MembershipAdministrationService {
    private final PlatformAuthorization authorization;
    private final MerchantRepository merchants;
    private final MerchantMembershipRepository memberships;
    private final MembershipAuditStore audit;

    public MembershipAdministrationService(PlatformAuthorization authorization, MerchantRepository merchants,
            MerchantMembershipRepository memberships, MembershipAuditStore audit) {
        this.authorization = authorization;
        this.merchants = merchants;
        this.memberships = memberships;
        this.audit = audit;
    }

    @Transactional
    public MembershipResponse create(UUID merchantId, CreateMembershipRequest request) {
        var actor = authorization.require(); // Before lookup, including missing-target checks.
        if (!actor.issuer().equals(request.issuer())) throw new MembershipRequestException();
        lockMerchant(merchantId);
        var membership = new MerchantMembership(UUID.randomUUID(), merchantId, request.issuer(), request.subject(),
                request.role(), actor.issuer(), actor.subject(), Instant.now());
        memberships.saveAndFlush(membership);
        audit.append("GRANTED", membership, null, actor);
        // A uniqueness violation escapes this transaction; never query in an aborted transaction.
        return MembershipResponse.from(membership);
    }

    @Transactional(readOnly = true)
    public MembershipPageResponse list(UUID merchantId, int page, int size) {
        authorization.require();
        if (page < 0 || size < 1 || size > 100) throw new MembershipRequestException();
        if (!merchants.existsById(merchantId)) throw new MembershipNotFoundException();
        var result = memberships.findByMerchantId(merchantId, PageRequest.of(page, size,
                Sort.by("createdAt").and(Sort.by("id"))));
        return new MembershipPageResponse(result.getContent().stream().map(MembershipResponse::from).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public MembershipResponse updateRole(UUID merchantId, UUID membershipId, UpdateMembershipRequest request) {
        var actor = authorization.require();
        lockMerchant(merchantId);
        var membership = find(merchantId, membershipId, request.version());
        if (membership.getRole() != request.role()) {
            var before = MembershipAuditStore.State.from(membership);
            membership.changeRole(request.role(), actor.issuer(), actor.subject(), changeTime(membership));
            memberships.flush();
            audit.append("ROLE_CHANGED", membership, before, actor);
        }
        return MembershipResponse.from(membership);
    }

    @Transactional
    public MembershipResponse revoke(UUID merchantId, UUID membershipId, RevokeMembershipRequest request) {
        var actor = authorization.require();
        lockMerchant(merchantId);
        var membership = find(merchantId, membershipId, request.version());
        if (membership.isActive()) {
            var before = MembershipAuditStore.State.from(membership);
            membership.revoke(actor.issuer(), actor.subject(), changeTime(membership));
            memberships.flush();
            audit.append("REVOKED", membership, before, actor);
        }
        return MembershipResponse.from(membership);
    }

    private void lockMerchant(UUID merchantId) {
        // All membership mutations lock the parent first, including future tenant writes.
        merchants.findByIdForUpdate(merchantId).orElseThrow(MembershipNotFoundException::new);
    }

    private MerchantMembership find(UUID merchantId, UUID membershipId, Long version) {
        var membership = memberships.findByIdAndMerchantId(membershipId, merchantId)
                .orElseThrow(MembershipNotFoundException::new);
        if (!Objects.equals(membership.getVersion(), version)) throw new MembershipConflictException();
        return membership;
    }

    private Instant changeTime(MerchantMembership membership) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return now.isBefore(membership.getUpdatedAt()) ? membership.getUpdatedAt() : now;
    }
}
