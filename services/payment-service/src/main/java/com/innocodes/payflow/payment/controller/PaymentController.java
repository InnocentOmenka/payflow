package com.innocodes.payflow.payment.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.innocodes.payflow.payment.dto.request.CreatePaymentRequest;
import com.innocodes.payflow.payment.dto.response.PaymentResponse;
import com.innocodes.payflow.payment.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> initiatePayment(
            @Valid @RequestBody CreatePaymentRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        PaymentResponse response =
                paymentService.initiatePayment(request, idempotencyKey);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse> getPaymentById(
            @PathVariable UUID paymentId) {

        PaymentResponse response = paymentService.getPaymentById(paymentId);

        return ResponseEntity.ok(response);
    }
}

