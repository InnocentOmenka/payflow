package com.innocodes.payflow.lifecycle;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Local signed-token/JWKS fixture, not a replacement for production Keycloak. */
final class TestIdentityProvider implements AutoCloseable {
    final String secret = UUID.randomUUID().toString();
    final RSAKey key = new RSAKeyGenerator(2048).keyID("tenant-test").generate();
    final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    volatile int tokenStatus = 200;
    volatile boolean malformedToken;

    TestIdentityProvider() throws Exception {
        server.createContext("/jwks", exchange -> {
            byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
            exchange.close();
        });
        server.createContext("/token", exchange -> {
            try {
                String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String basic = "Basic " + Base64.getEncoder().encodeToString(
                        ("payflow-payment-service:" + secret).getBytes(StandardCharsets.UTF_8));
                boolean accepted = "POST".equals(exchange.getRequestMethod())
                        && basic.equals(exchange.getRequestHeaders().getFirst("Authorization"))
                        && form.contains("grant_type=client_credentials") && form.contains("scope=merchant.access.resolve");
                int status = accepted ? tokenStatus : 401;
                String token = malformedToken ? "invalid" : token(machineClaims());
                byte[] body = JsonMapper.builder().build().writeValueAsBytes(status == 200
                        ? Map.of("access_token", token, "token_type", "Bearer", "expires_in", 30,
                                "scope", "merchant.access.resolve")
                        : Map.of("error", "temporarily_unavailable"));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            } catch (Exception failure) {
                exchange.sendResponseHeaders(500, -1);
            } finally { exchange.close(); }
        });
        server.start();
    }

    String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    String issuer() { return url() + "/realms/payflow"; }
    Map<String, Object> claims(String subject, String audience, String client, String scope, String role) {
        var claims = new HashMap<String, Object>();
        claims.put("iss", issuer()); claims.put("sub", subject); claims.put("aud", List.of(audience));
        claims.put("azp", client); claims.put("typ", "Bearer"); claims.put("scope", scope);
        claims.put("iat", Date.from(Instant.now().minusSeconds(5)));
        claims.put("exp", Date.from(Instant.now().plusSeconds(300)));
        claims.put("resource_access", Map.of(audience, Map.of("roles", List.of(role))));
        return claims;
    }
    Map<String, Object> machineClaims() {
        return claims("machine-subject", "payflow-merchant-api", "payflow-payment-service", "merchant.access.resolve", "access-resolver");
    }
    String user(String subject, String role) throws Exception {
        return token(claims(subject, "payflow-payment-api", "payflow-portal", "payment.create payment.read", role));
    }
    String token(Map<String, Object> claims) throws Exception {
        var builder = new JWTClaimsSet.Builder(); claims.forEach(builder::claim);
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), builder.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
    @Override public void close() { server.stop(0); }
}
