package com.innocodes.payflow.merchant.membership;

import com.innocodes.payflow.merchant.dto.request.RegisterMerchantRequest;
import com.innocodes.payflow.merchant.enums.MerchantMembershipRole;
import com.innocodes.payflow.merchant.repository.MerchantMembershipRepository;
import com.innocodes.payflow.merchant.service.MerchantService;
import com.innocodes.payflow.security.KeycloakAccessTokens;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"payflow.merchant.outbox.enabled=false",
        "spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false"})
@Testcontainers
class MembershipAdministrationIntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");
    static final String ISSUER = "https://identity.test/realms/payflow";
    static final RSAKey KEY;
    static final HttpServer JWKS;
    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("membership-test-key").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/jwks", exchange -> {
                byte[] json = new JWKSet(KEY.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, json.length);
                try (var output = exchange.getResponseBody()) { output.write(json); }
            });
            JWKS.start();
        } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        properties.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    }
    @AfterAll static void stopFixture() { JWKS.stop(0); }

    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy filters;
    @Autowired MerchantService merchants;
    @Autowired MembershipAdministrationService service;
    @Autowired MerchantMembershipRepository memberships;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @Autowired JwtDecoder decoder;
    @MockitoSpyBean MembershipAuditStore audit;
    MockMvc mvc;

    @BeforeEach void setup() {
        SecurityContextHolder.clearContext();
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filters).build();
    }
    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    Map<String, Object> claims() {
        var result = new HashMap<String, Object>();
        result.put("iss", ISSUER); result.put("sub", "verified-platform-subject");
        result.put("azp", "payflow-platform-console"); result.put("aud", List.of("payflow-merchant-api"));
        result.put("typ", "Bearer"); result.put("scope", "merchant.bindings.manage");
        result.put("iat", Date.from(Instant.now().minusSeconds(5)));
        result.put("exp", Date.from(Instant.now().plusSeconds(300)));
        result.put("resource_access", Map.of("payflow-merchant-api", Map.of("roles", List.of("platform-admin"))));
        return result;
    }
    String token(Map<String, Object> claims) throws Exception {
        var builder = new JWTClaimsSet.Builder(); claims.forEach(builder::claim);
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(KEY.getKeyID()).build(), builder.build());
        jwt.sign(new RSASSASigner(KEY)); return jwt.serialize();
    }
    String admin() throws Exception { return "Bearer " + token(claims()); }
    String path(UUID merchant) { return "/api/v1/merchants/" + merchant + "/memberships"; }
    UUID merchant() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        return merchants.register(new RegisterMerchantRequest("MER-" + suffix, "Membership Admin Test", suffix + "@example.test", null)).id();
    }
    CreateMembershipRequest request() { return new CreateMembershipRequest(ISSUER, UUID.randomUUID().toString(), MerchantMembershipRole.MERCHANT_OPERATOR); }
    MembershipResponse create(UUID merchant) throws Exception {
        var response = mvc.perform(post(path(merchant)).header("Authorization", admin()).contentType("application/json")
                .content(mapper.writeValueAsString(request())))
                .andExpect(status().isCreated()).andExpect(header().exists("Location")).andReturn().getResponse();
        return mapper.readValue(response.getContentAsString(), MembershipResponse.class);
    }
    <T> T asAdmin(Callable<T> call) throws Exception {
        var jwt = decoder.decode(token(claims()));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                KeycloakAccessTokens.authorities("payflow-merchant-api").convert(jwt)));
        try { return call.call(); } finally { SecurityContextHolder.clearContext(); }
    }
    int audits(UUID merchant) { return jdbc.queryForObject("SELECT count(*) FROM merchant_membership_audit WHERE merchant_uuid=?", Integer.class, merchant); }

    @Test void createsListsUpdatesAndRevokesWithTrustedActorAndAudit() throws Exception {
        UUID merchant = merchant(); var created = create(merchant);
        assertTrue(created.active()); assertEquals(0L, created.version());
        mvc.perform(get(path(merchant)).header("Authorization", admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].subject").value(created.subject()));
        mvc.perform(put(path(merchant) + "/" + created.id()).header("Authorization", admin()).contentType("application/json")
                .content("{\"version\":0,\"role\":\"MERCHANT_ADMIN\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(post(path(merchant) + "/" + created.id() + "/revoke").header("Authorization", admin()).contentType("application/json")
                .content("{\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false)).andExpect(jsonPath("$.version").value(2));
        assertEquals(List.of("GRANTED", "ROLE_CHANGED", "REVOKED"), jdbc.queryForList(
                "SELECT action FROM merchant_membership_audit WHERE membership_id=? ORDER BY after_version", String.class, created.id()));
        assertEquals(3, audits(merchant));
        var audit = jdbc.queryForMap("SELECT * FROM merchant_membership_audit WHERE membership_id=? AND after_version=2", created.id());
        assertEquals("verified-platform-subject", audit.get("actor_subject"));
        assertEquals("payflow-platform-console", audit.get("actor_client_id"));
        assertEquals(ISSUER, audit.get("actor_issuer"));
        assertEquals(created.subject(), audit.get("principal_subject"));
        var membership = memberships.findById(created.id()).orElseThrow();
        assertEquals(created.merchantId(), membership.getMerchantId());
        assertEquals(created.issuer(), membership.getIssuer());
        assertEquals(created.subject(), membership.getSubject());
        assertEquals("verified-platform-subject", membership.getChangedBySubject());
    }

    @Test void listIsPaginatedAndScopedToParent() throws Exception {
        UUID first = merchant(), second = merchant();
        create(first); create(first); create(second);
        mvc.perform(get(path(first)).param("size", "1").header("Authorization", admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content.length()").value(1));
        mvc.perform(get(path(first)).param("page", "-1").header("Authorization", admin())).andExpect(status().isBadRequest());
        mvc.perform(get(path(first)).param("size", "101").header("Authorization", admin())).andExpect(status().isBadRequest());
    }

    @Test void missingAuthenticationIs401BeforeAnyLookupOrBodyParsing() throws Exception {
        mvc.perform(post(path(UUID.randomUUID())).contentType("application/json").content("{broken"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.message").value("Bearer authentication is required"));
        mvc.perform(get(path(UUID.randomUUID())).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        assertThrows(InsufficientAuthenticationException.class, () -> service.list(UUID.randomUUID(), 0, 20));
    }

    static Stream<Arguments> forbiddenClaims() {
        return Stream.of(Arguments.of("azp", "payflow-payment-service"), Arguments.of("azp", "payflow-portal"),
                Arguments.of("scope", "merchant.read"), Arguments.of("scope", "merchant.bindings.manage.extra"),
                Arguments.of("resource_access", Map.of("payflow-merchant-api", Map.of("roles", List.of("merchant-admin")))),
                Arguments.of("resource_access", Map.of("payflow-payment-api", Map.of("roles", List.of("platform-admin")))),
                Arguments.of("resource_access", Map.of()), Arguments.of("scope", ""));
    }
    @ParameterizedTest @MethodSource("forbiddenClaims")
    void validButUnprivilegedTokensGet403WithoutObjectDisclosure(String claim, Object value) throws Exception {
        var claims = claims(); claims.put(claim, value);
        mvc.perform(post(path(UUID.randomUUID())).header("Authorization", "Bearer " + token(claims))
                .header("X-User-Id", "verified-platform-subject").header("X-Merchant-Id", "MER-OTHER")
                .contentType("application/json").content("{broken"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("Access is denied"));
        var jwt = decoder.decode(token(claims));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                KeycloakAccessTokens.authorities("payflow-merchant-api").convert(jwt)));
        assertThrows(AccessDeniedException.class, () -> service.list(UUID.randomUUID(), 0, 20));
    }

    @Test void serviceAuthorizationErrorsRemain401And403ThroughControllerAdvice() throws Exception {
        var direct = MockMvcBuilders.standaloneSetup(new MembershipController(service))
                .setControllerAdvice(new MembershipExceptionHandler(),
                        new com.innocodes.payflow.merchant.exception.GlobalExceptionHandler()).build();
        direct.perform(get(path(UUID.randomUUID())))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        var claims = claims(); claims.put("azp", "payflow-portal");
        var jwt = decoder.decode(token(claims));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                KeycloakAccessTokens.authorities("payflow-merchant-api").convert(jwt)));
        direct.perform(get(path(UUID.randomUUID())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("Access is denied"));
    }

    @Test void wrongIssuerAudienceSignatureAndExpiredTokensAre401() throws Exception {
        for (String key : List.of("iss", "aud", "exp")) {
            var claims = claims();
            claims.put(key, key.equals("iss") ? "https://attacker.test" : key.equals("aud") ? List.of("payflow-payment-api") : Date.from(Instant.now().minusSeconds(120)));
            mvc.perform(get(path(UUID.randomUUID())).header("Authorization", "Bearer " + token(claims))).andExpect(status().isUnauthorized());
        }
        String jwt = token(claims()); String[] parts = jwt.split("\\.");
        var attackerClaims = claims(); attackerClaims.put("sub", "tampered");
        String changed = token(attackerClaims).split("\\.")[1];
        mvc.perform(get(path(UUID.randomUUID())).header("Authorization", "Bearer " + parts[0] + "." + changed + "." + parts[2]))
                .andExpect(status().isUnauthorized());
    }

    @Test void notFoundAndWrongParentUseSameGenericResponse() throws Exception {
        UUID merchant = merchant(), other = merchant(); var created = create(merchant);
        String body = "{\"version\":0,\"role\":\"MERCHANT_ADMIN\"}";
        for (String url : List.of(path(other) + "/" + created.id(), path(merchant) + "/" + UUID.randomUUID())) {
            mvc.perform(put(url).header("Authorization", admin()).contentType("application/json").content(body))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Resource not found"));
        }
        mvc.perform(get(path(UUID.randomUUID())).header("Authorization", admin()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Resource not found"));
        assertEquals(0L, memberships.findById(created.id()).orElseThrow().getVersion());
        assertEquals(1, audits(merchant)); assertEquals(0, audits(other));
    }

    @ParameterizedTest @ValueSource(strings = {"issuer", "subject", "merchantId", "actorSubject"})
    void updatesRejectBindingAndActorFields(String field) throws Exception {
        UUID merchant = merchant(); var created = create(merchant);
        var input = new HashMap<String, Object>(); input.put("version", 0); input.put("role", "MERCHANT_ADMIN"); input.put(field, "forged");
        mvc.perform(put(path(merchant) + "/" + created.id()).header("Authorization", admin()).contentType("application/json")
                .content(mapper.writeValueAsString(input))).andExpect(status().isBadRequest());
        assertEquals(0L, memberships.findById(created.id()).orElseThrow().getVersion()); assertEquals(1, audits(merchant));
    }

    @Test void createRejectsForeignIssuerInvalidRolesAndActorSpoofing() throws Exception {
        UUID merchant = merchant();
        for (var input : List.of(Map.of("issuer", "https://other.test", "subject", "human", "role", "MERCHANT_ADMIN"),
                Map.of("issuer", ISSUER, "subject", "human", "role", "PLATFORM_ADMIN"),
                Map.of("issuer", ISSUER, "subject", "human", "role", "MERCHANT_ADMIN", "actorSubject", "forged"),
                Map.of("issuer", ISSUER, "subject", " ", "role", "MERCHANT_ADMIN"))) {
            mvc.perform(post(path(merchant)).header("Authorization", admin()).contentType("application/json")
                    .content(mapper.writeValueAsString(input))).andExpect(status().isBadRequest());
        }
        assertTrue(memberships.findByMerchantId(merchant).isEmpty()); assertEquals(0, audits(merchant));
    }

    @Test void duplicateAndStaleVersionConflictWithoutLeakingOwner() throws Exception {
        UUID merchant = merchant(), other = merchant(); var created = create(merchant);
        mvc.perform(post(path(other)).header("Authorization", admin()).contentType("application/json")
                .content(mapper.writeValueAsString(new CreateMembershipRequest(ISSUER, created.subject(), created.role()))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Membership conflicts with current state"));
        mvc.perform(put(path(merchant) + "/" + created.id()).header("Authorization", admin()).contentType("application/json")
                .content("{\"version\":99,\"role\":\"MERCHANT_ADMIN\"}"))
                .andExpect(status().isConflict());
        assertEquals(0, audits(other)); assertEquals(1, audits(merchant));
    }

    @Test void noOpChangesDoNotCreateFalseAuditEvents() throws Exception {
        UUID merchant = merchant(); var created = create(merchant);
        asAdmin(() -> service.updateRole(merchant, created.id(), new UpdateMembershipRequest(0L, created.role())));
        var revoked = asAdmin(() -> service.revoke(merchant, created.id(), new RevokeMembershipRequest(0L)));
        asAdmin(() -> service.revoke(merchant, created.id(), new RevokeMembershipRequest(revoked.version())));
        assertEquals(2, audits(merchant));
    }

    @Test void auditFailureRollsBackGrantAndRoleUpdate() throws Exception {
        UUID merchant = merchant(); var created = create(merchant); var request = request();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("private-diagnostic-not-for-response")).when(audit).append(anyString(), any(), any(), any());
        // GRANTED has a null before-state; Mockito any() also matches null.
        mvc.perform(post(path(merchant)).header("Authorization", admin()).contentType("application/json")
                .content(mapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.message").value("An unexpected error occurred"));
        assertTrue(memberships.findByIssuerAndSubject(ISSUER, request.subject()).isEmpty());
        assertThrows(org.springframework.dao.DataAccessException.class, () -> asAdmin(() -> service.updateRole(merchant, created.id(),
                new UpdateMembershipRequest(0L, MerchantMembershipRole.MERCHANT_ADMIN))));
        assertEquals(0L, memberships.findById(created.id()).orElseThrow().getVersion());
        assertEquals(created.role(), memberships.findById(created.id()).orElseThrow().getRole());
        assertEquals(1, audits(merchant));
    }

    @ParameterizedTest @ValueSource(strings = {"UPDATE merchant_membership_audit SET action='GRANTED' WHERE membership_id=?",
            "DELETE FROM merchant_membership_audit WHERE membership_id=?"})
    void auditRowsAreAppendOnly(String sql) throws Exception {
        UUID merchant = merchant(); var created = create(merchant);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql, created.id()));
        assertEquals(1, audits(merchant));
    }
    @Test void auditCannotBeTruncated() throws Exception {
        UUID merchant = merchant(); create(merchant);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.execute("TRUNCATE merchant_membership_audit"));
        assertEquals(1, audits(merchant));
    }

    @Test void concurrentRoleChangeAndRevocationHaveOneWinnerAndOneAudit() throws Exception {
        UUID merchant = merchant(); var created = create(merchant); var barrier = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> change = () -> compete(barrier, () -> service.updateRole(merchant, created.id(), new UpdateMembershipRequest(0L, MerchantMembershipRole.MERCHANT_ADMIN)));
            Callable<Boolean> revoke = () -> compete(barrier, () -> service.revoke(merchant, created.id(), new RevokeMembershipRequest(0L)));
            var a = workers.submit(change); var b = workers.submit(revoke);
            assertNotEquals(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
        assertEquals(1L, memberships.findById(created.id()).orElseThrow().getVersion()); assertEquals(2, audits(merchant));
    }
    private boolean compete(CyclicBarrier barrier, Callable<MembershipResponse> operation) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        try { asAdmin(operation); return true; } catch (MembershipConflictException exception) { return false; }
    }

    @Test void concurrentDuplicateGrantsRollBackLosingAudit() throws Exception {
        UUID first = merchant(), second = merchant(); var input = request(); var barrier = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> a = () -> grantConcurrently(barrier, first, input);
            Callable<Boolean> b = () -> grantConcurrently(barrier, second, input);
            var x = workers.submit(a); var y = workers.submit(b);
            assertNotEquals(x.get(20, TimeUnit.SECONDS), y.get(20, TimeUnit.SECONDS));
        }
        assertEquals(1, audits(first) + audits(second));
        assertTrue(memberships.findByIssuerAndSubject(ISSUER, input.subject()).isPresent());
    }
    private boolean grantConcurrently(CyclicBarrier barrier, UUID merchant, CreateMembershipRequest input) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        try { asAdmin(() -> service.create(merchant, input)); return true; }
        catch (DataIntegrityViolationException exception) { return false; }
    }

    @ParameterizedTest @ValueSource(strings = {"/api/v1/merchants", "/api/v1/payments", "/actuator/info",
            "/unknown", "/internal/v1/merchant-access/resolve"})
    void adminDoesNotOpenOtherBusinessOrManagementRoutes(String route) throws Exception {
        mvc.perform(get(route).header("Authorization", admin())).andExpect(status().isForbidden());
    }

    @Test void ownershipHeadersCannotAuthenticate() throws Exception {
        mvc.perform(get(path(UUID.randomUUID())).header("X-User-Id", "verified-platform-subject")
                .header("X-Role", "platform-admin")).andExpect(status().isUnauthorized());
    }
}
