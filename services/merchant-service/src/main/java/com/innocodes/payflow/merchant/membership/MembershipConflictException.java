package com.innocodes.payflow.merchant.membership;

public class MembershipConflictException extends RuntimeException {
    public MembershipConflictException() { super("Membership conflicts with current state"); }
}
