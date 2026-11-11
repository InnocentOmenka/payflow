package com.innocodes.payflow.security;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.web.bind.annotation.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = ResourceServerSecurityTest.Application.class, properties = {
        "spring.security.oauth2.resourceserver.jwt.audiences=payflow-merchant-api",
        "management.endpoints.web.exposure.include=health,info",
        "management.endpoint.health.show-details=never",
        "management.endpoint.health.show-components=never"
})
class ResourceServerSecurityTest {
    static final RSAKey KEY;
    static final RSAKey ATTACKER;
    static final HttpServer JWKS;
    static final AtomicInteger BUSINESS_CALLS = new AtomicInteger();
    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("trusted").generate();
            ATTACKER = new RSAKeyGenerator(2048).keyID("trusted").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/jwks", exchange -> {
                byte[] bytes = new JWKSet(KEY.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            JWKS.start();
        } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "https://identity.test/realms/payflow");
        properties.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({FailClosedResourceServerConfiguration.class, BusinessController.class})
    static class Application {}

    @RestController
    static class BusinessController {
        @RequestMapping({"/api/v1/merchants", "/api/v1/payments", "/internal/v1/merchant-access/resolve"})
        String business() { BUSINESS_CALLS.incrementAndGet(); return "must never run"; }
    }

    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy filters;
    @Autowired JwtDecoder decoder;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        BUSINESS_CALLS.set(0);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filters).build();
    }

    @AfterAll static void stopFixture() { JWKS.stop(0); }

    static Map<String, Object> claims() {
        var claims = new HashMap<String, Object>();
        claims.put("iss", "https://identity.test/realms/payflow");
        claims.put("sub", "tenant-a-user");
        claims.put("azp", "payflow-portal");
        claims.put("aud", List.of("payflow-merchant-api"));
        claims.put("typ", "Bearer");
        claims.put("iat", Date.from(Instant.now().minusSeconds(5)));
        claims.put("exp", Date.from(Instant.now().plusSeconds(300)));
        claims.put("scope", "merchant.read webhook.manage");
        claims.put("resource_access", Map.of("payflow-merchant-api", Map.of("roles", List.of("merchant-admin"))));
        return claims;
    }

    static String token(Map<String, Object> claims) throws Exception { return token(claims, KEY, JWSAlgorithm.RS256); }
    static String token(Map<String, Object> claims, RSAKey key, JWSAlgorithm algorithm) throws Exception {
        var builder = new JWTClaimsSet.Builder();
        claims.forEach(builder::claim);
        var jwt = new SignedJWT(new JWSHeader.Builder(algorithm).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(), builder.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    void unauthorized(String token) throws Exception {
        mvc.perform(get("/api/v1/merchants").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.status").value(401)).andExpect(jsonPath("$.details").isArray())
                .andExpect(header().string("Cache-Control", "no-store"));
        assertEquals(0, BUSINESS_CALLS.get());
    }

    @Test void rejectsWrongSignature() throws Exception { unauthorized(token(claims(), ATTACKER, JWSAlgorithm.RS256)); }
    @Test void rejectsUnapprovedAlgorithm() throws Exception { unauthorized(token(claims(), KEY, JWSAlgorithm.RS512)); }
    @Test void rejectsMalformedToken() throws Exception { unauthorized("not-a-jwt"); }
    @Test void rejectsWrongIssuer() throws Exception { var c = claims(); c.put("iss", "https://attacker.test"); unauthorized(token(c)); }
    @Test void rejectsWrongAudience() throws Exception { var c = claims(); c.put("aud", List.of("payflow-payment-api")); unauthorized(token(c)); }
    @Test void rejectsExpiredToken() throws Exception { var c = claims(); c.put("exp", Date.from(Instant.now().minusSeconds(120))); unauthorized(token(c)); }
    @Test void rejectsFutureNotBefore() throws Exception { var c = claims(); c.put("nbf", Date.from(Instant.now().plusSeconds(120))); unauthorized(token(c)); }
    @Test void rejectsFutureIssuedAt() throws Exception { var c = claims(); c.put("iat", Date.from(Instant.now().plusSeconds(120))); unauthorized(token(c)); }
    @Test void rejectsIdToken() throws Exception { var c = claims(); c.put("typ", "ID"); unauthorized(token(c)); }

    @ParameterizedTest @ValueSource(strings = {"iss", "sub", "aud", "azp", "typ", "exp", "iat"})
    void rejectsMissingRequiredClaim(String name) throws Exception { var c = claims(); c.remove(name); unauthorized(token(c)); }

    @ParameterizedTest @ValueSource(strings = {"sub", "azp", "scope", "resource_access"})
    void rejectsMalformedClaims(String name) throws Exception { var c = claims(); c.put(name, 123); unauthorized(token(c)); }

    @Test void rejectsBlankSubject() throws Exception { var c = claims(); c.put("sub", " "); unauthorized(token(c)); }
    @Test void rejectsMalformedRoles() throws Exception { var c = claims(); c.put("resource_access", Map.of("payflow-merchant-api", Map.of("roles", "merchant-admin"))); unauthorized(token(c)); }

    @ParameterizedTest @ValueSource(strings = {"/api/v1/merchants", "/api/v1/payments", "/actuator", "/actuator/info", "/actuator/health/readiness", "/unknown", "/internal/v1/merchant-access/resolve"})
    void missingBearerIs401(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @ParameterizedTest @ValueSource(strings = {"/api/v1/merchants", "/api/v1/payments", "/actuator", "/actuator/info", "/actuator/health/readiness", "/unknown", "/internal/v1/merchant-access/resolve"})
    void validAdminJwtStillCannotAccessAnyProtectedRoute(String path) throws Exception {
        var c = claims();
        c.put("resource_access", Map.of("payflow-merchant-api", Map.of("roles", List.of("merchant-admin", "platform-admin"))));
        mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token(c)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Access is denied"));
        assertEquals(0, BUSINESS_CALLS.get());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "POST,/api/v1/merchants", "GET,/api/v1/merchants/00000000-0000-0000-0000-000000000001",
            "GET,/api/v1/merchants/by-identifier/MER-OTHER",
            "PUT,/api/v1/merchants/00000000-0000-0000-0000-000000000001/profile",
            "POST,/api/v1/merchants/00000000-0000-0000-0000-000000000001/activate",
            "POST,/api/v1/merchants/00000000-0000-0000-0000-000000000001/suspend",
            "POST,/api/v1/merchants/00000000-0000-0000-0000-000000000001/close",
            "GET,/api/v1/merchants/00000000-0000-0000-0000-000000000001/webhook",
            "PUT,/api/v1/merchants/00000000-0000-0000-0000-000000000001/webhook",
            "POST,/api/v1/merchants/00000000-0000-0000-0000-000000000001/webhook/replay",
            "POST,/api/v1/merchants/00000000-0000-0000-0000-000000000001/webhook/secrets",
            "POST,/api/v1/payments", "GET,/api/v1/payments/00000000-0000-0000-0000-000000000001"
    })
    void everyExistingBusinessOperationIsDeniedEvenWithAdminJwt(String method, String path) throws Exception {
        var c = claims();
        c.put("resource_access", Map.of("payflow-merchant-api", Map.of("roles", List.of("merchant-admin", "platform-admin"))));
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(c)))
                .andExpect(status().isForbidden());
        assertEquals(0, BUSINESS_CALLS.get());
    }

    @Test void writesDeniedBeforeBodyValidationWithoutSessionOrCsrf() throws Exception {
        var response = mvc.perform(post("/api/v1/merchants").contentType("application/json").content("{broken")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(claims())))
                .andExpect(status().isForbidden()).andReturn();
        assertNull(response.getRequest().getSession(false));
        assertEquals(0, BUSINESS_CALLS.get());
    }

    @Test void rolelessTokenHasNoBusinessAccess() throws Exception {
        var c = claims(); c.remove("resource_access"); c.remove("scope");
        mvc.perform(get("/api/v1/merchants").header(HttpHeaders.AUTHORIZATION, "Bearer " + token(c)))
                .andExpect(status().isForbidden());
    }

    @Test void minimalAnonymousHealth() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.details").doesNotExist()).andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(post("/actuator/health")).andExpect(status().isUnauthorized());
    }

    @Test void identityHeadersAndQueryTokensAreNotAuthentication() throws Exception {
        mvc.perform(get("/api/v1/merchants").param("access_token", token(claims()))
                .header("X-Merchant-Id", "MER-VICTIM").header("X-User-Id", "admin").header("X-Forwarded-User", "admin"))
                .andExpect(status().isUnauthorized());
    }

    @Test void noBasicOrFormLogin() throws Exception {
        mvc.perform(get("/api/v1/merchants").header("Authorization", "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
        mvc.perform(post("/login").param("username", "admin")).andExpect(status().isUnauthorized());
    }

    @Test void corsRejectsUnapprovedOrigin() throws Exception {
        mvc.perform(options("/api/v1/merchants").header("Origin", "https://attacker.test")
                .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test void onlyExpectedApiAndAllowlistedAuthoritiesAreExtracted() throws Exception {
        var c = claims();
        c.put("scope", "merchant.read payment.create made.up");
        c.put("realm_access", Map.of("roles", List.of("realm-admin")));
        c.put("resource_access", Map.of("payflow-merchant-api", Map.of("roles", List.of("merchant-admin", "unknown")),
                "payflow-payment-api", Map.of("roles", List.of("merchant-operator"))));
        var jwt = decoder.decode(token(c));
        var authorities = KeycloakAccessTokens.authorities("payflow-merchant-api").convert(jwt).stream().map(Object::toString).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("SCOPE_merchant.read", "ROLE_merchant-admin"), authorities);
        assertTrue(KeycloakAccessTokens.authorities("payflow-ledger-api").convert(jwt).isEmpty());
    }

    @Test void paymentAudienceUsesOnlyPaymentRolesAndScopes() throws Exception {
        var c = claims(); c.put("aud", List.of("payflow-payment-api"));
        c.put("scope", "payment.read payment.create merchant.read");
        c.put("resource_access", Map.of("payflow-payment-api", Map.of("roles", List.of("merchant-operator")),
                "payflow-merchant-api", Map.of("roles", List.of("platform-admin"))));
        var paymentDecoder = KeycloakAccessTokens.decoder("https://identity.test/realms/payflow",
                "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks", "payflow-payment-api");
        var authorities = KeycloakAccessTokens.authorities("payflow-payment-api").convert(paymentDecoder.decode(token(c)))
                .stream().map(Object::toString).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("SCOPE_payment.read", "SCOPE_payment.create", "ROLE_merchant-operator"), authorities);
    }

    @Test void unsignedTokenCannotAuthenticate() throws Exception {
        var builder = new JWTClaimsSet.Builder(); claims().forEach(builder::claim);
        unauthorized(new PlainJWT(builder.build()).serialize());
    }

    @Test void signingKeyOutageFailsClosed() throws Exception {
        var unavailable = KeycloakAccessTokens.decoder("https://identity.test/realms/payflow", "http://127.0.0.1:1/jwks", "payflow-merchant-api");
        assertThrows(org.springframework.security.oauth2.jwt.JwtException.class, () -> unavailable.decode(token(claims())));
    }
}
