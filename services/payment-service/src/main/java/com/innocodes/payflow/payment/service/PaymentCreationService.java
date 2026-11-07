
package com.innocodes.payflow.payment.service;


import com.innocodes.payflow.payment.dto.request.CreatePaymentRequest;
import com.innocodes.payflow.contracts.payment.PaymentInitiatedEvent;
import com.innocodes.payflow.payment.dto.response.PaymentResponse;
import com.innocodes.payflow.payment.entity.IdempotencyRecord;
import com.innocodes.payflow.payment.entity.OutboxEvent;
import com.innocodes.payflow.payment.entity.Payment;
import com.innocodes.payflow.payment.enums.PaymentStatus;
import com.innocodes.payflow.payment.repository.IdempotencyRecordRepository;
import com.innocodes.payflow.payment.repository.OutboxEventRepository;
import com.innocodes.payflow.payment.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

@Service
public class PaymentCreationService {

    private final PaymentRepository paymentRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final JsonMapper jsonMapper;

    public PaymentCreationService(
            PaymentRepository paymentRepository,
            IdempotencyRecordRepository idempotencyRecordRepository,
            OutboxEventRepository outboxEventRepository,
            JsonMapper jsonMapper
    ) {
        this.paymentRepository = paymentRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public PaymentResponse createPayment(
            CreatePaymentRequest request,
            String idempotencyKey,
            String requestHash
    ) {
        UUID paymentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
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

        PaymentInitiatedEvent event = new PaymentInitiatedEvent(
                eventId,
                savedPayment.getId(),
                savedPayment.getMerchantId(),
                savedPayment.getReference(),
                savedPayment.getAmount(),
                savedPayment.getCurrency(),
                savedPayment.getDescription(),
                createdAt
        );

        String payload = jsonMapper.writeValueAsString(event);

        OutboxEvent outboxEvent = new OutboxEvent(
                eventId,
                "PAYMENT",
                savedPayment.getId(),
                "PaymentInitiated",
                payload,
                createdAt
        );

        outboxEventRepository.save(outboxEvent);

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
