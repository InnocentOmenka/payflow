package com.innocodes.payflow.merchant.security;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Only Payment's validated machine identity may attest a human identity for resolution. */
public final class AccessResolverAuthorization {
    private final String issuer;
    public AccessResolverAuthorization(String issuer) { this.issuer = issuer; }

    public boolean allowed(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) return false;
        var jwt = token.getToken();
        if (!issuer.equals(jwt.getClaimAsString("iss"))
                || !jwt.getAudience().contains("payflow-merchant-api")
                || !"Bearer".equals(jwt.getClaimAsString("typ"))
                || !"payflow-payment-service".equals(jwt.getClaimAsString("azp"))) return false;
        String scope = jwt.getClaimAsString("scope");
        Map<String, Object> resources = jwt.getClaim("resource_access");
        return scope != null && Arrays.asList(scope.split("\\s+")).contains("merchant.access.resolve")
                && resources != null && resources.get("payflow-merchant-api") instanceof Map<?, ?> api
                && api.get("roles") instanceof Collection<?> roles && roles.contains("access-resolver");
    }

    public void require() {
        if (!allowed(SecurityContextHolder.getContext().getAuthentication())) throw new AccessDeniedException("Access is denied");
    }
}
