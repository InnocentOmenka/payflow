package com.innocodes.payflow.payment.security;

import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

public final class MerchantPaymentAuthorization {
    private final String issuer;
    public MerchantPaymentAuthorization(String issuer) { this.issuer = issuer; }

    public boolean allowed(Authentication authentication, String scope) {
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) return false;
        var jwt = token.getToken();
        if (!issuer.equals(jwt.getClaimAsString("iss")) || !"payflow-portal".equals(jwt.getClaimAsString("azp"))
                || !"Bearer".equals(jwt.getClaimAsString("typ")) || !jwt.getAudience().contains("payflow-payment-api")
                || jwt.getSubject() == null || jwt.getSubject().isBlank() || jwt.getSubject().length() > 255) return false;
        var authorities = authentication.getAuthorities().stream().map(a -> a.getAuthority()).toList();
        return authorities.contains("SCOPE_" + scope)
                && authorities.stream().anyMatch(Set.of("ROLE_merchant-admin", "ROLE_merchant-operator")::contains);
    }

    public Jwt require(String scope) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated())
            throw new InsufficientAuthenticationException("Bearer authentication is required");
        if (!allowed(authentication, scope)) throw new AccessDeniedException("Access is denied");
        return ((JwtAuthenticationToken) authentication).getToken();
    }
}
