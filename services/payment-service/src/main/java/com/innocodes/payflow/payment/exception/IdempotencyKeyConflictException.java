package com.innocodes.payflow.payment.exception;

public class IdempotencyKeyConflictException extends RuntimeException{
    public IdempotencyKeyConflictException(String message) {
        super(message);
    }
}
