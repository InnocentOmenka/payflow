package com.innocodes.payflow.payment.service;

import com.innocodes.payflow.contracts.payment.PaymentProcessedEvent;
import com.innocodes.payflow.payment.entity.Payment;
import com.innocodes.payflow.payment.entity.ProcessedPaymentEvent;
import com.innocodes.payflow.payment.enums.PaymentStatus;
import com.innocodes.payflow.payment.repository.PaymentRepository;
import com.innocodes.payflow.payment.repository.ProcessedPaymentEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentResultService {

    private final PaymentRepository paymentRepository;
    private final ProcessedPaymentEventRepository processedEventRepository;

    public PaymentResultService(
            PaymentRepository paymentRepository,
            ProcessedPaymentEventRepository processedEventRepository
    ) {
        this.paymentRepository = paymentRepository;
        this.processedEventRepository = processedEventRepository;
    }

    @Transactional
    public void process(PaymentProcessedEvent event) {
        validateEvent(event);

        // Ignore an event that has already been applied.
        if (processedEventRepository.existsById(event.eventId())) {
            return;
        }

        // Lock the payment while applying its result.
        Payment payment = paymentRepository.findByIdForUpdate(event.paymentId())
                .orElseThrow(() -> new IllegalStateException(
                        "Payment not found: " + event.paymentId()
                ));

        validatePaymentDetails(payment, event);

        PaymentStatus resultStatus = parseResultStatus(event.status());

        if (resultStatus == PaymentStatus.SUCCESSFUL) {
            payment.markAsSuccessful();
        } else {
            payment.markAsFailed();
        }

        // Both changes participate in this same database transaction.
        processedEventRepository.save(
                new ProcessedPaymentEvent(event.eventId(), event.paymentId())
        );
    }

    private void validateEvent(PaymentProcessedEvent event) {
        if (event == null
                || event.eventId() == null
                || event.paymentId() == null
                || event.processedAt() == null) {
            throw new IllegalArgumentException(
                    "Payment processed event is missing required fields"
            );
        }
    }

    private void validatePaymentDetails(
            Payment payment,
            PaymentProcessedEvent event
    ) {
        boolean matches =
                payment.getMerchantId().equals(event.merchantId())
                        && payment.getReference().equals(event.reference())
                        && payment.getAmount().compareTo(event.amount()) == 0
                        && payment.getCurrency().equals(event.currency());

        if (!matches) {
            throw new IllegalStateException(
                    "Payment result does not match the original payment: "
                            + event.paymentId()
            );
        }
    }

    private PaymentStatus parseResultStatus(String status) {
        if (status == null) {
            throw new IllegalArgumentException(
                    "Payment result status cannot be null"
            );
        }

        return switch (status) {
            case "SUCCESSFUL" -> PaymentStatus.SUCCESSFUL;
            case "FAILED" -> PaymentStatus.FAILED;
            default -> throw new IllegalArgumentException(
                    "Unsupported payment result status: " + status
            );
        };
    }
}