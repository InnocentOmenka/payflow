package com.innocodes.payflow.payment.service;

import com.innocodes.payflow.payment.dto.request.CreatePaymentRequest;
import com.innocodes.payflow.payment.dto.response.PaymentResponse;
import com.innocodes.payflow.payment.entity.Payment;
import com.innocodes.payflow.payment.enums.PaymentStatus;
import com.innocodes.payflow.payment.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;

    public PaymentService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Transactional
    public PaymentResponse initiatePayment(CreatePaymentRequest request) {

        Payment payment = new Payment(
                UUID.randomUUID(),
                request.merchantId(),
                request.reference(),
                request.amount(),
                request.currency(),
                request.description(),
                PaymentStatus.PENDING,
                Instant.now()
        );

        Payment savedPayment = paymentRepository.save(payment);

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