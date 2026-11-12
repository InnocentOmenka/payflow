package com.innocodes.payflow.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@Import(ResourceServerJwtConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class FailClosedResourceServerConfiguration {
    @Bean
    SecurityFilterChain payflowSecurity(HttpSecurity http,
            @Value("${spring.security.oauth2.resourceserver.jwt.audiences}") String audience) throws Exception {
        return ResourceServerHttpSecurity.configure(http, audience)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .anyRequest().denyAll())
                .build();
    }
}
