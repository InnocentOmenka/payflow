package com.innocodes.payflow.payment.exception;

public class PaymentReferenceConflictException extends RuntimeException {

    public PaymentReferenceConflictException(String message) {
        super(message);
    }
}