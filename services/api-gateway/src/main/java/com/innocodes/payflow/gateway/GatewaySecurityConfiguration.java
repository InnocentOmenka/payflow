package com.innocodes.payflow.gateway;

import com.innocodes.payflow.security.ResourceServerHttpSecurity;
import com.innocodes.payflow.security.ResourceServerJwtConfiguration;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter.RequestHttpHeadersFilter;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@Import(ResourceServerJwtConfiguration.class)
public class GatewaySecurityConfiguration {
    @Bean
    SecurityFilterChain gatewaySecurity(HttpSecurity http,
            @Value("${spring.security.oauth2.resourceserver.jwt.audiences}") String audience) throws Exception {
        return ResourceServerHttpSecurity.configure(http, audience)
                .authorizeHttpRequests(routes -> routes
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/payments")
                            .access((authentication, context) -> new AuthorizationDecision(allowed(authentication.get(), "payment.create")))
                        .requestMatchers(HttpMethod.GET, "/api/v1/payments/{paymentId}")
                            .access((authentication, context) -> new AuthorizationDecision(allowed(authentication.get(), "payment.read")))
                        .anyRequest().denyAll()).build();
    }

    private boolean allowed(Authentication authentication, String scope) {
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) return false;
        var jwt = token.getToken();
        if (!"payflow-portal".equals(jwt.getClaimAsString("azp")) || !"Bearer".equals(jwt.getClaimAsString("typ"))
                || !jwt.getAudience().contains("payflow-payment-api") || jwt.getSubject() == null
                || jwt.getSubject().isBlank() || jwt.getSubject().length() > 255) return false;
        var authorities = authentication.getAuthorities().stream().map(a -> a.getAuthority()).toList();
        return authorities.contains("SCOPE_" + scope)
                && authorities.stream().anyMatch(Set.of("ROLE_merchant-admin", "ROLE_merchant-operator")::contains);
    }

    @Bean
    RequestHttpHeadersFilter removeUntrustedIdentityHeaders() {
        return (headers, request) -> {
            var forwarded = new HttpHeaders();
            forwarded.putAll(headers);
            forwarded.headerNames().stream().filter(this::identityHeader).toList().forEach(forwarded::remove);
            return forwarded; // Authorization, Idempotency-Key and content headers remain unchanged.
        };
    }

    private boolean identityHeader(String header) {
        String name = header.toLowerCase(Locale.ROOT);
        return name.startsWith("x-user-") || name.startsWith("x-merchant-") || name.startsWith("x-principal-")
                || name.startsWith("x-authenticated-")
                || Set.of("x-user", "x-merchant", "x-principal", "x-role", "x-roles", "x-subject", "x-issuer").contains(name);
    }
}
