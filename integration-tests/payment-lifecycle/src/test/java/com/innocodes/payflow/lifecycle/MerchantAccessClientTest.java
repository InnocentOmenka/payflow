package com.innocodes.payflow.lifecycle;

import com.innocodes.payflow.payment.merchant.AuthorizationUnavailableException;
import com.innocodes.payflow.payment.merchant.MerchantAccessClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class MerchantAccessClientTest {
    TestIdentityProvider identity;
    HttpServer resolver;
    MerchantAccessClient client;
    int status = 200;
    String response;
    final AtomicInteger redirects = new AtomicInteger();

    @BeforeEach void start() throws Exception {
        identity = new TestIdentityProvider();
        resolver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        resolver.createContext("/internal/v1/merchant-access/resolve", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (status == 302) exchange.getResponseHeaders().set("Location", "/redirect-target");
            byte[] body = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
            exchange.close();
        });
        resolver.createContext("/redirect-target", exchange -> {
            redirects.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close();
        });
        resolver.start();
        response = JsonMapper.builder().build().writeValueAsString(Map.of("issuer", identity.issuer(), "subject", "human",
                "merchantUuid", UUID.randomUUID(), "merchantId", "MER-CLIENT-TEST", "role", "MERCHANT_OPERATOR", "status", "ACTIVE"));
        client = new MerchantAccessClient(url(), identity.url() + "/token", identity.secret);
    }

    String url() { return "http://127.0.0.1:" + resolver.getAddress().getPort(); }

    @AfterEach void stop() {
        if (resolver != null) resolver.stop(0);
        if (identity != null) identity.close();
    }

    @Test void resolverDenialIsNotAnAllowAndDependencyFailuresAreSanitized() {
        status = 403;
        assertThrows(AccessDeniedException.class, () -> client.resolve(identity.issuer(), "human"));
        for (int code : new int[]{201, 206, 401, 404, 500, 503}) {
            status = code;
            var failure = assertThrows(AuthorizationUnavailableException.class, () -> client.resolve(identity.issuer(), "human"));
            assertEquals("Authorization service unavailable", failure.getMessage());
            assertNull(failure.getCause());
        }
    }

    @Test void malformedIncompleteAndMismatchedResponsesFailClosed() {
        for (String invalid : new String[]{"{broken", "{}", response.replace("\"human\"", "\"other\""),
                response.replace("MERCHANT_OPERATOR", "PLATFORM_ADMIN"), response.replace("ACTIVE", "UNKNOWN")}) {
            response = invalid;
            assertThrows(AuthorizationUnavailableException.class, () -> client.resolve(identity.issuer(), "human"));
        }
    }

    @Test void stoppedResolverAndRedirectsFailClosedWithoutFollowingRedirects() {
        status = 302;
        assertThrows(AuthorizationUnavailableException.class, () -> client.resolve(identity.issuer(), "human"));
        assertEquals(0, redirects.get());
        resolver.stop(0);
        assertThrows(AuthorizationUnavailableException.class, () -> client.resolve(identity.issuer(), "human"));
    }

    @Test void missingCredentialsAndTokenEndpointFailureFailClosed() {
        var missing = new MerchantAccessClient(url(), identity.url() + "/token", "");
        assertThrows(AuthorizationUnavailableException.class, () -> missing.resolve(identity.issuer(), "human"));
        identity.tokenStatus = 503;
        assertThrows(AuthorizationUnavailableException.class, () -> client.resolve(identity.issuer(), "human"));
    }
}
