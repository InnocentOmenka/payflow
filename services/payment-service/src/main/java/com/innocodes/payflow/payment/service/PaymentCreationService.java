package com.innocodes.payflow.payment.service;

import com.innocodes.payflow.payment.dto.request.CreatePaymentRequest;
import com.innocodes.payflow.payment.dto.response.PaymentResponse;
import com.innocodes.payflow.payment.entity.IdempotencyRecord;
import com.innocodes.payflow.payment.entity.Payment;
import com.innocodes.payflow.payment.enums.PaymentStatus;
import com.innocodes.payflow.payment.repository.IdempotencyRecordRepository;
import com.innocodes.payflow.payment.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class PaymentCreationService {

    private final PaymentRepository paymentRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;

    public PaymentCreationService(
            PaymentRepository paymentRepository,
            IdempotencyRecordRepository idempotencyRecordRepository
    ) {
        this.paymentRepository = paymentRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
    }

    @Transactional
    public PaymentResponse createPayment(
            CreatePaymentRequest request,
            String idempotencyKey,
            String requestHash
    ) {
        UUID paymentId = UUID.randomUUID();
        Instant createdAt = Instant.now();

        Payment payment = new Payment(
                paymentId,
                request.merchantId(),
                request.reference(),
                request.amount(),
                request.currency(),
                request.description(),
                PaymentStatus.PENDING,
                createdAt
        );

        Payment savedPayment = paymentRepository.save(payment);

        IdempotencyRecord idempotencyRecord = new IdempotencyRecord(
                UUID.randomUUID(),
                request.merchantId(),
                idempotencyKey,
                requestHash,
                savedPayment.getId(),
                createdAt
        );

        idempotencyRecordRepository.save(idempotencyRecord);

        return new PaymentResponse(
                savedPayment.getId().toString(),
                savedPayment.getMerchantId(),
                savedPayment.getReference(),
                savedPayment.getAmount(),
                savedPayment.getCurrency(),
                savedPayment.getStatus(),
                savedPayment.getCreatedAt()
        );
    }
}