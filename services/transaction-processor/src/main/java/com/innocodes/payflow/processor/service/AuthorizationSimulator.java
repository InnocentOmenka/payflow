package com.innocodes.payflow.processor.service;

import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class AuthorizationSimulator {

    private static final String DECLINE_KEYWORD = "DECLINE";
    private static final String FAILURE_REASON = "SIMULATED_DECLINE";

    public AuthorizationResult authorize(String reference) {

        if (reference.toUpperCase(Locale.ROOT).contains(DECLINE_KEYWORD)) {
            return new AuthorizationResult(false, FAILURE_REASON);
        }

        return new AuthorizationResult(true, null);
    }

    public record AuthorizationResult(
            boolean successful,
            String failureReason
    ) {
    }
}