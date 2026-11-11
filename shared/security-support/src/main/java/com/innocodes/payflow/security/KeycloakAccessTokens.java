package com.innocodes.payflow.security;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import org.springframework.core.convert.converter.Converter;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.client.RestTemplate;

/** The Slice 1 Keycloak access-token contract, not a token issuer or tenant resolver. */
public final class KeycloakAccessTokens {
    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();
    private static final Map<String, Set<String>> ROLES = Map.of(
            "payflow-merchant-api", Set.of("merchant-admin", "merchant-operator", "platform-admin", "access-resolver"),
            "payflow-payment-api", Set.of("merchant-admin", "merchant-operator"));
    private static final Map<String, Set<String>> SCOPES = Map.of(
            "payflow-merchant-api", Set.of("merchant.read", "merchant.profile.write", "merchant.lifecycle.manage",
                    "merchant.bindings.manage", "webhook.read", "webhook.manage", "webhook.key.provision", "merchant.access.resolve"),
            "payflow-payment-api", Set.of("payment.read", "payment.create"));

    private KeycloakAccessTokens() {}

    public static JwtDecoder decoder(String issuer, String jwks, String audience) {
        if (issuer.isBlank() || jwks.isBlank() || audience.isBlank()) {
            throw new IllegalArgumentException("Issuer, JWKS and audience are required");
        }
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(new RestTemplate(factory)).build();
        OAuth2TokenValidator<Jwt> contract = jwt -> {
            if (jwt.getAudience() == null || !jwt.getAudience().contains(audience) || !"Bearer".equals(jwt.getClaimAsString("typ"))
                    || jwt.getIssuedAt() == null || jwt.getExpiresAt() == null
                    || jwt.getIssuedAt().isAfter(Instant.now().plusSeconds(30))
                    || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid access-token claims", null));
            }
            return OAuth2TokenValidatorResult.success();
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ofSeconds(30)), new JwtIssuerValidator(issuer), contract));
        return token -> {
            Jwt verified = decoder.decode(token);
            // Nimbus normalizes some primitive claims (for example numeric sub -> text).
            // Inspect the signed original JSON too, so malformed identities cannot be coerced.
            try {
                Map<String, Object> original = JSON.readValue(Base64.getUrlDecoder().decode(token.split("\\.")[1]),
                                new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
                if (!validShape(original)) throw new BadJwtException("Invalid access-token claims");
            } catch (IllegalArgumentException exception) {
                throw new BadJwtException("Invalid access-token claims", exception);
            }
            return verified;
        };
    }

    private static boolean text(Object value) {
        return value instanceof String s && !s.isBlank();
    }

    private static boolean validShape(Map<String, Object> claims) {
        if (!text(claims.get("iss")) || !text(claims.get("sub")) || !text(claims.get("azp"))
                || !"Bearer".equals(claims.get("typ"))
                || !(claims.get("exp") instanceof Number) || !(claims.get("iat") instanceof Number)
                || (claims.containsKey("nbf") && !(claims.get("nbf") instanceof Number))) {
            return false;
        }
        Object aud = claims.get("aud");
        if (!(text(aud) || aud instanceof Collection<?> values && !values.isEmpty() && values.stream().allMatch(KeycloakAccessTokens::text))) {
            return false;
        }
        if (claims.containsKey("scope") && !(claims.get("scope") instanceof String)) {
            return false;
        }
        if (claims.containsKey("resource_access")) {
            if (!(claims.get("resource_access") instanceof Map<?, ?> resources)) return false;
            for (var entry : resources.entrySet()) {
                if (!text(entry.getKey()) || !(entry.getValue() instanceof Map<?, ?> resource)
                        || !(resource.get("roles") instanceof Collection<?> roles)
                        || !roles.stream().allMatch(KeycloakAccessTokens::text)) return false;
            }
        }
        return true;
    }

    public static Converter<Jwt, Collection<GrantedAuthority>> authorities(String audience) {
        return jwt -> {
            Set<GrantedAuthority> result = new LinkedHashSet<>();
            String scope = jwt.getClaimAsString("scope");
            if (scope != null) {
                for (String name : scope.split("\\s+")) {
                    if (SCOPES.getOrDefault(audience, Set.of()).contains(name)) {
                        result.add(new SimpleGrantedAuthority("SCOPE_" + name));
                    }
                }
            }
            Map<String, Object> resources = jwt.getClaim("resource_access");
            if (resources != null && resources.get(audience) instanceof Map<?, ?> resource
                    && resource.get("roles") instanceof Collection<?> roles) {
                for (Object role : roles) {
                    if (ROLES.getOrDefault(audience, Set.of()).contains(role)) {
                        result.add(new SimpleGrantedAuthority("ROLE_" + role));
                    }
                }
            }
            return result;
        };
    }
}
