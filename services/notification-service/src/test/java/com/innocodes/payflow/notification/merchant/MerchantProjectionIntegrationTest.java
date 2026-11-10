package com.innocodes.payflow.notification.merchant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.innocodes.payflow.contracts.merchant.MerchantWebhookConfigurationEvent;
import com.innocodes.payflow.contracts.payment.PaymentProcessedEvent;
import com.innocodes.payflow.notification.delivery.*;
import com.innocodes.payflow.notification.exception.NotificationConflictException;
import com.innocodes.payflow.notification.service.*;
import com.innocodes.payflow.notification.support.MockMerchant;
import com.innocodes.payflow.webhook.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

@SpringBootTest(
        properties = {
            "spring.kafka.listener.auto-startup=false",
            "spring.kafka.admin.auto-create=false",
            "payflow.webhook.worker-enabled=false",
            "payflow.webhook.allow-local=true"
        })
@ActiveProfiles("dev")
@Testcontainers
class MerchantProjectionIntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:17");

    static final MockMerchant receiver;

    static {
        try {
            receiver = new MockMerchant();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Autowired MerchantProjectionService projection;
    @Autowired ProjectedMerchantDestinationResolver resolver;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SecretReader secrets;
    @Autowired NotificationIngestionService ingestion;
    @Autowired DeliveryStore deliveries;
    @Autowired WebhookHttpClient http;
    private final UUID key = UUID.randomUUID();
    private final String merchant = "MER-PROJECTION";

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM merchant_configuration_receipts");
        jdbc.update("DELETE FROM merchant_webhook_projection");
        jdbc.update("DELETE FROM notification_attempts");
        jdbc.update("DELETE FROM notifications");
        receiver.reset();
        when(secrets.resolve(merchant, key, 1L)).thenReturn(MockMerchant.SECRET);
    }

    @AfterAll
    static void close() {
        receiver.close();
    }

    @Test
    void shouldPersistReplayAndReloadConfigurationWithoutStaticFallback() {
        var e = event(1, key, 1L, true, "ACTIVE", Instant.now());
        projection.apply(e);
        projection.apply(e);
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM merchant_configuration_receipts", Integer.class));
        assertFalse(
                jdbc.queryForObject(
                        "SELECT secret_ready FROM merchant_webhook_projection", Boolean.class));
        var fresh =
                new ProjectedMerchantDestinationResolver(
                        jdbc,
                        secrets,
                        new com.innocodes.payflow.notification.config.WebhookProperties(
                                8,
                                java.time.Duration.ofSeconds(2),
                                java.time.Duration.ofSeconds(5),
                                java.time.Duration.ofSeconds(10),
                                java.time.Duration.ofSeconds(30),
                                java.time.Duration.ofSeconds(5),
                                java.time.Duration.ofHours(1),
                                true,
                                java.util.Map.of()),
                        java.time.Duration.ofMinutes(5));
        assertEquals(key, fresh.resolve(merchant).secretReference());
        assertTrue(
                jdbc.queryForObject(
                        "SELECT secret_ready FROM merchant_webhook_projection", Boolean.class));
    }

    @Test
    void shouldRejectStaleVersionsAndConflictingSameVersionAndEventIdentity() {
        var e = event(2, key, 1L, true, "ACTIVE", Instant.now());
        projection.apply(e);
        projection.apply(event(1, null, null, false, "PENDING", Instant.now()));
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT config_version FROM merchant_webhook_projection", Long.class));
        assertThrows(
                NotificationConflictException.class,
                () -> projection.apply(event(2, key, 1L, false, "ACTIVE", Instant.now())));
        var reused =
                new MerchantWebhookConfigurationEvent(
                        e.eventId(),
                        merchant,
                        3,
                        e.destinationUrl(),
                        false,
                        "ACTIVE",
                        key,
                        1L,
                        e.occurredAt());
        assertThrows(NotificationConflictException.class, () -> projection.apply(reused));
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT count(*) FROM merchant_configuration_receipts", Integer.class));
    }

    @Test
    void shouldFenceOldConfigurationWhenNewSecretIsMissingThenRecover() {
        projection.apply(event(1, key, 1L, true, "ACTIVE", Instant.now()));
        resolver.resolve(merchant);
        UUID next = UUID.randomUUID();
        when(secrets.resolve(merchant, next, 2)).thenThrow(new SecretUnavailableException());
        projection.apply(event(2, next, 2L, true, "ACTIVE", Instant.now()));
        assertEquals(
                "SIGNING_KEY_UNAVAILABLE",
                assertThrows(
                                DestinationUnavailableException.class,
                                () -> resolver.resolve(merchant))
                        .getMessage());
        assertFalse(
                jdbc.queryForObject(
                        "SELECT secret_ready FROM merchant_webhook_projection", Boolean.class));
        doReturn("another-secure-key-0123456789abcdef").when(secrets).resolve(merchant, next, 2);
        assertEquals(2L, resolver.resolve(merchant).secretVersion());
    }

    @Test
    void shouldBlockSuspensionClosureAndDisabledDelivery() {
        projection.apply(event(1, key, 1L, true, "ACTIVE", Instant.now()));
        projection.apply(event(2, key, 1L, true, "SUSPENDED", Instant.now()));
        assertEquals(
                "WEBHOOK_DISABLED",
                assertThrows(
                                DestinationUnavailableException.class,
                                () -> resolver.resolve(merchant))
                        .getMessage());
        projection.apply(event(3, key, 1L, false, "ACTIVE", Instant.now()));
        assertThrows(DestinationUnavailableException.class, () -> resolver.resolve(merchant));
        projection.apply(event(4, key, 1L, true, "CLOSED", Instant.now()));
        assertFalse(
                assertThrows(
                                DestinationUnavailableException.class,
                                () -> resolver.resolve(merchant))
                        .retryable());
    }

    @Test
    void shouldExpireStaleProjectionAndRefreshOnlyWithCurrentSnapshot() {
        projection.apply(event(2, key, 1L, true, "ACTIVE", Instant.now().minusSeconds(600)));
        assertEquals(
                "CONFIGURATION_STALE",
                assertThrows(
                                DestinationUnavailableException.class,
                                () -> resolver.resolve(merchant))
                        .getMessage());
        projection.apply(event(1, key, 1L, true, "ACTIVE", Instant.now()));
        assertThrows(DestinationUnavailableException.class, () -> resolver.resolve(merchant));
        projection.apply(event(2, key, 1L, true, "ACTIVE", Instant.now()));
        assertEquals(key, resolver.resolve(merchant).secretReference());
    }

    @Test
    void shouldUseLatestKeyForQueuedDeliveryAndVerifyVersionedSignature() {
        projection.apply(event(1, key, 1L, true, "ACTIVE", Instant.now()));
        var payment =
                new PaymentProcessedEvent(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        merchant,
                        "REF",
                        new java.math.BigDecimal("100.00"),
                        "NGN",
                        "SUCCESSFUL",
                        null,
                        Instant.now());
        ingestion.process(payment);
        var claim = deliveries.claim().orElseThrow();
        UUID next = UUID.randomUUID();
        String nextSecret = "a-new-version-secure-secret-0123456789";
        when(secrets.resolve(merchant, next, 2)).thenReturn(nextSecret);
        projection.apply(event(2, next, 2L, true, "ACTIVE", Instant.now()));
        var result = http.deliver(claim);
        assertTrue(result.delivered());
        assertTrue(deliveries.complete(claim, result));
        var request = receiver.received.getFirst();
        assertEquals("2", request.header("PayFlow-Webhook-Key-Version"));
        assertEquals(next.toString(), request.header("PayFlow-Webhook-Key-Id"));
        assertEquals(
                WebhookSigner.sign(
                        nextSecret, request.header("PayFlow-Webhook-Timestamp"), request.body()),
                request.header("PayFlow-Webhook-Signature"));
    }

    @Test
    void shouldSerializeConcurrentFirstDeliveryAndPreserveHighestVersion() throws Exception {
        try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var a =
                    workers.submit(
                            () ->
                                    projection.apply(
                                            event(2, key, 1L, true, "ACTIVE", Instant.now())));
            var b =
                    workers.submit(
                            () ->
                                    projection.apply(
                                            event(1, key, 1L, true, "ACTIVE", Instant.now())));
            a.get(5, java.util.concurrent.TimeUnit.SECONDS);
            b.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT config_version FROM merchant_webhook_projection", Long.class));
    }

    private MerchantWebhookConfigurationEvent event(
            long version,
            UUID reference,
            Long keyVersion,
            boolean enabled,
            String status,
            Instant time) {
        return new MerchantWebhookConfigurationEvent(
                UUID.randomUUID(),
                merchant,
                version,
                receiver.url().toString(),
                enabled,
                status,
                reference,
                keyVersion,
                time);
    }
}
