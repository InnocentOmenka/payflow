package com.innocodes.payflow.notification.service;

import com.innocodes.payflow.contracts.payment.PaymentProcessedEvent;
import com.innocodes.payflow.notification.exception.NotificationConflictException;
import com.innocodes.payflow.notification.model.WebhookEnvelope;
import com.innocodes.payflow.notification.repository.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.temporal.ChronoUnit;

@Service
public class NotificationIngestionService {
    private final NotificationRepository repository;
    private final JsonMapper mapper;

    public NotificationIngestionService(NotificationRepository repository, JsonMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional
    public void process(PaymentProcessedEvent event) {
        validate(event);
        // Canonical values make equivalent numeric scales and sub-microsecond timestamps replay safely.
        var canonical = new PaymentProcessedEvent(event.eventId(), event.paymentId(), event.merchantId(),
                event.reference(), event.amount().setScale(2), event.currency(), event.status(),
                event.failureReason(), event.processedAt().truncatedTo(ChronoUnit.MICROS));
        String payload = mapper.writeValueAsString(canonical);
        var existing = repository.findById(event.eventId());
        if (existing.isPresent()) {
            if (existing.get().eventPayload().equals(payload)) {
                return;
            }
            throw new NotificationConflictException("Conflicting notification event " + event.eventId());
        }
        if (repository.findByPaymentId(event.paymentId()).isPresent()) {
            throw new NotificationConflictException("Conflicting payment result " + event.paymentId());
        }
        // A real INSERT; uniqueness failures propagate and roll back. No HTTP in this transaction.
        repository.insert(event.eventId(), event.paymentId(), event.merchantId(), event.status(),
                payload, mapper.writeValueAsString(WebhookEnvelope.from(canonical)));
    }

    private void validate(PaymentProcessedEvent e) {
        if (e == null || e.eventId() == null || e.paymentId() == null || e.processedAt() == null
                || !text(e.merchantId(), 100) || !text(e.reference(), 255)
                || e.currency() == null || !e.currency().matches("[A-Z]{3}")
                || !("SUCCESSFUL".equals(e.status()) || "FAILED".equals(e.status()))
                || (e.failureReason() != null && (e.failureReason().length() > 500 || e.failureReason().contains("\0")))
                || e.amount() == null || e.amount().signum() <= 0 || e.amount().scale() > 2
                || e.amount().precision() - e.amount().scale() > 17) {
            throw new IllegalArgumentException("Invalid payment processed notification event");
        }
    }

    private boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max && !value.contains("\0");
    }
}
