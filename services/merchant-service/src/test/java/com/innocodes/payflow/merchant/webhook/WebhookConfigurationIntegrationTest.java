package com.innocodes.payflow.merchant.webhook;

import static org.junit.jupiter.api.Assertions.*;

import com.innocodes.payflow.merchant.dto.request.RegisterMerchantRequest;
import com.innocodes.payflow.merchant.exception.MerchantConflictException;
import com.innocodes.payflow.merchant.service.MerchantService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "payflow.webhook.allow-local=true",
            "spring.kafka.admin.auto-create=false",
            "payflow.merchant.outbox.enabled=false"
        })
@ActiveProfiles("dev")
@Testcontainers
class WebhookConfigurationIntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:17");

    static final java.nio.file.Path secretsDirectory;
    static final java.nio.file.Path master;

    static {
        try {
            var dir = java.nio.file.Files.createTempDirectory("payflow-merchant-secrets-");
            secretsDirectory = dir.resolve("keys");
            java.nio.file.Files.createDirectory(
                    secretsDirectory,
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                            java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
            master = dir.resolve("master");
            byte[] key = new byte[32];
            new java.security.SecureRandom().nextBytes(key);
            java.nio.file.Files.write(master, key);
            java.nio.file.Files.setPosixFilePermissions(
                    master, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @org.springframework.test.context.DynamicPropertySource
    static void secretProperties(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("payflow.secrets.adapter", () -> "local-files");
        r.add("payflow.secrets.directory", () -> secretsDirectory.toString());
        r.add("payflow.secrets.master-key-file", () -> master.toString());
        r.add("payflow.secrets.provisioning-enabled", () -> true);
    }

    @Autowired WebhookSecretService secrets;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager tx;
    @Autowired tools.jackson.databind.json.JsonMapper mapper;

    @Autowired MerchantService merchants;
    @Autowired WebhookConfigurationService webhooks;

    @org.junit.jupiter.api.BeforeEach
    void clearIsolatedOutbox() {
        jdbc.update("DELETE FROM merchant_config_outbox");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldBoundPublisherFailuresAndReplayWithStableIdentities() {
        var m = merchant();
        webhooks.update(
                m.id(),
                new UpdateWebhookRequest(0L, "http://localhost:8090/hook", false, null, null));
        org.springframework.kafka.core.KafkaTemplate<Object, Object> kafka =
                org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class);
        org.mockito.Mockito.when(
                        kafka.send(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.any()))
                .thenReturn(
                        java.util.concurrent.CompletableFuture.failedFuture(
                                new IllegalStateException("Outage")));
        var publisher = new MerchantOutboxPublisher(jdbc, kafka, tx, webhooks);
        String original =
                jdbc.queryForObject("SELECT payload FROM merchant_config_outbox", String.class);
        for (int i = 0; i < 8; i++) {
            publisher.publishOne();
            jdbc.update(
                    "UPDATE merchant_config_outbox SET next_attempt_at=clock_timestamp()-interval"
                        + " '1 second'");
        }
        assertEquals(
                "DEAD",
                jdbc.queryForObject("SELECT state FROM merchant_config_outbox", String.class));
        assertEquals(
                8,
                jdbc.queryForObject("SELECT attempts FROM merchant_config_outbox", Integer.class));
        org.mockito.Mockito.verify(kafka, org.mockito.Mockito.times(8))
                .send(MerchantOutboxPublisher.TOPIC, m.merchantId(), original);
        webhooks.replay(m.id());
        org.mockito.Mockito.when(
                        kafka.send(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        publisher.publishOne();
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM merchant_config_outbox WHERE state='PUBLISHED'",
                        Integer.class));
    }

    @Test
    void shouldSerializeConcurrentConfigUpdates() throws Exception {
        var m = merchant();
        webhooks.update(
                m.id(),
                new UpdateWebhookRequest(0L, "http://localhost:8090/hook", false, null, null));
        try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var barrier = new java.util.concurrent.CyclicBarrier(2);
            java.util.concurrent.Callable<Boolean> update =
                    () -> {
                        barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                        try {
                            webhooks.update(
                                    m.id(),
                                    new UpdateWebhookRequest(
                                            1L, "http://localhost:8091/hook", false, null, null));
                            return true;
                        } catch (MerchantConflictException conflict) {
                            return false;
                        }
                    };
            var a = workers.submit(update);
            var b = workers.submit(update);
            assertNotEquals(
                    a.get(10, java.util.concurrent.TimeUnit.SECONDS),
                    b.get(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(2, webhooks.get(m.id()).version());
    }

    @Test
    void shouldPersistUpdateAndRejectStaleVersion() {
        var m = merchant();
        var first =
                webhooks.update(
                        m.id(),
                        new UpdateWebhookRequest(
                                0L, "http://127.0.0.1:8090/hook", false, null, null));
        assertEquals(1, first.version());
        assertEquals(first, webhooks.get(m.id()));
        var second =
                webhooks.update(
                        m.id(),
                        new UpdateWebhookRequest(
                                1L, "http://127.0.0.1:8091/hook", false, null, null));
        assertEquals(2, second.version());
        assertThrows(
                MerchantConflictException.class,
                () ->
                        webhooks.update(
                                m.id(),
                                new UpdateWebhookRequest(
                                        1L, first.destinationUrl(), false, null, null)));
    }

    @Test
    void shouldEnforceLifecycleAndRequireKey() {
        var m = merchant();
        assertThrows(
                MerchantConflictException.class,
                () ->
                        webhooks.update(
                                m.id(),
                                new UpdateWebhookRequest(
                                        0L, "http://localhost:8090/hook", true, null, null)));
        webhooks.update(
                m.id(),
                new UpdateWebhookRequest(0L, "http://localhost:8090/hook", false, null, null));
        merchants.close(m.id(), m.version());
        assertThrows(
                MerchantConflictException.class,
                () ->
                        webhooks.update(
                                m.id(),
                                new UpdateWebhookRequest(
                                        1L, "http://localhost:8090/hook", false, null, null)));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://169.254.169.254/hook",
                "http://10.0.0.1/hook",
                "file:///tmp/key",
                "https://user:pass@example.com/hook"
            })
    void shouldRejectUnsafeDestinations(String url) {
        var m = merchant();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        webhooks.update(
                                m.id(), new UpdateWebhookRequest(0L, url, false, null, null)));
    }

    @Test
    void shouldProvisionOnceActivateAndRotateWithoutSecretDisclosure() throws Exception {
        var m = merchant();
        // Exercise the development controller contract directly; real HTTP provisioning
        // is deliberately denied until tenant authorization is implemented.
        var localRequest = new org.springframework.mock.web.MockHttpServletRequest();
        localRequest.setRemoteAddr("127.0.0.1");
        var response = new DevelopmentSecretController(secrets).provision(m.id(), localRequest);
        assertEquals(201, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        var first = java.util.Objects.requireNonNull(response.getBody());
        merchants.activate(m.id(), m.version());
        var config =
                webhooks.update(
                        m.id(),
                        new UpdateWebhookRequest(
                                0L,
                                "http://localhost:8090/hook",
                                true,
                                first.reference(),
                                first.version()));
        var next = secrets.provision(m.id());
        assertEquals(first.version() + 1, next.version());
        assertEquals(
                first.reference(),
                webhooks.get(m.id())
                        .secretRef()); // Provisioning alone never rotates the active key.
        var rotated =
                webhooks.update(
                        m.id(),
                        new UpdateWebhookRequest(
                                config.version(),
                                config.destinationUrl(),
                                true,
                                next.reference(),
                                next.version()));
        assertEquals(2, rotated.version());
        assertFalse(mapper.writeValueAsString(webhooks.get(m.id())).contains(first.secret()));
        assertFalse(mapper.writeValueAsString(webhooks.get(m.id())).contains(next.secret()));
        for (var payload :
                jdbc.queryForList("SELECT payload FROM merchant_config_outbox", String.class)) {
            assertFalse(payload.contains(first.secret()));
            assertFalse(payload.contains(next.secret()));
        }
        assertThrows(
                MerchantConflictException.class,
                () ->
                        webhooks.update(
                                m.id(),
                                new UpdateWebhookRequest(
                                        2L,
                                        config.destinationUrl(),
                                        true,
                                        first.reference(),
                                        first.version())));
    }

    @Test
    void shouldRejectForeignKeyAndPublishLifecycleChangesAtomically() {
        var m = merchant();
        var other = merchant();
        var foreign = secrets.provision(other.id());
        merchants.activate(m.id(), 0L);
        assertThrows(
                MerchantConflictException.class,
                () ->
                        webhooks.update(
                                m.id(),
                                new UpdateWebhookRequest(
                                        0L,
                                        "http://localhost:8090/hook",
                                        true,
                                        foreign.reference(),
                                        foreign.version())));
        var key = secrets.provision(m.id());
        webhooks.update(
                m.id(),
                new UpdateWebhookRequest(
                        0L, "http://localhost:8090/hook", true, key.reference(), key.version()));
        merchants.suspend(m.id(), 1L);
        assertEquals(2, webhooks.get(m.id()).version());
        assertThrows(MerchantConflictException.class, () -> secrets.provision(m.id()));
        String payload =
                jdbc.queryForObject(
                        "SELECT payload FROM merchant_config_outbox WHERE merchant_id=? ORDER BY"
                            + " config_version DESC LIMIT 1",
                        String.class,
                        m.merchantId());
        assertEquals("SUSPENDED", mapper.readTree(payload).get("merchantStatus").asText());
        var disabled =
                webhooks.update(
                        m.id(),
                        new UpdateWebhookRequest(
                                2L,
                                "http://localhost:8090/hook",
                                false,
                                key.reference(),
                                key.version()));
        assertEquals(3, disabled.version());
    }

    @Test
    void shouldRollBackConfigurationAndOutboxTogether() {
        var m = merchant();
        assertThrows(
                IllegalStateException.class,
                () ->
                        new org.springframework.transaction.support.TransactionTemplate(tx)
                                .executeWithoutResult(
                                        status -> {
                                            webhooks.update(
                                                    m.id(),
                                                    new UpdateWebhookRequest(
                                                            0L,
                                                            "http://localhost:8090/hook",
                                                            false,
                                                            null,
                                                            null));
                                            throw new IllegalStateException("Injected failure");
                                        }));
        assertTrue(webhooks.find(m.id()).isEmpty());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM merchant_config_outbox WHERE merchant_id=?",
                        Integer.class,
                        m.merchantId()));
    }

    @Test
    void shouldReplayCurrentVersionWithoutChangingKeyOrVersion() {
        var m = merchant();
        var c =
                webhooks.update(
                        m.id(),
                        new UpdateWebhookRequest(
                                0L, "http://localhost:8090/hook", false, null, null));
        webhooks.replay(m.id());
        assertEquals(c, webhooks.get(m.id()));
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT count(*) FROM merchant_config_outbox WHERE merchant_id=?",
                        Integer.class,
                        m.merchantId()));
    }

    private com.innocodes.payflow.merchant.dto.response.MerchantResponse merchant() {
        String id = UUID.randomUUID().toString();
        return merchants.register(
                new RegisterMerchantRequest("MER-" + id, "Test", id + "@example.com", null));
    }
}
