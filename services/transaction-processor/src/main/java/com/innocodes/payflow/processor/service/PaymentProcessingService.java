package com.innocodes.payflow.processor.service;

import com.innocodes.payflow.contracts.payment.PaymentInitiatedEvent;
import com.innocodes.payflow.contracts.payment.PaymentProcessedEvent;
import com.innocodes.payflow.processor.entity.OutboxEvent;
import com.innocodes.payflow.processor.entity.ProcessedPayment;
import com.innocodes.payflow.processor.enums.ProcessingStatus;
import com.innocodes.payflow.processor.repository.OutboxEventRepository;
import com.innocodes.payflow.processor.repository.ProcessedPaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

@Service
public class PaymentProcessingService {

    private static final String EVENT_TYPE = "PaymentProcessed";

    private final ProcessedPaymentRepository processedPaymentRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final AuthorizationSimulator authorizationSimulator;
    private final JsonMapper jsonMapper;

    public PaymentProcessingService(
            ProcessedPaymentRepository processedPaymentRepository,
            OutboxEventRepository outboxEventRepository,
            AuthorizationSimulator authorizationSimulator,
            JsonMapper jsonMapper
    ) {
        this.processedPaymentRepository = processedPaymentRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.authorizationSimulator = authorizationSimulator;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public void process(PaymentInitiatedEvent event) {

        if (processedPaymentRepository.existsByEventId(event.eventId())) {
            return;
        }

        if (processedPaymentRepository.existsByPaymentId(event.paymentId())) {
            throw new IllegalStateException(
                    "Payment has already been processed: " + event.paymentId()
            );
        }

        AuthorizationSimulator.AuthorizationResult authorization =
                authorizationSimulator.authorize(event.reference());

        Instant processedAt = Instant.now();

        ProcessingStatus status = authorization.successful()
                ? ProcessingStatus.SUCCESSFUL
                : ProcessingStatus.FAILED;

        ProcessedPayment processedPayment = new ProcessedPayment(
                UUID.randomUUID(),
                event.eventId(),
                event.paymentId(),
                event.merchantId(),
                event.reference(),
                event.amount(),
                event.currency(),
                status,
                authorization.failureReason(),
                processedAt
        );

        processedPaymentRepository.save(processedPayment);

        PaymentProcessedEvent processedEvent = new PaymentProcessedEvent(
                UUID.randomUUID(),
                event.paymentId(),
                event.merchantId(),
                event.reference(),
                event.amount(),
                event.currency(),
                status.name(),
                authorization.failureReason(),
                processedAt
        );

        try {
            String payload = jsonMapper.writeValueAsString(processedEvent);

            OutboxEvent outboxEvent = new OutboxEvent(
                    UUID.randomUUID(),
                    event.paymentId(),
                    EVENT_TYPE,
                    payload,
                    processedAt
            );

            outboxEventRepository.save(outboxEvent);

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed to serialize payment processing event",
                    exception
            );
        }
    }
}