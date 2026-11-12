package com.innocodes.payflow.payment.service;

import com.innocodes.payflow.payment.dto.request.CreatePaymentRequest;
import com.innocodes.payflow.payment.dto.response.PaymentResponse;
import com.innocodes.payflow.payment.merchant.MerchantAccessClient;
import com.innocodes.payflow.payment.security.MerchantPaymentAuthorization;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** The HTTP application boundary. Core services remain available to trusted internal workers/fixtures. */
@Service
public class MerchantPaymentService {
    private final MerchantPaymentAuthorization authorization;
    private final MerchantAccessClient merchants;
    private final PaymentService payments;

    public MerchantPaymentService(MerchantPaymentAuthorization authorization, MerchantAccessClient merchants, PaymentService payments) {
        this.authorization = authorization;
        this.merchants = merchants;
        this.payments = payments;
    }

    public PaymentResponse initiatePayment(CreatePaymentRequest request, String idempotencyKey) {
        var jwt = authorization.require("payment.create");
        var access = merchants.resolve(jwt.getClaimAsString("iss"), jwt.getSubject());
        if (!"ACTIVE".equals(access.status()) || !access.merchantId().equals(request.merchantId()))
            throw new AccessDeniedException("Access is denied");
        return payments.initiatePayment(new CreatePaymentRequest(access.merchantId(), request.reference(),
                request.amount(), request.currency(), request.description()), idempotencyKey);
    }

    public PaymentResponse getPaymentById(UUID paymentId) {
        var jwt = authorization.require("payment.read");
        var access = merchants.resolve(jwt.getClaimAsString("iss"), jwt.getSubject());
        return payments.getPaymentByIdAndMerchantId(paymentId, access.merchantId());
    }
}
