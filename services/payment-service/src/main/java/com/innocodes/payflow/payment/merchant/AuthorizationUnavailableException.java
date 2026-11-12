package com.innocodes.payflow.payment.merchant;

public class AuthorizationUnavailableException extends RuntimeException {
    public AuthorizationUnavailableException() { super("Authorization service unavailable"); }
}
