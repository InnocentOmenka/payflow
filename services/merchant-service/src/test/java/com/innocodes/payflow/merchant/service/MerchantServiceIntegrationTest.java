package com.innocodes.payflow.merchant.service;

import com.innocodes.payflow.merchant.dto.request.RegisterMerchantRequest;
import com.innocodes.payflow.merchant.dto.request.UpdateMerchantProfileRequest;
import com.innocodes.payflow.merchant.dto.response.MerchantResponse;
import com.innocodes.payflow.merchant.enums.MerchantStatus;
import com.innocodes.payflow.merchant.repository.MerchantRepository;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties={"payflow.merchant.outbox.enabled=false","spring.kafka.admin.auto-create=false"})
@Testcontainers
class MerchantServiceIntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired MerchantService service;
    @Autowired MerchantRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JsonMapper mapper;
    private org.springframework.test.web.servlet.MockMvc controllerMvc;

    @BeforeEach
    void cleanIsolatedContainerDatabase() {
        repository.deleteAll(); // Only this test class's disposable PostgreSQL container.
        // Controller/validation + real database contract only. Runtime HTTP is tested separately
        // with the real security filters, which deny every business request in Slice 2.
        controllerMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new com.innocodes.payflow.merchant.controller.MerchantController(service))
                .setControllerAdvice(new com.innocodes.payflow.merchant.exception.GlobalExceptionHandler())
                .build();
    }

    @Test
    void shouldRegisterRetrieveAndNormalizeMerchantThroughController() throws Exception {
        var request = new RegisterMerchantRequest(" mer-1001 ", " Example Business ", " Owner@Example.com ", "+2348012345678");
        var response = request("POST", "", request);
        assertEquals(201, response.statusCode());
        var merchant = mapper.readValue(response.body(), MerchantResponse.class);
        assertEquals("MER-1001", merchant.merchantId());
        assertEquals("owner@example.com", merchant.businessEmail());
        assertEquals("Example Business", merchant.businessName());
        assertEquals(MerchantStatus.PENDING, merchant.status());
        assertEquals(0L, merchant.version());
        assertNotNull(merchant.createdAt());
        assertEquals(merchant.createdAt(), merchant.updatedAt());
        assertTrue(response.headers().firstValue("Location").orElseThrow().endsWith("/" + merchant.id()));
        assertEquals(200, request("GET", "/" + merchant.id(), null).statusCode());
        var lookup = request("GET", "/by-identifier/mer-1001", null);
        assertEquals(200, lookup.statusCode());
        assertEquals(merchant.id().toString(), json(lookup).get("id").asText());
        assertEquals(1, repository.count());
    }

    @ParameterizedTest @ValueSource(strings = {"merchantId", "businessEmail", "both"})
    void shouldReportDuplicateRegistrationDeterministically(String duplicate) throws Exception {
        var first = service.register(registration());
        var next = registration();
        var request = new RegisterMerchantRequest(duplicate.equals("businessEmail") ? next.merchantId() : first.merchantId(),
                "Another business", duplicate.equals("merchantId") ? next.businessEmail() : first.businessEmail().toUpperCase(), null);
        var response = request("POST", "", request);
        assertError(response, 409);
        assertEquals("Merchant identifier or business email already exists", json(response).get("message").asText());
        assertFalse(response.body().contains("23505"));
        assertEquals(1, repository.count());
    }

    @ParameterizedTest @ValueSource(strings = {"merchantId", "businessEmail", "businessName", "contactPhone"})
    void shouldRejectInvalidRegistrationApiRequests(String field) throws Exception {
        var fields = new java.util.HashMap<String, Object>(Map.of("merchantId", "MER-1234", "businessName", "Business",
                "businessEmail", "a@example.com", "contactPhone", "+2348012345678"));
        fields.put(field, field.equals("businessEmail") ? "not-an-email" : field.equals("contactPhone") ? "bad" : " ");
        var response = request("POST", "", fields);
        assertError(response, 400);
        assertTrue(json(response).get("details").toString().contains(field));
        assertEquals(0, repository.count());
    }

    @Test
    void shouldValidateDirectServiceRegistration() {
        assertThrows(ConstraintViolationException.class, () -> service.register(null));
        assertThrows(ConstraintViolationException.class, () -> service.register(new RegisterMerchantRequest("BAD", " ", "bad", null)));
        assertEquals(0, repository.count());
    }

    @Test
    void shouldUpdateProfileAndRejectStaleVersion() throws Exception {
        var merchant = service.register(registration());
        var updated = request("PUT", "/" + merchant.id() + "/profile",
                new UpdateMerchantProfileRequest(merchant.version(), " Updated Business ", " New@Example.com ", null));
        assertEquals(200, updated.statusCode());
        var result = mapper.readValue(updated.body(), MerchantResponse.class);
        assertEquals("Updated Business", result.businessName());
        assertEquals("new@example.com", result.businessEmail());
        assertNull(result.contactPhone());
        assertEquals(merchant.version() + 1, result.version());
        assertEquals(merchant.merchantId(), result.merchantId());
        assertEquals(merchant.createdAt(), result.createdAt());
        assertFalse(result.updatedAt().isBefore(result.createdAt()));
        assertError(request("PUT", "/" + merchant.id() + "/profile",
                new UpdateMerchantProfileRequest(merchant.version(), "Stale", "stale@example.com", null)), 409);
        assertEquals("Updated Business", service.getById(merchant.id()).businessName());
    }

    @Test
    void shouldRollBackProfileChangesWhenEmailIsTaken() throws Exception {
        var first = service.register(registration());
        var second = service.register(registration());
        var response = request("PUT", "/" + second.id() + "/profile",
                new UpdateMerchantProfileRequest(second.version(), "Must roll back", first.businessEmail(), null));
        assertError(response, 409);
        var unchanged = service.getById(second.id());
        assertEquals(second.businessName(), unchanged.businessName());
        assertEquals(second.businessEmail(), unchanged.businessEmail());
        assertEquals(second.version(), unchanged.version());
    }

    @Test
    void shouldApplyLifecycleOperationsAndKeepClosureTerminal() throws Exception {
        var merchant = service.register(registration());
        String path = "/" + merchant.id();
        String[] operations = {"activate", "suspend", "activate", "close"};
        String[] states = {"ACTIVE", "SUSPENDED", "ACTIVE", "CLOSED"};
        for (int i = 0; i < operations.length; i++) {
            var response = request("POST", path + "/" + operations[i], Map.of("version", i));
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(states[i], json(response).get("status").asText());
            assertEquals(i + 1, json(response).get("version").asInt());
        }
        for (String operation : operations) assertError(request("POST", path + "/" + operation, Map.of("version", 4)), 409);
        assertError(request("PUT", path + "/profile", new UpdateMerchantProfileRequest(4L, "Changed", "changed@example.com", null)), 409);
        assertEquals(4L, service.getById(merchant.id()).version());
    }

    @Test
    void shouldRejectInvalidPendingSuspensionAndStaleLifecycleVersion() throws Exception {
        var merchant = service.register(registration());
        assertError(request("POST", "/" + merchant.id() + "/suspend", Map.of("version", 0)), 409);
        assertEquals(MerchantStatus.PENDING, service.getById(merchant.id()).status());
        assertError(request("POST", "/" + merchant.id() + "/activate", Map.of("version", 1)), 409);
        assertEquals(0L, service.getById(merchant.id()).version());
    }

    @ParameterizedTest @ValueSource(strings = {"PENDING", "ACTIVE", "SUSPENDED"})
    void shouldAllowClosingEveryNonTerminalState(String source) {
        var merchant = service.register(registration());
        if (!source.equals("PENDING")) merchant = service.activate(merchant.id(), merchant.version());
        if (source.equals("SUSPENDED")) merchant = service.suspend(merchant.id(), merchant.version());
        assertEquals(MerchantStatus.CLOSED, service.close(merchant.id(), merchant.version()).status());
    }

    @Test
    void shouldPaginateAndFilterByStatusAndCaseInsensitiveName() throws Exception {
        var first = service.register(new RegisterMerchantRequest("MER-ALPHA", "Alpha Shop", "alpha@example.com", null));
        var second = service.register(new RegisterMerchantRequest("MER-BETA", "Beta Shop", "beta@example.com", null));
        service.register(new RegisterMerchantRequest("MER-GAMMA", "Gamma Supplies", "gamma@example.com", null));
        service.activate(first.id(), first.version());
        service.activate(second.id(), second.version());
        var response = request("GET", "?status=ACTIVE&search=sHoP&page=0&size=1", null);
        assertEquals(200, response.statusCode());
        var page = json(response);
        assertEquals(2, page.get("totalElements").asInt());
        assertEquals(2, page.get("totalPages").asInt());
        assertTrue(page.get("first").asBoolean());
        assertFalse(page.get("last").asBoolean());
        assertEquals(1, page.get("content").size());
        var next = json(request("GET", "?status=ACTIVE&search=shop&page=1&size=1", null));
        assertNotEquals(page.get("content").get(0).get("id"), next.get("content").get(0).get("id"));
        assertTrue(next.get("last").asBoolean());
        assertEquals(0, json(request("GET", "?status=CLOSED", null)).get("content").size());
    }

    @Test
    void shouldTreatSearchWildcardsAsLiteralCharacters() {
        service.register(new RegisterMerchantRequest("MER-PERCENT", "100% Cafe", "percent@example.com", null));
        service.register(new RegisterMerchantRequest("MER-OTHER", "100percent Cafe", "other@example.com", null));
        assertEquals(1, service.list(0, 20, null, "%").totalElements());
        assertEquals(0, service.list(0, 20, null, "_").totalElements());
    }

    @ParameterizedTest @ValueSource(strings = {"?page=-1", "?size=0", "?size=101", "?status=UNKNOWN", "?page=bad", "/not-a-uuid"})
    void shouldReturnConsistentErrorsForInvalidParameters(String suffix) throws Exception {
        assertError(request("GET", suffix, null), 400);
    }

    @Test
    void shouldReturnNotFoundAndMalformedBodyErrors() throws Exception {
        assertError(request("GET", "/" + UUID.randomUUID(), null), 404);
        assertError(request("GET", "/by-identifier/MER-MISSING", null), 404);
        assertError(raw("POST", "", "{broken"), 400);
        assertError(raw("POST", "", "null"), 400);
        var merchant = service.register(registration());
        assertError(request("POST", "/" + merchant.id() + "/activate", Map.of()), 400);
        assertError(request("POST", "/" + merchant.id() + "/activate", Map.of("version", -1)), 400);
        assertError(request("PUT", "/" + merchant.id() + "/profile", Map.of("businessName", "Name")), 400);
    }

    @Test
    void shouldEnforceDatabaseConstraintsForDirectWrites() {
        var first = service.register(registration());
        var second = service.register(registration());
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE merchants SET business_email=? WHERE id=?", first.businessEmail(), second.id()));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE merchants SET merchant_id=? WHERE id=?", first.merchantId(), second.id()));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE merchants SET status='UNKNOWN' WHERE id=?", second.id()));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE merchants SET version=-1 WHERE id=?", second.id()));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE merchants SET business_email='UPPER@example.com' WHERE id=?", second.id()));
        assertEquals(second.businessEmail(), service.getById(second.id()).businessEmail());
    }

    @Test
    void shouldRollBackProfileAndStatusChangesInOneTransaction() {
        var merchant = service.register(registration());
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var updated = service.updateProfile(merchant.id(), new UpdateMerchantProfileRequest(0L, "Rollback", "rollback@example.com", null));
            service.activate(merchant.id(), updated.version());
            throw new IllegalStateException("Injected failure after both writes");
        }));
        var reloaded = service.getById(merchant.id());
        assertEquals(merchant.businessName(), reloaded.businessName());
        assertEquals(merchant.businessEmail(), reloaded.businessEmail());
        assertEquals(MerchantStatus.PENDING, reloaded.status());
        assertEquals(0L, reloaded.version());
        assertEquals(MerchantStatus.ACTIVE, service.activate(merchant.id(), 0L).status());
    }

    @Test
    void shouldPreventLostUpdatesAcrossConcurrentProfileAndLifecycleChanges() throws Exception {
        var merchant = service.register(registration());
        var barrier = new CyclicBarrier(2);
        Callable<Throwable> profile = () -> outcome(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            repository.findById(merchant.id()).orElseThrow();
            cross(barrier);
            service.updateProfile(merchant.id(), new UpdateMerchantProfileRequest(0L, "Concurrent profile", "concurrent@example.com", null));
        }));
        Callable<Throwable> lifecycle = () -> outcome(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            repository.findById(merchant.id()).orElseThrow();
            cross(barrier);
            service.activate(merchant.id(), 0L);
        }));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(profile);
            var b = executor.submit(lifecycle);
            Throwable first = a.get(10, TimeUnit.SECONDS), second = b.get(10, TimeUnit.SECONDS);
            assertTrue((first == null) != (second == null));
            assertInstanceOf(OptimisticLockingFailureException.class, first == null ? second : first);
            var winner = service.getById(merchant.id());
            assertEquals(1L, winner.version());
            if (first == null) {
                assertEquals("Concurrent profile", winner.businessName());
                assertEquals(MerchantStatus.PENDING, winner.status());
            } else {
                assertEquals(merchant.businessName(), winner.businessName());
                assertEquals(MerchantStatus.ACTIVE, winner.status());
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"merchantId", "businessEmail"})
    void shouldEnforceUniquenessDuringConcurrentRegistration(String duplicate) throws Exception {
        var first = registration();
        var second = registration();
        var conflicting = new RegisterMerchantRequest(duplicate.equals("merchantId") ? first.merchantId() : second.merchantId(),
                second.businessName(), duplicate.equals("businessEmail") ? first.businessEmail() : second.businessEmail(), null);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> { cross(barrier); return outcome(() -> service.register(first)); });
            var b = executor.submit(() -> { cross(barrier); return outcome(() -> service.register(conflicting)); });
            Throwable x = a.get(10, TimeUnit.SECONDS), y = b.get(10, TimeUnit.SECONDS);
            assertTrue((x == null) != (y == null));
            assertInstanceOf(DataIntegrityViolationException.class, x == null ? y : x);
        }
        assertEquals(1, repository.count());
    }

    private Throwable outcome(Runnable action) {
        try { action.run(); return null; } catch (RuntimeException exception) { return exception; }
    }

    private void cross(CyclicBarrier barrier) {
        try { barrier.await(5, TimeUnit.SECONDS); } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private RegisterMerchantRequest registration() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        return new RegisterMerchantRequest("MER-" + suffix, "Business " + suffix, suffix + "@example.com", "+2348012345678");
    }

    private ControllerResponse request(String method, String path, Object body) throws Exception {
        return raw(method, path, body == null ? null : mapper.writeValueAsString(body));
    }

    private ControllerResponse raw(String method, String path, String body) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .request(org.springframework.http.HttpMethod.valueOf(method), URI.create("/api/v1/merchants" + path))
                .contentType("application/json");
        if (body != null) request.content(body);
        var response = controllerMvc.perform(request).andReturn().getResponse();
        var headers = new java.util.HashMap<String, java.util.List<String>>();
        response.getHeaderNames().forEach(name -> headers.put(name, response.getHeaders(name)));
        return new ControllerResponse(response.getStatus(), response.getContentAsString(),
                java.net.http.HttpHeaders.of(headers, (name, value) -> true));
    }

    private record ControllerResponse(int statusCode, String body, java.net.http.HttpHeaders headers) {}

    private JsonNode json(ControllerResponse response) { return mapper.readTree(response.body()); }

    private void assertError(ControllerResponse response, int status) {
        assertEquals(status, response.statusCode(), response.body());
        var error = json(response);
        assertEquals(status, error.get("status").asInt());
        assertNotNull(error.get("timestamp"));
        assertNotNull(error.get("error"));
        assertNotNull(error.get("message"));
        assertTrue(error.get("path").asText().startsWith("/api/v1/merchants"));
        assertTrue(error.get("details").isArray());
    }
}
