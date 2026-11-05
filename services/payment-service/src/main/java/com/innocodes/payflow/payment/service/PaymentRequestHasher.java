package com.innocodes.payflow.payment.service;

import com.innocodes.payflow.payment.dto.request.CreatePaymentRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class PaymentRequestHasher {

    public String hash(CreatePaymentRequest request) {
        String canonicalRequest = String.join("|",
                request.merchantId(),
                request.reference(),
                normalizeAmount(request.amount()),
                request.currency(),
                request.description() == null ? "" : request.description()
        );

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(
                    canonicalRequest.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(hashBytes);

        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 algorithm is not available",
                    exception
            );
        }
    }

    private String normalizeAmount(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }
}