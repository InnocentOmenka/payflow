package com.innocodes.payflow.payment.service;

import com.innocodes.payflow.payment.dto.request.CreatePaymentRequest;
import com.innocodes.payflow.payment.dto.response.PaymentResponse;
import com.innocodes.payflow.payment.exception.IdempotencyKeyConflictException;
import com.innocodes.payflow.payment.exception.PaymentReferenceConflictException;
import com.innocodes.payflow.payment.repository.IdempotencyRecordRepository;
import com.innocodes.payflow.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.innocodes.payflow.payment.entity.OutboxEvent;
import com.innocodes.payflow.payment.enums.OutboxEventStatus;
import com.innocodes.payflow.payment.repository.OutboxEventRepository;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = {
        "payflow.outbox.publisher.enabled=false"
})
@Testcontainers
class PaymentServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17")
                    .withDatabaseName("payflow_test")
                    .withUsername("test_user")
                    .withPassword("test_password");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @BeforeEach
    void cleanDatabase() {
        outboxEventRepository.deleteAll();
        idempotencyRecordRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    @Test
    void shouldPersistPaymentAndIdempotencyRecord() {
        // Arrange
        CreatePaymentRequest request = new CreatePaymentRequest(
                "MER-TEST-001",
                "ORDER-TEST-001",
                new BigDecimal("50000.00"),
                "NGN",
                "Integration test payment"
        );

        // Act
        PaymentResponse response = paymentService.initiatePayment(
                request,
                "integration-key-001"
        );

        // Assert
        assertEquals(1, outboxEventRepository.count());

        OutboxEvent outboxEvent = outboxEventRepository.findAll()
                .getFirst();

        assertEquals("PAYMENT", outboxEvent.getAggregateType());
        assertEquals(response.paymentId(), outboxEvent.getAggregateId().toString());
        assertEquals("PaymentInitiated", outboxEvent.getEventType());
        assertEquals(OutboxEventStatus.PENDING, outboxEvent.getStatus());
        assertNotNull(outboxEvent.getPayload());
        assertNotNull(outboxEvent.getCreatedAt());
    }

    @Test
    void shouldReturnSamePaymentWhenRequestIsRetried() {
        // Arrange
        CreatePaymentRequest request = new CreatePaymentRequest(
                "MER-TEST-002",
                "ORDER-TEST-002",
                new BigDecimal("75000.00"),
                "NGN",
                "Retry integration test"
        );

        // Act
        PaymentResponse firstResponse = paymentService.initiatePayment(
                request,
                "integration-key-002"
        );

        PaymentResponse retryResponse = paymentService.initiatePayment(
                request,
                "integration-key-002"
        );

        // Assert
        assertEquals(firstResponse.paymentId(), retryResponse.paymentId());
        assertEquals(firstResponse.createdAt(), retryResponse.createdAt());
        assertEquals(1, paymentRepository.count());
        assertEquals(1, idempotencyRecordRepository.count());
        assertEquals(1, outboxEventRepository.count());
    }

    @Test
    void shouldRejectDifferentRequestUsingSameIdempotencyKey() {
        // Arrange
        CreatePaymentRequest originalRequest = new CreatePaymentRequest(
                "MER-TEST-003",
                "ORDER-TEST-003",
                new BigDecimal("50000.00"),
                "NGN",
                "Original payment"
        );

        CreatePaymentRequest differentRequest = new CreatePaymentRequest(
                "MER-TEST-003",
                "ORDER-TEST-003",
                new BigDecimal("90000.00"),
                "NGN",
                "Changed payment"
        );

        // Act
        paymentService.initiatePayment(
                originalRequest,
                "integration-key-003"
        );

        // Assert
        assertThrows(
                IdempotencyKeyConflictException.class,
                () -> paymentService.initiatePayment(
                        differentRequest,
                        "integration-key-003"
                )
        );

        assertEquals(1, paymentRepository.count());
        assertEquals(1, idempotencyRecordRepository.count());
    }


    @Test
    void shouldHandleConcurrentRequestsWithSameIdempotencyKey()
            throws Exception {

        // Arrange
        int numberOfRequests = 8;

        CreatePaymentRequest request = new CreatePaymentRequest(
                "MER-CONCURRENT-001",
                "ORDER-CONCURRENT-001",
                new BigDecimal("100000.00"),
                "NGN",
                "Concurrent payment test"
        );

        ExecutorService executor =
                Executors.newFixedThreadPool(numberOfRequests);

        CountDownLatch ready = new CountDownLatch(numberOfRequests);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<PaymentResponse>> futures = new ArrayList<>();

        try {
            // Act: submit all requests before releasing them
            for (int i = 0; i < numberOfRequests; i++) {

                Future<PaymentResponse> future = executor.submit(() -> {
                    ready.countDown();

                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "Timed out waiting to start concurrent requests"
                        );
                    }

                    return paymentService.initiatePayment(
                            request,
                            "concurrent-key-001"
                    );
                });

                futures.add(future);
            }

            // Wait until all worker threads are ready
            assertTrue(
                    ready.await(10, TimeUnit.SECONDS),
                    "Not all requests became ready"
            );

            // Release all requests together
            start.countDown();

            // Assert: every request receives the same payment
            PaymentResponse firstResponse =
                    futures.get(0).get(30, TimeUnit.SECONDS);

            assertNotNull(firstResponse.paymentId());

            for (Future<PaymentResponse> future : futures) {
                PaymentResponse response =
                        future.get(30, TimeUnit.SECONDS);

                assertEquals(
                        firstResponse.paymentId(),
                        response.paymentId(),
                        "All requests should return the same payment ID"
                );
            }

            assertEquals(1, paymentRepository.count());
            assertEquals(1, idempotencyRecordRepository.count());
            assertEquals(1, outboxEventRepository.count());

        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(
                    executor.awaitTermination(10, TimeUnit.SECONDS),
                    "Executor did not terminate"
            );
        }
    }

    @Test
    void shouldRejectDuplicatePaymentReferenceWithDifferentIdempotencyKey() {

        CreatePaymentRequest firstRequest = new CreatePaymentRequest(
                "MER-DUPLICATE-001",
                "ORDER-DUPLICATE-001",
                new BigDecimal("50000.00"),
                "NGN",
                "First payment"
        );

        CreatePaymentRequest secondRequest = new CreatePaymentRequest(
                "MER-DUPLICATE-001",
                "ORDER-DUPLICATE-001",
                new BigDecimal("75000.00"),
                "NGN",
                "Second payment"
        );

        paymentService.initiatePayment(firstRequest, "duplicate-key-001");

        assertThrows(
                PaymentReferenceConflictException.class,
                () -> paymentService.initiatePayment(
                        secondRequest,
                        "duplicate-key-002"
                )
        );

        assertEquals(1, paymentRepository.count());
        assertEquals(1, idempotencyRecordRepository.count());
        assertEquals(1, outboxEventRepository.count());
    }

    @Test
    void shouldReturnOriginalPaymentWhenSameReferenceAndIdempotencyKeyAreReused() {

        CreatePaymentRequest request = new CreatePaymentRequest(
                "MER-DUPLICATE-002",
                "ORDER-DUPLICATE-002",
                new BigDecimal("50000.00"),
                "NGN",
                "Retry payment"
        );

        PaymentResponse firstResponse = paymentService.initiatePayment(
                request,
                "duplicate-key-003"
        );

        PaymentResponse retryResponse = paymentService.initiatePayment(
                request,
                "duplicate-key-003"
        );

        assertEquals(firstResponse.paymentId(), retryResponse.paymentId());
        assertEquals(1, paymentRepository.count());
        assertEquals(1, idempotencyRecordRepository.count());
        assertEquals(1, outboxEventRepository.count());
    }

}
