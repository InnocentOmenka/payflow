package com.innocodes.payflow.gateway;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.cloud.gateway.server.mvc.config.GatewayMvcProperties;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.function.*;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=127.0.0.1")
@Import(GatewaySecurityIntegrationTest.ProbeConfiguration.class)
class GatewaySecurityIntegrationTest {
    static final String ISSUER = "https://identity.test/realms/payflow";
    static final RSAKey KEY;
    static final HttpServer JWKS;
    static final HttpServer UPSTREAM;
    static final ExecutorService UPSTREAM_THREADS = Executors.newVirtualThreadPerTaskExecutor();
    static final List<Forwarded> FORWARDED = new CopyOnWriteArrayList<>();
    static volatile int upstreamStatus = 200;
    static volatile long upstreamDelay;
    static volatile boolean dropConnection;
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    static final AtomicInteger HANDLER_CALLS = new AtomicInteger();
    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("gateway-test").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/jwks", exchange -> {
                byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
                exchange.close();
            });
            JWKS.start();
            UPSTREAM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            UPSTREAM.setExecutor(UPSTREAM_THREADS);
            UPSTREAM.createContext("/", exchange -> {
                FORWARDED.add(new Forwarded(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                        Map.copyOf(exchange.getRequestHeaders())));
                try {
                    if (dropConnection) return;
                    if (upstreamDelay > 0) Thread.sleep(upstreamDelay);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.getResponseHeaders().set("X-Upstream-Test", "payment");
                    if (upstreamStatus == 302) exchange.getResponseHeaders().set("Location", "/redirect-target");
                    byte[] response = "{\"source\":\"payment\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(upstreamStatus, response.length);
                    try (var output = exchange.getResponseBody()) { output.write(response); }
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            UPSTREAM.start();
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }

    @DynamicPropertySource static void identity(DynamicPropertyRegistry properties) {
        properties.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        properties.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
        properties.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> "payflow-payment-api");
        properties.add("PAYFLOW_PAYMENT_SERVICE_URL", () -> upstreamUrl());
        properties.add("spring.http.clients.connect-timeout", () -> "200ms");
        properties.add("spring.http.clients.read-timeout", () -> "300ms");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        // A test-only servlet route proves security blocks handlers, not just missing paths.
        @Bean RouterFunction<ServerResponse> deniedProbe() {
            return RouterFunctions.route().GET("/internal/test-sentinel", request -> {
                HANDLER_CALLS.incrementAndGet();
                return ServerResponse.ok().body("must never execute");
            }).build();
        }
    }

    @Autowired WebServerApplicationContext context;
    @Autowired FilterChainProxy security;
    @Autowired GatewayMvcProperties gateway;
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach void resetUpstream() {
        FORWARDED.clear(); upstreamStatus = 200; upstreamDelay = 0; dropConnection = false;
    }
    @AfterAll static void closeFixtures() {
        HTTP.close(); JWKS.stop(0); UPSTREAM.stop(0); UPSTREAM_THREADS.shutdownNow();
    }
    static String upstreamUrl() { return "http://127.0.0.1:" + UPSTREAM.getAddress().getPort(); }
    record Forwarded(String method, String path, String body, Map<String, List<String>> headers) {
        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .findFirst().map(e -> e.getValue().getFirst()).orElse(null);
        }
        @Override public String toString() { return "Forwarded[redacted]"; }
    }
    @AfterEach void noHandlerExecuted() { assertEquals(0, HANDLER_CALLS.get()); }

    Map<String, Object> claims() {
        var claims = new HashMap<String, Object>();
        claims.put("iss", ISSUER); claims.put("sub", "merchant-user");
        claims.put("azp", "payflow-portal"); claims.put("aud", List.of("payflow-payment-api"));
        claims.put("typ", "Bearer"); claims.put("iat", Date.from(Instant.now().minusSeconds(5)));
        claims.put("exp", Date.from(Instant.now().plusSeconds(300)));
        claims.put("scope", "payment.create payment.read");
        claims.put("resource_access", Map.of("payflow-payment-api", Map.of("roles", List.of("merchant-admin"))));
        return claims;
    }
    String token(Map<String, Object> claims, RSAKey key) throws Exception {
        var builder = new JWTClaimsSet.Builder(); claims.forEach(builder::claim);
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), builder.build());
        jwt.sign(new RSASSASigner(key)); return jwt.serialize();
    }
    HttpResponse<String> request(String method, String path, String authorization) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + context.getWebServer().getPort() + path))
                .timeout(Duration.ofSeconds(5)).header("Accept", "application/json")
                .header("X-User-Id", "admin").header("X-Merchant-Id", "MER-SPOOFED")
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (authorization != null) request.header("Authorization", authorization);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    void denied(HttpResponse<String> response, int status) {
        assertTrue(FORWARDED.isEmpty(), "Denied requests must not reach Payment");
        assertEquals(status, response.statusCode());
        assertEquals(status, json.readTree(response.body()).get("status").asInt());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
        if (status == 401) assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElseThrow());
    }

    @Test void realServletApplicationStartsAndHealthIsMinimalAndAnonymous() throws Exception {
        assertTrue(context.getWebServer().getPort() > 0);
        assertEquals(1, security.getFilterChains().size());
        assertEquals(List.of("payment-create", "payment-retrieve"),
                gateway.getRoutes().stream().map(route -> route.getId()).toList());
        assertTrue(gateway.getRoutesMap().isEmpty());
        var response = request("GET", "/actuator/health", null);
        assertEquals(200, response.statusCode());
        var body = json.readTree(response.body());
        assertEquals("UP", body.get("status").asString());
        assertNull(body.get("components")); assertNull(body.get("details"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/payments", "/api/v1/merchants", "/internal/v1/merchant-access/resolve",
            "/internal/test-sentinel", "/actuator", "/actuator/info", "/actuator/gateway/routes",
            "/actuator/health/readiness", "/unknown"})
    void anonymousRequestsAre401EvenWithSpoofedHeaders(String path) throws Exception {
        denied(request("GET", path, null), 401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/payments",
            "/api/v1/merchants", "/internal/v1/merchant-access/resolve", "/internal/test-sentinel",
            "/actuator/info", "/actuator/gateway/routes", "/unknown"})
    void validJwtCannotReachUnapprovedPaths(String path) throws Exception {
        denied(request("GET", path, "Bearer " + token(claims(), KEY)), 403);
    }

    @ParameterizedTest @ValueSource(strings = {"POST", "PUT", "DELETE", "OPTIONS"})
    void writesAndNonGetHealthRemainProtected(String method) throws Exception {
        denied(request(method, "/actuator/health", null), 401);
        if (!method.equals("POST")) denied(request(method, "/api/v1/payments", "Bearer " + token(claims(), KEY)), 403);
        denied(request(method, "/internal/v1/merchant-access/resolve", "Bearer " + token(claims(), KEY)), 403);
    }

    @Test void malformedTokenAndBasicAuthenticationAreRejected() throws Exception {
        denied(request("GET", "/api/v1/payments", "Bearer invalid"), 401);
        denied(request("GET", "/api/v1/payments", "Basic Zm9vOmJhcg=="), 401);
        denied(request("GET", "/actuator/health", "Bearer invalid"), 401);
    }

    @ParameterizedTest @ValueSource(strings = {"issuer", "audience", "expired", "subject", "signature"})
    void signedInvalidTokensAreRejected(String condition) throws Exception {
        var claims = claims();
        switch (condition) {
            case "issuer" -> claims.put("iss", "https://untrusted.invalid");
            case "audience" -> claims.put("aud", List.of("payflow-merchant-api"));
            case "expired" -> claims.put("exp", Date.from(Instant.now().minusSeconds(120)));
            case "subject" -> claims.remove("sub");
        }
        RSAKey signingKey = condition.equals("signature") ? new RSAKeyGenerator(2048).generate() : KEY;
        denied(request("GET", "/api/v1/payments", "Bearer " + token(claims, signingKey)), 401);
    }

    HttpResponse<String> create(String authorization, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + context.getWebServer().getPort() + "/api/v1/payments"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .header("Idempotency-Key", "gateway-test-key")
                .header("X-User-Id", "spoofed").header("x-MERCHANT-id", "MER-SPOOFED")
                .header("X-User-Roles", "platform-admin").header("X-Roles", "platform-admin")
                .header("X-Principal-Subject", "spoofed").header("X-Authenticated-User", "spoofed")
                .header("X-Issuer", "https://untrusted.invalid")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) request.header("Authorization", authorization);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @ParameterizedTest @ValueSource(strings = {"merchant-admin", "merchant-operator"})
    void createsPaymentWithOriginalBearerBodyAndIdempotencyKeyAndStripsIdentityHeaders(String role) throws Exception {
        upstreamStatus = 202;
        var claims = claims(); claims.put("scope", "payment.create");
        claims.put("resource_access", Map.of("payflow-payment-api", Map.of("roles", List.of(role))));
        String authorization = "Bearer " + token(claims, KEY);
        String body = "{\"merchantId\":\"MER-TEST\",\"reference\":\"ORDER-1\",\"amount\":10.00,\"currency\":\"NGN\"}";
        var response = create(authorization, body);
        assertEquals(202, response.statusCode());
        assertEquals("{\"source\":\"payment\"}", response.body());
        assertEquals(1, FORWARDED.size());
        var forwarded = FORWARDED.getFirst();
        assertEquals("POST", forwarded.method()); assertEquals("/api/v1/payments", forwarded.path());
        assertEquals(body, forwarded.body());
        assertTrue(authorization.equals(forwarded.header("Authorization")), "Original bearer header must be preserved");
        assertEquals("gateway-test-key", forwarded.header("Idempotency-Key"));
        assertTrue(org.springframework.http.MediaType.APPLICATION_JSON.isCompatibleWith(
                org.springframework.http.MediaType.parseMediaType(forwarded.header("Content-Type"))));
        for (String name : List.of("X-User-Id", "X-Merchant-Id", "X-User-Roles", "X-Roles", "X-Principal-Subject",
                "X-Authenticated-User", "X-Issuer", "Forwarded", "X-Forwarded-For")) assertNull(forwarded.header(name));
    }

    @ParameterizedTest @ValueSource(strings = {"merchant-admin", "merchant-operator"})
    void retrievesPaymentWithUnchangedPathQueryAndBearer(String role) throws Exception {
        var claims = claims(); claims.put("scope", "payment.read");
        claims.put("resource_access", Map.of("payflow-payment-api", Map.of("roles", List.of(role))));
        String authorization = "Bearer " + token(claims, KEY);
        String path = "/api/v1/payments/00000000-0000-0000-0000-000000000001?view=test";
        var response = request("GET", path, authorization);
        assertEquals(200, response.statusCode());
        assertEquals("payment", response.headers().firstValue("X-Upstream-Test").orElseThrow());
        assertEquals(1, FORWARDED.size());
        assertEquals(path, FORWARDED.getFirst().path());
        assertTrue(authorization.equals(FORWARDED.getFirst().header("Authorization")));
    }

    @Test void missingOrInsufficientCapabilitiesNeverReachPayment() throws Exception {
        denied(create(null, "{}"), 401);
        for (var changes : List.<Map<String, Object>>of(Map.of("azp", "payflow-platform-console"),
                Map.of("azp", "payflow-payment-service"), Map.of("scope", "payment.read"),
                Map.of("scope", "payment.create.extra"), Map.of("resource_access", Map.of()),
                Map.of("resource_access", Map.of("payflow-merchant-api", Map.of("roles", List.of("merchant-admin")))),
                Map.of("resource_access", Map.of("payflow-payment-api", Map.of("roles", List.of("platform-admin")))))) {
            var claims = claims(); claims.putAll(changes);
            denied(create("Bearer " + token(claims, KEY), "{}"), 403);
        }
        var createOnly = claims(); createOnly.put("scope", "payment.create");
        denied(request("GET", "/api/v1/payments/" + UUID.randomUUID(), "Bearer " + token(createOnly, KEY)), 403);
    }

    @ParameterizedTest @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"})
    void retrievalRejectsOtherMethodsBeforeUpstream(String method) throws Exception {
        assertEquals(403, request(method, "/api/v1/payments/" + UUID.randomUUID(),
                "Bearer " + token(claims(), KEY)).statusCode());
        assertTrue(FORWARDED.isEmpty());
    }

    @ParameterizedTest @ValueSource(ints = {400, 401, 403, 404, 409, 500, 503})
    void preservesPaymentRejectionsAndFailuresWithoutRetry(int status) throws Exception {
        upstreamStatus = status;
        var response = create("Bearer " + token(claims(), KEY), "{}");
        assertEquals(status, response.statusCode());
        assertEquals("{\"source\":\"payment\"}", response.body());
        assertEquals(1, FORWARDED.size());
    }

    @Test void upstreamReadTimeoutIsBoundedAndPostIsNotRetried() throws Exception {
        upstreamDelay = 1500;
        long started = System.nanoTime();
        var response = create("Bearer " + token(claims(), KEY), "{}");
        assertEquals(504, response.statusCode());
        assertEquals("Payment service timed out", json.readTree(response.body()).get("message").asString());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(3)) < 0);
        assertEquals(1, FORWARDED.size());
    }

    @Test void droppedConnectionReturnsSanitizedBadGatewayWithoutPostRetry() throws Exception {
        dropConnection = true;
        var response = create("Bearer " + token(claims(), KEY), "{}");
        assertEquals(502, response.statusCode());
        assertEquals("Payment service unavailable", json.readTree(response.body()).get("message").asString());
        assertFalse(response.body().contains("127.0.0.1"));
        assertEquals(1, FORWARDED.size());
    }

    @Test void upstreamRedirectIsReturnedWithoutFollowingIt() throws Exception {
        upstreamStatus = 302;
        assertEquals(302, create("Bearer " + token(claims(), KEY), "{}").statusCode());
        assertEquals(1, FORWARDED.size());
    }
}
