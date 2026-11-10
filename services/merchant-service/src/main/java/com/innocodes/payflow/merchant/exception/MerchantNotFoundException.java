package com.innocodes.payflow.merchant.exception;

public class MerchantNotFoundException extends RuntimeException {
    public MerchantNotFoundException() {
        super("Merchant not found");
    }
}
