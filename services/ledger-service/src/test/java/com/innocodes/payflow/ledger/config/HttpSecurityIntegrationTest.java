package com.innocodes.payflow.ledger.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Loads this service's actual configuration; no database, broker or IdP needed. */
@SpringJUnitConfig
@ContextConfiguration(classes = {SecurityConfig.class, HttpSecurityIntegrationTest.MvcConfiguration.class},
        initializers = ConfigDataApplicationContextInitializer.class)
@WebAppConfiguration
class HttpSecurityIntegrationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class MvcConfiguration {}

    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy security;
    MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
    }

    @Test void anonymousBusinessAndManagementRequestsAreDenied() throws Exception {
        for (String path : new String[]{"/api/v1/merchants", "/api/v1/payments", "/actuator/info", "/actuator", "/unknown"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(jsonPath("$.status").value(401));
        }
    }

    @Test void identityHeadersDoNotAuthenticateOrPermitWrites() throws Exception {
        mvc.perform(post("/api/v1/merchants").header("X-Merchant-Id", "MER-OTHER")
                .header("X-User-Id", "admin").contentType("application/json").content("{broken"))
                .andExpect(status().isUnauthorized());
    }
}
