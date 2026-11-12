package com.innocodes.payflow.payment.config;

import com.innocodes.payflow.payment.security.MerchantPaymentAuthorization;
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
    MerchantPaymentAuthorization merchantPaymentAuthorization(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
        return new MerchantPaymentAuthorization(issuer);
    }

    @Bean
    SecurityFilterChain paymentSecurity(HttpSecurity http, MerchantPaymentAuthorization authorization,
            @Value("${spring.security.oauth2.resourceserver.jwt.audiences}") String audience) throws Exception {
        return ResourceServerHttpSecurity.configure(http, audience)
                .authorizeHttpRequests(routes -> routes
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/payments")
                            .access((authentication, context) -> new AuthorizationDecision(authorization.allowed(authentication.get(), "payment.create")))
                        .requestMatchers(HttpMethod.GET, "/api/v1/payments/{paymentId}")
                            .access((authentication, context) -> new AuthorizationDecision(authorization.allowed(authentication.get(), "payment.read")))
                        .anyRequest().denyAll()).build();
    }
}
