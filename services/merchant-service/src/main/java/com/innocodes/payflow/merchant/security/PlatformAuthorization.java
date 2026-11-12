package com.innocodes.payflow.merchant.security;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Accepts only the framework's validated JWT identity; never headers or request identity. */
public final class PlatformAuthorization {
    private final String issuer;

    public PlatformAuthorization(String issuer) { this.issuer = issuer; }

    public boolean allowed(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication) || !authentication.isAuthenticated()) return false;
        var jwt = jwtAuthentication.getToken();
        String subject = jwt.getSubject();
        if (!issuer.equals(jwt.getClaimAsString("iss")) || subject == null || subject.isBlank() || subject.length() > 255
                || issuer.length() > 512 || jwt.getAudience() == null || !jwt.getAudience().contains("payflow-merchant-api")
                || !"payflow-platform-console".equals(jwt.getClaimAsString("azp"))
                || !"Bearer".equals(jwt.getClaimAsString("typ"))) return false;
        String scope = jwt.getClaimAsString("scope");
        if (scope == null || Arrays.stream(scope.split("\\s+")).noneMatch("merchant.bindings.manage"::equals)) return false;
        Map<String, Object> resources = jwt.getClaim("resource_access");
        return resources != null && resources.get("payflow-merchant-api") instanceof Map<?, ?> resource
                && resource.get("roles") instanceof Collection<?> roles && roles.contains("platform-admin");
    }

    public Actor require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new InsufficientAuthenticationException("Bearer authentication is required");
        }
        if (!allowed(authentication)) throw new AccessDeniedException("Access is denied");
        var jwt = ((JwtAuthenticationToken) authentication).getToken();
        return new Actor(jwt.getClaimAsString("iss"), jwt.getSubject(), jwt.getClaimAsString("azp"));
    }

    public record Actor(String issuer, String subject, String clientId) {}
}
