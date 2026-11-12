package com.innocodes.payflow.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class ResourceServerJwtConfiguration {
    @Bean
    JwtDecoder payflowJwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwks,
            @Value("${spring.security.oauth2.resourceserver.jwt.audiences}") String audience) {
        return KeycloakAccessTokens.decoder(issuer, jwks, audience);
    }

}
