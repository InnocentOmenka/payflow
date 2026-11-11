package com.innocodes.payflow.ledger.config;

import com.innocodes.payflow.security.FailClosedResourceServerConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import(FailClosedResourceServerConfiguration.class)
public class SecurityConfig {
}
