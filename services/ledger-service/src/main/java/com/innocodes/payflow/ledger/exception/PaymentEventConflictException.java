package com.innocodes.payflow.ledger.exception;

public class PaymentEventConflictException extends IllegalStateException {

    public PaymentEventConflictException(String message) {
        super(message);
    }
}
