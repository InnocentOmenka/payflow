package com.innocodes.payflow.ledger.service;

import com.innocodes.payflow.contracts.payment.PaymentProcessedEvent;
import com.innocodes.payflow.ledger.dto.JournalEntryRequest;
import com.innocodes.payflow.ledger.exception.PaymentEventConflictException;
import com.innocodes.payflow.ledger.enums.EntryType;
import com.innocodes.payflow.ledger.enums.JournalType;
import com.innocodes.payflow.ledger.repository.ProcessedPaymentEventRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

@Service
public class PaymentProcessedService {

    private final ProcessedPaymentEventRepository processedEvents;
    private final JournalPostingService journalPostingService;
    private final EntityManager entityManager;

    public PaymentProcessedService(
            ProcessedPaymentEventRepository processedEvents,
            JournalPostingService journalPostingService,
            EntityManager entityManager
    ) {
        this.processedEvents = processedEvents;
        this.journalPostingService = journalPostingService;
        this.entityManager = entityManager;
    }

    @Transactional
    public void process(PaymentProcessedEvent event) {
        validateEvent(event);

        var existing = processedEvents.findById(event.eventId());
        if (existing.isPresent()) {
            if (matches(existing.get(), event)) {
                return;
            }
            throw new PaymentEventConflictException("Conflicting payment processed event: " + event.eventId());
        }
        if (processedEvents.findByPaymentId(event.paymentId()).isPresent()) {
            throw new PaymentEventConflictException("Conflicting payment result for payment: " + event.paymentId());
        }

        // Force INSERT rather than merge for assigned IDs. Concurrent uniqueness failures
        // must roll back this transaction and be retried in a fresh transaction.
        entityManager.persist(new com.innocodes.payflow.ledger.entity.ProcessedPaymentEvent(
                event.eventId(), event.paymentId(), event.merchantId(), event.reference(),
                event.amount(), event.currency(), event.status(), event.failureReason(),
                event.processedAt().truncatedTo(ChronoUnit.MICROS), Instant.now()
        ));
        entityManager.flush();

        if ("SUCCESSFUL".equals(event.status())) {
            journalPostingService.postJournal(
                    event.paymentId(), event.eventId(), event.merchantId(), event.reference(),
                    JournalType.PAYMENT_CAPTURE, event.currency(), "Successful payment capture",
                    List.of(
                            new JournalEntryRequest("1001", EntryType.DEBIT, event.amount()),
                            new JournalEntryRequest("2001", EntryType.CREDIT, event.amount())
                    )
            );
        }
    }

    private boolean matches(
            com.innocodes.payflow.ledger.entity.ProcessedPaymentEvent accepted,
            PaymentProcessedEvent event
    ) {
        return accepted.getPaymentId().equals(event.paymentId())
                && accepted.getMerchantId().equals(event.merchantId())
                && accepted.getReference().equals(event.reference())
                && accepted.getAmount().compareTo(event.amount()) == 0
                && accepted.getCurrency().equals(event.currency())
                && accepted.getStatus().equals(event.status())
                && Objects.equals(accepted.getFailureReason(), event.failureReason())
                && accepted.getProcessedAt().equals(event.processedAt().truncatedTo(ChronoUnit.MICROS));
    }

    private void validateEvent(PaymentProcessedEvent event) {
        if (event == null || event.eventId() == null || event.paymentId() == null
                || event.processedAt() == null
                || !validText(event.merchantId(), 100)
                || !validText(event.reference(), 255)
                || event.currency() == null || !event.currency().matches("[A-Z]{3}")
                || !("SUCCESSFUL".equals(event.status()) || "FAILED".equals(event.status()))
                || (event.failureReason() != null && event.failureReason().length() > 500)) {
            throw new IllegalArgumentException("Invalid payment processed event metadata");
        }
        if (event.amount() == null || event.amount().signum() <= 0
                || event.amount().scale() > 2
                || event.amount().precision() - event.amount().scale() > 17) {
            throw new IllegalArgumentException("Invalid payment processed event amount for NUMERIC(19,2)");
        }
    }

    private boolean validText(String value, int maxLength) {
        return value != null && !value.isBlank() && value.length() <= maxLength;
    }
}
