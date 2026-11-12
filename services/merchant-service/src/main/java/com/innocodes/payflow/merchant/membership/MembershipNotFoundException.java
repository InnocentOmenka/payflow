package com.innocodes.payflow.merchant.membership;

public class MembershipNotFoundException extends RuntimeException {
    public MembershipNotFoundException() { super("Resource not found"); }
}
