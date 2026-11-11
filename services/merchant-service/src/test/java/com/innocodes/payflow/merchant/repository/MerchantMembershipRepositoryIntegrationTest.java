package com.innocodes.payflow.merchant.repository;

import com.innocodes.payflow.merchant.dto.request.RegisterMerchantRequest;
import com.innocodes.payflow.merchant.entity.Merchant;
import com.innocodes.payflow.merchant.entity.MerchantMembership;
import com.innocodes.payflow.merchant.enums.MerchantMembershipRole;
import com.innocodes.payflow.merchant.service.MerchantService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "payflow.merchant.outbox.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false"
})
@Testcontainers
class MerchantMembershipRepositoryIntegrationTest {
    // All writes, including deliberately invalid statements, target only this disposable database.
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired MerchantMembershipRepository memberships;
    @Autowired MerchantRepository merchants;
    @Autowired MerchantService merchantService;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    private static final String ISSUER = "https://identity.example.test/realms/payflow";
    private static final String GRANTER = "platform-grant-subject";
    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00.123456789Z");

    private Merchant merchant() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        return merchants.saveAndFlush(new Merchant(UUID.randomUUID(), "MER-" + suffix.toUpperCase(java.util.Locale.ROOT),
                "Membership Test", suffix + "@example.test", null, NOW.truncatedTo(ChronoUnit.MICROS)));
    }

    private MerchantMembership membership(UUID merchant, String issuer, String subject) {
        return new MerchantMembership(UUID.randomUUID(), merchant, issuer, subject,
                MerchantMembershipRole.MERCHANT_ADMIN, ISSUER, GRANTER, NOW);
    }

    private MerchantMembership saved() {
        return memberships.saveAndFlush(membership(merchant().getId(), ISSUER, UUID.randomUUID().toString()));
    }

    @Test void persistsIdentityRoleMerchantAndAttribution() {
        var original = saved();
        var result = memberships.findById(original.getId()).orElseThrow();
        assertEquals(original.getMerchantId(), result.getMerchantId());
        assertEquals(original.getIssuer(), result.getIssuer());
        assertEquals(original.getSubject(), result.getSubject());
        assertEquals(MerchantMembershipRole.MERCHANT_ADMIN, result.getRole());
        assertTrue(result.isActive());
        assertEquals(0L, result.getVersion());
        assertEquals(NOW.truncatedTo(ChronoUnit.MICROS), result.getCreatedAt());
        assertEquals(result.getCreatedAt(), result.getUpdatedAt());
        assertEquals(ISSUER, result.getGrantedByIssuer());
        assertEquals(GRANTER, result.getGrantedBySubject());
        assertEquals(ISSUER, result.getChangedByIssuer());
        assertEquals(GRANTER, result.getChangedBySubject());
        assertEquals(result.getId(), memberships.findByIssuerAndSubject(result.getIssuer(), result.getSubject()).orElseThrow().getId());
    }

    @Test void supportsMultiplePrincipalsForOneMerchantAndIndexedLookup() {
        var merchant = merchant();
        var first = memberships.saveAndFlush(membership(merchant.getId(), ISSUER, UUID.randomUUID().toString()));
        var second = membership(merchant.getId(), ISSUER, UUID.randomUUID().toString());
        second.changeRole(MerchantMembershipRole.MERCHANT_OPERATOR, ISSUER, "operator-granter", NOW);
        memberships.saveAndFlush(second);
        assertEquals(2, memberships.findByMerchantId(merchant.getId()).size());
        assertTrue(memberships.findByMerchantId(UUID.randomUUID()).isEmpty());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE tablename='merchant_memberships' AND indexname='idx_memberships_merchant'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM databasechangelog WHERE id='004-create-merchant-memberships'", Integer.class));
        assertTrue(memberships.findById(first.getId()).isPresent());
    }

    @Test void duplicatePrincipalCannotBindToSameOrAnotherMerchant() {
        var existing = saved();
        assertThrows(DataIntegrityViolationException.class, () -> memberships.saveAndFlush(
                membership(existing.getMerchantId(), existing.getIssuer(), existing.getSubject())));
        assertThrows(DataIntegrityViolationException.class, () -> memberships.saveAndFlush(
                membership(merchant().getId(), existing.getIssuer(), existing.getSubject())));
        assertEquals(existing.getId(), memberships.findByIssuerAndSubject(existing.getIssuer(), existing.getSubject()).orElseThrow().getId());
    }

    @Test void revokedPrincipalStillCannotBeAssignedToAnotherMerchant() {
        var existing = saved();
        existing.revoke(ISSUER, "revoker", NOW.plusSeconds(1));
        memberships.saveAndFlush(existing);
        assertThrows(DataIntegrityViolationException.class, () -> memberships.saveAndFlush(
                membership(merchant().getId(), existing.getIssuer(), existing.getSubject())));
        assertFalse(memberships.findById(existing.getId()).orElseThrow().isActive());
    }

    @Test void comparesIssuerAndSubjectExactlyWithoutNormalization() {
        var merchant = merchant();
        String subject = "User-" + UUID.randomUUID();
        var exact = memberships.saveAndFlush(membership(merchant.getId(), ISSUER, subject));
        memberships.saveAndFlush(membership(merchant.getId(), ISSUER, subject.toLowerCase(java.util.Locale.ROOT)));
        memberships.saveAndFlush(membership(merchant.getId(), ISSUER + "/", subject));
        memberships.saveAndFlush(membership(merchant.getId(), ISSUER, " " + subject + " "));
        assertEquals(exact.getId(), memberships.findByIssuerAndSubject(ISSUER, subject).orElseThrow().getId());
        assertEquals(4, memberships.findByMerchantId(merchant.getId()).size());
        assertTrue(memberships.findByIssuerAndSubject(ISSUER.toUpperCase(java.util.Locale.ROOT), subject).isEmpty());
    }

    @Test void persistsRevocationActivationRoleChangeAndLastActor() {
        var original = saved();
        original.revoke(ISSUER, "revoker", NOW.plusSeconds(1));
        var revoked = memberships.saveAndFlush(original);
        assertFalse(revoked.isActive());
        assertEquals(1L, revoked.getVersion());
        revoked.activate(ISSUER + "/admin", "reactivator", NOW.plusSeconds(2));
        revoked.changeRole(MerchantMembershipRole.MERCHANT_OPERATOR, ISSUER + "/admin", "role-editor", NOW.plusSeconds(3));
        memberships.saveAndFlush(revoked);
        var result = memberships.findById(original.getId()).orElseThrow();
        assertTrue(result.isActive());
        assertEquals(2L, result.getVersion());
        assertEquals(MerchantMembershipRole.MERCHANT_OPERATOR, result.getRole());
        assertEquals(ISSUER + "/admin", result.getChangedByIssuer());
        assertEquals("role-editor", result.getChangedBySubject());
        assertEquals(ISSUER, result.getGrantedByIssuer());
        assertEquals(GRANTER, result.getGrantedBySubject());
        assertEquals(original.getCreatedAt(), result.getCreatedAt());
        assertEquals(NOW.plusSeconds(3).truncatedTo(ChronoUnit.MICROS), result.getUpdatedAt());
        assertEquals(original.getMerchantId(), result.getMerchantId());
        assertEquals(original.getSubject(), result.getSubject());
    }

    @Test void rejectsMissingMerchantForeignKey() {
        assertThrows(DataIntegrityViolationException.class, () -> memberships.saveAndFlush(
                membership(UUID.randomUUID(), ISSUER, UUID.randomUUID().toString())));
    }

    static Stream<Arguments> invalidDatabaseValues() {
        var blank = Stream.of("issuer", "subject", "granted_by_issuer", "granted_by_subject", "changed_by_issuer", "changed_by_subject")
                .flatMap(column -> Stream.of(Arguments.of(column, ""), Arguments.of(column, " \t\r\n"), Arguments.of(column, null)));
        return Stream.concat(blank, Stream.of(
                Arguments.of("role", "PLATFORM_ADMIN"), Arguments.of("role", null),
                Arguments.of("active", null), Arguments.of("version", -1L), Arguments.of("version", null),
                Arguments.of("created_at", null), Arguments.of("updated_at", null),
                Arguments.of("merchant_uuid", null),
                Arguments.of("issuer", "x".repeat(513)), Arguments.of("subject", "x".repeat(256))));
    }

    @ParameterizedTest @MethodSource("invalidDatabaseValues")
    void enforcesConstraintsForDirectDatabaseWrites(String column, Object invalid) {
        var existing = saved();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE merchant_memberships SET " + column + " = ? WHERE id = ?", invalid, existing.getId()));
        // JDBC statements fail independently; verify the accepted record in a fresh read.
        assertEquals(existing.getSubject(), memberships.findById(existing.getId()).orElseThrow().getSubject());
    }

    @Test void rejectsTimestampRegressionInDatabase() {
        var existing = saved();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE merchant_memberships SET updated_at=created_at-interval '1 second' WHERE id=?", existing.getId()));
    }

    @Test void optimisticLockingPreventsLostRevocation() {
        var existing = saved();
        var first = memberships.findById(existing.getId()).orElseThrow();
        var stale = memberships.findById(existing.getId()).orElseThrow();
        first.revoke(ISSUER, "revoker", NOW.plusSeconds(1));
        memberships.saveAndFlush(first);
        stale.changeRole(MerchantMembershipRole.MERCHANT_OPERATOR, ISSUER, "stale-editor", NOW.plusSeconds(2));
        assertThrows(OptimisticLockingFailureException.class, () -> memberships.saveAndFlush(stale));
        var result = memberships.findById(existing.getId()).orElseThrow();
        assertFalse(result.isActive());
        assertEquals(MerchantMembershipRole.MERCHANT_ADMIN, result.getRole());
        assertEquals("revoker", result.getChangedBySubject());
    }

    @Test void rollsBackMembershipChangesAndAttributionTogether() {
        var existing = saved();
        var tx = new TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            var managed = memberships.findById(existing.getId()).orElseThrow();
            managed.revoke(ISSUER, "rolled-back-actor", NOW.plusSeconds(1));
            memberships.flush();
            throw new IllegalStateException("Simulated application failure");
        }));
        var result = memberships.findById(existing.getId()).orElseThrow();
        assertTrue(result.isActive());
        assertEquals(existing.getVersion(), result.getVersion());
        assertEquals(GRANTER, result.getChangedBySubject());
        UUID newId = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            memberships.saveAndFlush(new MerchantMembership(newId, existing.getMerchantId(), ISSUER,
                    UUID.randomUUID().toString(), MerchantMembershipRole.MERCHANT_ADMIN, ISSUER, GRANTER, NOW));
            throw new IllegalStateException("Simulated grant failure");
        }));
        assertFalse(memberships.existsById(newId));
    }

    @Test void concurrentDuplicateBindingsHaveExactlyOneWinner() throws Exception {
        UUID firstMerchant = merchant().getId();
        UUID secondMerchant = merchant().getId();
        String subject = UUID.randomUUID().toString();
        var barrier = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> first = () -> insertConcurrently(firstMerchant, subject, barrier);
            Callable<Boolean> second = () -> insertConcurrently(secondMerchant, subject, barrier);
            var a = workers.submit(first);
            var b = workers.submit(second);
            assertNotEquals(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        }
        var accepted = memberships.findByIssuerAndSubject(ISSUER, subject).orElseThrow();
        assertTrue(accepted.getMerchantId().equals(firstMerchant) || accepted.getMerchantId().equals(secondMerchant));
        assertEquals(1, memberships.findByMerchantId(firstMerchant).size() + memberships.findByMerchantId(secondMerchant).size());
    }

    private boolean insertConcurrently(UUID merchantId, String subject, CyclicBarrier barrier) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status ->
                    memberships.saveAndFlush(membership(merchantId, ISSUER, subject)));
            return true;
        } catch (DataIntegrityViolationException exception) {
            return false; // Outside the failed transaction; no query inside an aborted transaction.
        }
    }

    @Test void merchantRegistrationDoesNotAutomaticallyGrantMembership() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        var merchant = merchantService.register(new RegisterMerchantRequest("MER-" + suffix,
                "Unbound Business", suffix + "@example.test", null));
        assertTrue(memberships.findByMerchantId(merchant.id()).isEmpty());
        assertEquals(merchant.merchantId(), merchants.findById(merchant.id()).orElseThrow().getMerchantId());
    }

    @Test void entityRejectsInvalidIdentityWithoutNormalizingIt() {
        assertThrows(IllegalArgumentException.class, () -> membership(UUID.randomUUID(), " \t", "subject"));
        assertThrows(IllegalArgumentException.class, () -> membership(UUID.randomUUID(), ISSUER, ""));
        var existing = saved();
        assertThrows(IllegalArgumentException.class, () -> existing.revoke(ISSUER, " ", NOW.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> existing.revoke(ISSUER, "actor", NOW.minusSeconds(1)));
        assertTrue(existing.isActive());
        assertEquals(GRANTER, existing.getChangedBySubject());
    }
}
