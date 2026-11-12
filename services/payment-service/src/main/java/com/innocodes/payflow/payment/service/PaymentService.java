
package com.innocodes.payflow.payment.service;

import com.innocodes.payflow.payment.dto.request.CreatePaymentRequest;
import com.innocodes.payflow.payment.dto.response.PaymentResponse;
import com.innocodes.payflow.payment.entity.IdempotencyRecord;
import com.innocodes.payflow.payment.entity.Payment;
import com.innocodes.payflow.payment.exception.IdempotencyKeyConflictException;
import com.innocodes.payflow.payment.exception.InvalidIdempotencyKeyException;
import com.innocodes.payflow.payment.exception.PaymentNotFoundException;
import com.innocodes.payflow.payment.exception.PaymentReferenceConflictException;
import com.innocodes.payflow.payment.repository.IdempotencyRecordRepository;
import com.innocodes.payflow.payment.repository.PaymentRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final PaymentRequestHasher paymentRequestHasher;
    private final PaymentCreationService paymentCreationService;

    public PaymentService(
            PaymentRepository paymentRepository,
            IdempotencyRecordRepository idempotencyRecordRepository,
            PaymentRequestHasher paymentRequestHasher,
            PaymentCreationService paymentCreationService
    ) {
        this.paymentRepository = paymentRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.paymentRequestHasher = paymentRequestHasher;
        this.paymentCreationService = paymentCreationService;
    }

    public PaymentResponse initiatePayment(
            CreatePaymentRequest request,
            String idempotencyKey
    ) {
        if (idempotencyKey == null
                || idempotencyKey.isBlank()
                || idempotencyKey.length() > 255) {
            throw new InvalidIdempotencyKeyException(
                    "Idempotency-Key must contain between 1 and 255 characters"
            );
        }

        String requestHash = paymentRequestHasher.hash(request);

        Optional<IdempotencyRecord> existingRecord =
                idempotencyRecordRepository.findByMerchantIdAndIdempotencyKey(
                        request.merchantId(),
                        idempotencyKey
                );

        // Resolve an existing idempotency key before checking the reference.
        if (existingRecord.isPresent()) {
            return resolveExistingRequest(existingRecord.get(), requestHash);
        }

        // Reject a reference already used by this merchant.
        if (paymentRepository.existsByMerchantIdAndReference(
                request.merchantId(),
                request.reference()
        )) {
            throw new PaymentReferenceConflictException(
                    "A payment with this reference already exists for this merchant"
            );
        }

        try {
            return paymentCreationService.createPayment(
                    request,
                    idempotencyKey,
                    requestHash
            );
        } catch (DataIntegrityViolationException exception) {

            /*
             * A concurrent request may have inserted the same
             * idempotency key while this request was executing.
             */
            Optional<IdempotencyRecord> concurrentRecord =
                    idempotencyRecordRepository.findByMerchantIdAndIdempotencyKey(
                            request.merchantId(),
                            idempotencyKey
                    );

            if (concurrentRecord.isPresent()) {
                return resolveExistingRequest(
                        concurrentRecord.get(),
                        requestHash
                );
            }

            // Identify a duplicate merchant payment reference.
            if (isPaymentReferenceConstraintViolation(exception)) {
                throw new PaymentReferenceConflictException(
                        "A payment with this reference already exists for this merchant"
                );
            }

            throw exception;
        }
    }

    private PaymentResponse resolveExistingRequest(
            IdempotencyRecord record,
            String requestHash
    ) {
        if (!record.getRequestHash().equals(requestHash)) {
            throw new IdempotencyKeyConflictException(
                    "This Idempotency-Key has already been used with a different request"
            );
        }

        Payment payment = paymentRepository.findByIdAndMerchantId(record.getPaymentId(), record.getMerchantId())
                .orElseThrow(() -> new IllegalStateException(
                        "Payment associated with idempotency record was not found"
                ));

        return toPaymentResponse(payment);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPaymentByIdAndMerchantId(UUID paymentId, String merchantId) {
        return toPaymentResponse(paymentRepository.findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new PaymentNotFoundException("Resource not found")));
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPaymentById(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(
                        "Payment not found with ID: " + paymentId
                ));

        return toPaymentResponse(payment);
    }

    private PaymentResponse toPaymentResponse(Payment payment) {
        return new PaymentResponse(
                payment.getId().toString(),
                payment.getMerchantId(),
                payment.getReference(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getCreatedAt()
        );
    }

    private boolean isPaymentReferenceConstraintViolation(
            Throwable exception
    ) {
        Throwable cause = exception;

        while (cause != null) {
            if (cause instanceof ConstraintViolationException violation) {
                return "uk_payments_merchant_reference".equals(
                        violation.getConstraintName()
                );
            }

            cause = cause.getCause();
        }

        return false;
    }
}
