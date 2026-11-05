
package com.innocodes.payflow.payment.service;

import com.innocodes.payflow.payment.dto.request.CreatePaymentRequest;
import com.innocodes.payflow.payment.dto.response.PaymentResponse;
import com.innocodes.payflow.payment.entity.IdempotencyRecord;
import com.innocodes.payflow.payment.entity.Payment;
import com.innocodes.payflow.payment.enums.PaymentStatus;
import com.innocodes.payflow.payment.exception.IdempotencyKeyConflictException;
import com.innocodes.payflow.payment.exception.InvalidIdempotencyKeyException;
import com.innocodes.payflow.payment.repository.IdempotencyRecordRepository;
import com.innocodes.payflow.payment.repository.PaymentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final String MERCHANT_ID = "MER-1001";
    private static final String IDEMPOTENCY_KEY = "payflow-test-001";
    private static final String REFERENCE = "ORDER-TEST-001";

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Mock
    private PaymentCreationService paymentCreationService;

    private PaymentService paymentService;

    private PaymentRequestHasher paymentRequestHasher;

    private CreatePaymentRequest request;

    @BeforeEach
    void setUp() {
        paymentRequestHasher = new PaymentRequestHasher();

        paymentService = new PaymentService(
                paymentRepository,
                idempotencyRecordRepository,
                paymentRequestHasher,
                paymentCreationService
        );

        request = new CreatePaymentRequest(
                MERCHANT_ID,
                REFERENCE,
                new BigDecimal("50000.00"),
                "NGN",
                "Test payment"
        );
    }

    @Test
    void shouldCreatePaymentWhenIdempotencyKeyIsNew() {
        // Arrange
        String requestHash = paymentRequestHasher.hash(request);

        PaymentResponse expectedResponse = new PaymentResponse(
                UUID.randomUUID().toString(),
                MERCHANT_ID,
                REFERENCE,
                request.amount(),
                request.currency(),
                PaymentStatus.PENDING,
                Instant.now()
        );

        when(idempotencyRecordRepository
                .findByMerchantIdAndIdempotencyKey(
                        MERCHANT_ID,
                        IDEMPOTENCY_KEY
                )).thenReturn(Optional.empty());

        when(paymentCreationService.createPayment(
                request,
                IDEMPOTENCY_KEY,
                requestHash
        )).thenReturn(expectedResponse);

        // Act
        PaymentResponse actualResponse =
                paymentService.initiatePayment(request, IDEMPOTENCY_KEY);

        // Assert
        assertSame(expectedResponse, actualResponse);

        verify(paymentCreationService).createPayment(
                request,
                IDEMPOTENCY_KEY,
                requestHash
        );
    }

    @Test
    void shouldReturnExistingPaymentWhenSameRequestIsRetried() {
        // Arrange
        String requestHash = paymentRequestHasher.hash(request);

        UUID paymentId = UUID.randomUUID();
        Instant createdAt = Instant.now();

        IdempotencyRecord existingRecord = new IdempotencyRecord(
                UUID.randomUUID(),
                MERCHANT_ID,
                IDEMPOTENCY_KEY,
                requestHash,
                paymentId,
                createdAt
        );

        Payment existingPayment = new Payment(
                paymentId,
                MERCHANT_ID,
                REFERENCE,
                request.amount(),
                request.currency(),
                request.description(),
                PaymentStatus.PENDING,
                createdAt
        );

        when(idempotencyRecordRepository
                .findByMerchantIdAndIdempotencyKey(
                        MERCHANT_ID,
                        IDEMPOTENCY_KEY
                )).thenReturn(Optional.of(existingRecord));

        when(paymentRepository.findById(paymentId))
                .thenReturn(Optional.of(existingPayment));

        // Act
        PaymentResponse response =
                paymentService.initiatePayment(request, IDEMPOTENCY_KEY);

        // Assert
        assertEquals(paymentId.toString(), response.paymentId());
        assertEquals(MERCHANT_ID, response.merchantId());
        assertEquals(REFERENCE, response.reference());
        assertEquals(request.amount(), response.amount());
        assertEquals(PaymentStatus.PENDING, response.status());
        assertEquals(createdAt, response.createdAt());

        verify(paymentCreationService, never())
                .createPayment(request, IDEMPOTENCY_KEY, requestHash);
    }

    @Test
    void shouldThrowConflictWhenSameKeyIsUsedWithDifferentRequest() {
        // Arrange
        IdempotencyRecord existingRecord = new IdempotencyRecord(
                UUID.randomUUID(),
                MERCHANT_ID,
                IDEMPOTENCY_KEY,
                "different-request-hash",
                UUID.randomUUID(),
                Instant.now()
        );

        when(idempotencyRecordRepository
                .findByMerchantIdAndIdempotencyKey(
                        MERCHANT_ID,
                        IDEMPOTENCY_KEY
                )).thenReturn(Optional.of(existingRecord));

        // Act & Assert
        assertThrows(
                IdempotencyKeyConflictException.class,
                () -> paymentService.initiatePayment(
                        request,
                        IDEMPOTENCY_KEY
                )
        );

        verify(paymentCreationService, never())
                .createPayment(
                        org.mockito.ArgumentMatchers.eq(request),
                        org.mockito.ArgumentMatchers.eq(IDEMPOTENCY_KEY),
                        org.mockito.ArgumentMatchers.anyString()
                );
    }


    @Test
    void shouldReturnExistingPaymentWhenConcurrentRequestWins() {
        // Arrange
        String requestHash = paymentRequestHasher.hash(request);

        UUID paymentId = UUID.randomUUID();
        Instant createdAt = Instant.now();

        IdempotencyRecord existingRecord = new IdempotencyRecord(
                UUID.randomUUID(),
                MERCHANT_ID,
                IDEMPOTENCY_KEY,
                requestHash,
                paymentId,
                createdAt
        );

        Payment existingPayment = new Payment(
                paymentId,
                MERCHANT_ID,
                REFERENCE,
                request.amount(),
                request.currency(),
                request.description(),
                PaymentStatus.PENDING,
                createdAt
        );

        when(idempotencyRecordRepository
                .findByMerchantIdAndIdempotencyKey(
                        MERCHANT_ID,
                        IDEMPOTENCY_KEY
                ))
                .thenReturn(Optional.empty(), Optional.of(existingRecord));

        when(paymentCreationService.createPayment(
                request,
                IDEMPOTENCY_KEY,
                requestHash
        )).thenThrow(new DataIntegrityViolationException(
                "Duplicate idempotency key"
        ));

        when(paymentRepository.findById(paymentId))
                .thenReturn(Optional.of(existingPayment));

        // Act
        PaymentResponse response =
                paymentService.initiatePayment(request, IDEMPOTENCY_KEY);

        // Assert
        assertEquals(paymentId.toString(), response.paymentId());
        assertEquals(createdAt, response.createdAt());

        verify(paymentCreationService).createPayment(
                request,
                IDEMPOTENCY_KEY,
                requestHash
        );
    }

    @Test
    void shouldRejectBlankIdempotencyKey() {

        CreatePaymentRequest request = new CreatePaymentRequest(
                "MER-TEST-INVALID",
                "ORDER-TEST-INVALID",
                new BigDecimal("50000.00"),
                "NGN",
                "Invalid key test"
        );

        assertThrows(
                InvalidIdempotencyKeyException.class,
                () -> paymentService.initiatePayment(request, " ")
        );

        verifyNoInteractions(paymentCreationService);
    }

}
