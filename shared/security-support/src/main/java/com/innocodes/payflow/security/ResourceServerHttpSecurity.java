package com.innocodes.payflow.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** Stateless bearer setup only. Each application must declare its explicit route policy. */
public final class ResourceServerHttpSecurity {
    private ResourceServerHttpSecurity() {}
    public static HttpSecurity configure(HttpSecurity http, String audience) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(KeycloakAccessTokens.authorities(audience));
        var cors = new UrlBasedCorsConfigurationSource();
        // No browser API origin is approved yet. Same-origin requests remain possible.
        cors.registerCorsConfiguration("/**", new CorsConfiguration());
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .cors(config -> config.configurationSource(cors))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors.authenticationEntryPoint(SecurityErrors.unauthorized())
                        .accessDeniedHandler(SecurityErrors.forbidden()))
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(SecurityErrors.unauthorized())
                        .accessDeniedHandler(SecurityErrors.forbidden()));
        return http;
    }
}
