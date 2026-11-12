package com.innocodes.payflow.merchant.membership;

import com.innocodes.payflow.merchant.entity.MerchantMembership;
import com.innocodes.payflow.merchant.security.PlatformAuthorization.Actor;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Insert-only application API; database triggers also reject updates, deletes and truncation. */
@Repository
public class MembershipAuditStore {
    private final JdbcTemplate jdbc;
    public MembershipAuditStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void append(String action, MerchantMembership membership, State before, Actor actor) {
        jdbc.update("""
                INSERT INTO merchant_membership_audit
                  (id, membership_id, merchant_uuid, principal_issuer, principal_subject,
                   action, old_role, new_role, old_active, new_active, before_version, after_version,
                   actor_issuer, actor_subject, actor_client_id, recorded_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), membership.getId(), membership.getMerchantId(), membership.getIssuer(),
                membership.getSubject(), action, before == null ? null : before.role(), membership.getRole().name(),
                before == null ? null : before.active(), membership.isActive(), before == null ? null : before.version(),
                membership.getVersion(), actor.issuer(), actor.subject(), actor.clientId(),
                Timestamp.from(membership.getUpdatedAt()));
    }

    public record State(String role, boolean active, Long version) {
        public static State from(MerchantMembership membership) {
            return new State(membership.getRole().name(), membership.isActive(), membership.getVersion());
        }
    }
}
