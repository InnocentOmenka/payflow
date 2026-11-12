package com.innocodes.payflow.merchant.config;

import com.innocodes.payflow.merchant.security.PlatformAuthorization;
import com.innocodes.payflow.security.ResourceServerHttpSecurity;
import com.innocodes.payflow.security.ResourceServerJwtConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@Import(ResourceServerJwtConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {
    @Bean
    PlatformAuthorization platformAuthorization(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
        return new PlatformAuthorization(issuer);
    }

    @Bean
    SecurityFilterChain merchantSecurity(HttpSecurity http, PlatformAuthorization platform,
            @Value("${spring.security.oauth2.resourceserver.jwt.audiences}") String audience) throws Exception {
        return ResourceServerHttpSecurity.configure(http, audience)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/merchants/{merchantId}/memberships")
                            .access((authentication, context) -> new AuthorizationDecision(platform.allowed(authentication.get())))
                        .requestMatchers(HttpMethod.POST, "/api/v1/merchants/{merchantId}/memberships",
                                "/api/v1/merchants/{merchantId}/memberships/{membershipId}/revoke")
                            .access((authentication, context) -> new AuthorizationDecision(platform.allowed(authentication.get())))
                        .requestMatchers(HttpMethod.PUT, "/api/v1/merchants/{merchantId}/memberships/{membershipId}")
                            .access((authentication, context) -> new AuthorizationDecision(platform.allowed(authentication.get())))
                        .anyRequest().denyAll())
                .build();
    }
}
