package com.innocodes.payflow.notification.service;

import com.innocodes.payflow.contracts.payment.PaymentProcessedEvent;
import com.innocodes.payflow.notification.delivery.*;
import com.innocodes.payflow.notification.exception.NotificationConflictException;
import com.innocodes.payflow.notification.repository.NotificationRepository;
import com.innocodes.payflow.notification.support.MockMerchant;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false",
        "payflow.webhook.worker-enabled=false", "payflow.webhook.allow-local=true", "payflow.webhook.max-attempts=3",
        "payflow.webhook.connect-timeout=100ms", "payflow.webhook.response-timeout=100ms",
        "payflow.webhook.request-timeout=300ms", "payflow.webhook.lease-duration=2s",
        "payflow.webhook.initial-backoff=100ms", "payflow.webhook.max-backoff=1s"})
@ActiveProfiles("dev")
@Testcontainers
class NotificationDeliveryIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");
    static final MockMerchant merchant;
    static {
        try { merchant = new MockMerchant(); } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("payflow.webhook.merchants[MER-TEST].url", () -> merchant.url().toString());
        r.add("payflow.webhook.merchants[MER-TEST].secret", () -> MockMerchant.SECRET);
    }

    @Autowired NotificationIngestionService ingestion;
    @MockitoSpyBean NotificationRepository notifications;
    @Autowired DeliveryStore store;
    @Autowired DestinationPolicy destinationPolicy;
    @Autowired com.innocodes.payflow.notification.config.WebhookProperties webhookProperties;
    @Autowired WebhookHttpClient http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @AfterAll
    static void closeReceiver() { merchant.close(); }

    @BeforeEach
    void prepareIsolatedTestDatabase() {
        // These rows belong exclusively to this disposable Testcontainers database.
        jdbc.update("DELETE FROM notification_attempts");
        jdbc.update("DELETE FROM notifications");
        merchant.reset();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESSFUL", "FAILED"})
    void shouldIngestCanonicalReplaysWithoutLosingDeliveryState(String status) {
        var event = event(status);
        ingestion.process(event);
        var claim = store.claim().orElseThrow();
        var replay = new PaymentProcessedEvent(event.eventId(), event.paymentId(), event.merchantId(), event.reference(),
                new BigDecimal("100.0"), event.currency(), event.status(), event.failureReason(),
                event.processedAt().truncatedTo(ChronoUnit.MICROS));
        ingestion.process(replay);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM notifications", Integer.class));
        assertEquals(claim.claimToken(), notifications.findById(event.eventId()).orElseThrow().claimToken());
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "paymentId", "merchantId", "reference", "amount", "currency", "status", "reason", "timestamp"})
    void shouldRejectConflictingResults(String field) {
        var e = event("SUCCESSFUL");
        ingestion.process(e);
        var conflict = new PaymentProcessedEvent(field.equals("eventId") ? UUID.randomUUID() : e.eventId(),
                field.equals("paymentId") ? UUID.randomUUID() : e.paymentId(),
                field.equals("merchantId") ? "OTHER" : e.merchantId(), field.equals("reference") ? "OTHER" : e.reference(),
                field.equals("amount") ? new BigDecimal("101.00") : e.amount(),
                field.equals("currency") ? "USD" : e.currency(), field.equals("status") ? "FAILED" : e.status(),
                field.equals("reason") ? "Changed" : e.failureReason(),
                field.equals("timestamp") ? e.processedAt().plusSeconds(1) : e.processedAt());
        assertThrows(NotificationConflictException.class, () -> ingestion.process(conflict));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM notifications", Integer.class));
    }

    @Test
    void shouldRollBackIngestionFailureAndAllowFreshRetry() {
        var e = event("SUCCESSFUL");
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(call -> {
            call.callRealMethod();
            if (fail.getAndSet(false)) throw new DataAccessResourceFailureException("Injected failure after insert");
            return null;
        }).when(notifications).insert(eq(e.eventId()), any(), anyString(), anyString(), anyString(), anyString());
        assertThrows(DataAccessResourceFailureException.class, () -> ingestion.process(e));
        assertTrue(notifications.findById(e.eventId()).isEmpty());
        ingestion.process(e);
        assertTrue(notifications.findById(e.eventId()).isPresent());
    }

    @Test
    void shouldDeliverRealSignedHttpOutsideTransactionsAndRecordHistory() {
        var e = event("SUCCESSFUL");
        ingestion.process(e);
        var claim = store.claim().orElseThrow();
        // A separate connection observes the committed claim before the network call.
        assertEquals("IN_FLIGHT", notifications.findById(e.eventId()).orElseThrow().deliveryState());
        var result = http.deliver(claim);
        assertTrue(result.delivered());
        assertTrue(store.complete(claim, result));
        var saved = notifications.findById(e.eventId()).orElseThrow();
        assertEquals("DELIVERED", saved.deliveryState());
        assertEquals(1, saved.attemptCount());
        assertNotNull(saved.deliveredAt());
        var request = merchant.received.getFirst();
        assertEquals("POST", request.method());
        assertEquals(saved.webhookPayload(), request.body());
        assertEquals(e.eventId().toString(), request.header("PayFlow-Webhook-Id"));
        assertEquals(WebhookSigner.sign(MockMerchant.SECRET, request.header("PayFlow-Webhook-Timestamp"), request.body()),
                request.header("PayFlow-Webhook-Signature"));
        var history = jdbc.queryForMap("SELECT * FROM notification_attempts WHERE event_id=?", e.eventId());
        assertEquals("DELIVERED", history.get("outcome"));
        assertEquals(204, history.get("response_status"));
        assertNotNull(history.get("duration_ms"));
        assertNotNull(history.get("finished_at"));
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 400, 401, 408, 429, 500, 503})
    void shouldApplyHttpRetryPolicyWithoutFollowingRedirects(int status) {
        merchant.status = status;
        var e = event("SUCCESSFUL");
        ingestion.process(e);
        Instant before = Instant.now();
        new DeliveryWorker(store, http, new SimpleMeterRegistry()).runOnce();
        var saved = notifications.findById(e.eventId()).orElseThrow();
        assertEquals(status == 408 || status == 429 || status >= 500 ? "PENDING" : "FAILED", saved.deliveryState());
        assertEquals("HTTP_" + status, saved.lastFailure());
        assertEquals(1, merchant.received.size());
        assertTrue(saved.nextAttemptAt().isAfter(before));
    }

    @Test
    void shouldBoundTimeoutAndRecordRetryableFailure() {
        merchant.delayMillis = 1000;
        var e = event("FAILED");
        ingestion.process(e);
        long start = System.nanoTime();
        new DeliveryWorker(store, http, new SimpleMeterRegistry()).runOnce();
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(2)) < 0);
        var saved = notifications.findById(e.eventId()).orElseThrow();
        assertEquals("PENDING", saved.deliveryState());
        assertTrue(saved.lastFailure().equals("TIMEOUT") || saved.lastFailure().equals("NETWORK_ERROR"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM notification_attempts WHERE outcome='FAILED'", Integer.class));
    }

    @Test
    void shouldStopAfterBoundedAttempts() {
        merchant.status = 503;
        var e = event("SUCCESSFUL");
        ingestion.process(e);
        var worker = new DeliveryWorker(store, http, new SimpleMeterRegistry());
        for (int i = 0; i < 3; i++) {
            worker.runOnce();
            jdbc.update("UPDATE notifications SET next_attempt_at=clock_timestamp() - interval '1 second' WHERE event_id=?", e.eventId());
        }
        assertEquals("FAILED", notifications.findById(e.eventId()).orElseThrow().deliveryState());
        assertEquals(3, merchant.received.size());
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM notification_attempts WHERE outcome='FAILED'", Integer.class));
        assertTrue(store.claim().isEmpty());
    }

    @Test
    void shouldRecoverExpiredLeaseAndFenceStaleWorker() {
        var e = event("SUCCESSFUL");
        ingestion.process(e);
        var first = store.claim().orElseThrow();
        expire(e.eventId());
        var second = store.claim().orElseThrow();
        assertNotEquals(first.claimToken(), second.claimToken());
        assertEquals(2, second.attemptCount());
        assertFalse(store.complete(first, new DeliveryResult(204, 1, null, false)));
        assertTrue(store.complete(second, new DeliveryResult(204, 1, null, false)));
        assertEquals("LEASE_EXPIRED", jdbc.queryForObject("SELECT outcome FROM notification_attempts WHERE id=?", String.class, first.claimToken()));
        assertEquals("DELIVERED", notifications.findById(e.eventId()).orElseThrow().deliveryState());
    }

    @Test
    void shouldTerminateAfterRepeatedCrashesIncludingFinalLease() {
        var e = event("SUCCESSFUL");
        ingestion.process(e);
        for (int i = 0; i < 3; i++) {
            assertTrue(store.claim().isPresent());
            expire(e.eventId());
        }
        assertTrue(store.claim().isEmpty());
        assertEquals("FAILED", notifications.findById(e.eventId()).orElseThrow().deliveryState());
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM notification_attempts WHERE outcome='LEASE_EXPIRED'", Integer.class));
    }

    @Test
    void shouldSkipLockedRowsAcrossConcurrentWorkers() throws Exception {
        var first = event("SUCCESSFUL");
        var second = event("FAILED");
        ingestion.process(first);
        ingestion.process(second);
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var locker = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                jdbc.queryForObject("SELECT event_id FROM notifications WHERE event_id=? FOR UPDATE", UUID.class, first.eventId());
                locked.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new IllegalStateException(e); }
                return null;
            }));
            try {
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                var claimed = executor.submit(() -> store.claim().orElseThrow()).get(2, TimeUnit.SECONDS);
                assertEquals(second.eventId(), claimed.eventId());
                assertEquals(1, claimed.attemptCount());
            } finally { release.countDown(); }
            locker.get(5, TimeUnit.SECONDS);
        }
        assertEquals(first.eventId(), store.claim().orElseThrow().eventId());
        assertTrue(store.claim().isEmpty());
    }

    @Test
    void shouldRollBackAttemptCompletionOnDatabaseFailure() {
        var e = event("SUCCESSFUL");
        ingestion.process(e);
        var claim = store.claim().orElseThrow();
        assertThrows(DataIntegrityViolationException.class,
                () -> store.complete(claim, new DeliveryResult(204, -1, null, false)));
        assertEquals("IN_FLIGHT", notifications.findById(e.eventId()).orElseThrow().deliveryState());
        assertEquals("STARTED", jdbc.queryForObject("SELECT outcome FROM notification_attempts WHERE id=?", String.class, claim.claimToken()));
    }

    @Test
    void shouldRetainUnknownMerchantForBoundedRetryWithoutNetwork() {
        var e = event("FAILED");
        ingestion.process(new PaymentProcessedEvent(e.eventId(), e.paymentId(), "UNKNOWN", e.reference(), e.amount(),
                e.currency(), e.status(), e.failureReason(), e.processedAt()));
        new DeliveryWorker(store, http, new SimpleMeterRegistry()).runOnce();
        assertEquals("DESTINATION_NOT_CONFIGURED", notifications.findById(e.eventId()).orElseThrow().lastFailure());
        assertTrue(merchant.received.isEmpty());
    }

    @Test
    void shouldRollBackConcurrentDuplicateInsertAndAcceptFreshRetry() throws Exception {
        var e = event("SUCCESSFUL");
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            Object value = call.callRealMethod();
            if (reads.incrementAndGet() <= 2) barrier.await(5, TimeUnit.SECONDS);
            return value;
        }).when(notifications).findByPaymentId(e.paymentId());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Throwable> action = () -> {
                try { ingestion.process(e); return null; }
                catch (RuntimeException failure) { return failure; }
            };
            var first = executor.submit(action);
            var second = executor.submit(action);
            Throwable a = first.get(10, TimeUnit.SECONDS), b = second.get(10, TimeUnit.SECONDS);
            assertTrue((a == null) != (b == null));
            assertInstanceOf(DataIntegrityViolationException.class, a == null ? b : a);
        }
        ingestion.process(e);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM notifications", Integer.class));
    }

    @Test
    void shouldBlockMetadataDestinationBeforeAnyHttpRequest() {
        var e = event("SUCCESSFUL");
        ingestion.process(e);
        var claim = store.claim().orElseThrow();
        try (var client = new WebhookHttpClient(reference -> new MerchantWebhookDestinationResolver.Destination(
                java.net.URI.create("https://169.254.169.254/latest/meta-data"), MockMerchant.SECRET),
                destinationPolicy, webhookProperties)) {
            var result = client.deliver(claim);
            assertEquals("UNSAFE_DESTINATION", result.failure());
            assertFalse(result.retryable());
            assertTrue(store.complete(claim, result));
        }
        assertEquals("FAILED", notifications.findById(e.eventId()).orElseThrow().deliveryState());
        assertTrue(merchant.received.isEmpty());
    }

    @Test
    void shouldRecordConnectionFailureWithoutLeakingDestinationOrSecret() throws Exception {
        java.net.URI closed;
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            closed = java.net.URI.create("http://127.0.0.1:" + socket.getLocalPort() + "/webhook?token=sensitive");
        }
        var e = event("SUCCESSFUL");
        ingestion.process(e);
        var claim = store.claim().orElseThrow();
        try (var client = new WebhookHttpClient(reference -> new MerchantWebhookDestinationResolver.Destination(
                closed, MockMerchant.SECRET), destinationPolicy, webhookProperties)) {
            var result = client.deliver(claim);
            assertEquals("NETWORK_ERROR", result.failure());
            assertTrue(result.retryable());
            assertTrue(store.complete(claim, result));
        }
        assertEquals("NETWORK_ERROR", notifications.findById(e.eventId()).orElseThrow().lastFailure());
    }

    private void expire(UUID eventId) {
        jdbc.update("UPDATE notifications SET lease_until=clock_timestamp() - interval '1 second' WHERE event_id=?", eventId);
    }

    private PaymentProcessedEvent event(String status) {
        return new PaymentProcessedEvent(UUID.randomUUID(), UUID.randomUUID(), "MER-TEST", "REF-" + UUID.randomUUID(),
                new BigDecimal("100.00"), "NGN", status, status.equals("FAILED") ? "Declined" : null,
                Instant.parse("2026-11-10T12:00:00.123456789Z"));
    }
}
