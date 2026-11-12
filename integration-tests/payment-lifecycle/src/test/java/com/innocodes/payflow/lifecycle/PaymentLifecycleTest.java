package com.innocodes.payflow.lifecycle;

import com.innocodes.payflow.merchant.MerchantServiceApplication;
import com.innocodes.payflow.merchant.dto.request.RegisterMerchantRequest;
import com.innocodes.payflow.merchant.enums.MerchantStatus;
import com.innocodes.payflow.merchant.service.MerchantService;
import com.innocodes.payflow.merchant.entity.MerchantMembership;
import com.innocodes.payflow.merchant.enums.MerchantMembershipRole;
import com.innocodes.payflow.merchant.repository.MerchantMembershipRepository;
import com.innocodes.payflow.payment.dto.response.PaymentResponse;
import com.innocodes.payflow.merchant.webhook.UpdateWebhookRequest;
import com.innocodes.payflow.merchant.webhook.WebhookConfigurationService;
import com.innocodes.payflow.merchant.webhook.WebhookSecretService;
import com.innocodes.payflow.payment.PaymentServiceApplication;
import com.innocodes.payflow.payment.dto.request.CreatePaymentRequest;
import com.innocodes.payflow.payment.enums.PaymentStatus;
import com.innocodes.payflow.payment.service.PaymentService;
import com.innocodes.payflow.processor.TransactionProcessorApplication;
import com.innocodes.payflow.ledger.LedgerServiceApplication;
import com.innocodes.payflow.notification.NotificationServiceApplication;
import com.innocodes.payflow.notification.model.WebhookEnvelope;
import com.innocodes.payflow.webhook.ProvisionedSecret;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class PaymentLifecycleTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");
    @Container static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.2");
    @TempDir static Path temporary;
    static final List<ConfigurableApplicationContext> contexts = new ArrayList<>();
    static final List<URLClassLoader> loaders = new ArrayList<>();
    static final Map<UUID, ProvisionedSecret> keys = new ConcurrentHashMap<>();
    static final List<Received> received = new CopyOnWriteArrayList<>();
    static final JsonMapper json = JsonMapper.builder().build();
    static final BigDecimal AMOUNT = new BigDecimal("1250.00");
    static final Duration DEADLINE = Duration.ofSeconds(45);
    static ConfigurableApplicationContext merchant, payment, processor, ledger, notification;
    static HttpServer receiver;
    static ExecutorService receiverThreads;
    static TestIdentityProvider identity;
    static final HttpClient api = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @BeforeAll static void startPipeline() throws Exception {
        // All databases are created inside this fresh container, never on a host connection.
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            for (String name : List.of("merchant", "payment", "processor", "ledger", "notification")) {
                statement.execute("CREATE DATABASE " + name);
            }
        }
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic("payment-events", 3, (short) 1),
                    new NewTopic("payment-processed", 3, (short) 1),
                    new NewTopic("merchant-webhook-config", 3, (short) 1)
                            .configs(Map.of("cleanup.policy", "compact"))))
                    .all().get(20, TimeUnit.SECONDS);
        }
        Files.createDirectory(temporary.resolve("secrets"), PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
        byte[] master = new byte[32];
        new SecureRandom().nextBytes(master);
        Path masterFile = Files.createFile(temporary.resolve("master-key"), PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rw-------")));
        Files.write(masterFile, master);
        Arrays.fill(master, (byte) 0);
        identity = new TestIdentityProvider();
        startReceiver();
        merchant = start(MerchantServiceApplication.class, "merchant");
        notification = start(NotificationServiceApplication.class, "notification");
        ledger = start(LedgerServiceApplication.class, "ledger");
        processor = start(TransactionProcessorApplication.class, "processor");
        payment = start(PaymentServiceApplication.class, "payment");
    }

    private static ConfigurableApplicationContext start(Class<?> application, String database) throws Exception {
        // Each service owns application.yml and db/changelog at the same resource paths.
        // Select resources from that service only; do not merge migrations or component scans.
        var resources = new ServiceResources(application);
        loaders.add(resources);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(resources);
            var app = new SpringApplication(new DefaultResourceLoader(resources), application);
            app.setWebApplicationType(application == TransactionProcessorApplication.class
                    ? WebApplicationType.NONE : WebApplicationType.SERVLET);
            app.setRegisterShutdownHook(false);
            var context = app.run(
                    "--spring.config.location=" + Objects.requireNonNull(resources.getResource("application.yml")),
                    "--spring.profiles.active=dev",
                    "--server.address=127.0.0.1",
                    "--server.port=0",
                    "--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + identity.issuer(),
                    "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + identity.url() + "/jwks",
                    "--payflow.merchant-access.base-url=" + (merchant == null ? "http://127.0.0.1:9" : baseUrl(merchant)),
                    "--payflow.merchant-access.token-uri=" + identity.url() + "/token",
                    "--payflow.merchant-access.client-secret=" + identity.secret,
                    "--spring.datasource.url=jdbc:postgresql://" + postgres.getHost() + ":"
                            + postgres.getMappedPort(5432) + "/" + database,
                    "--spring.datasource.username=" + postgres.getUsername(),
                    "--spring.datasource.password=" + postgres.getPassword(),
                    "--spring.datasource.hikari.maximum-pool-size=3",
                    "--spring.jpa.show-sql=false",
                    "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                    "--spring.kafka.admin.fail-fast=true",
                    "--spring.kafka.listener.auto-startup=true",
                    "--spring.kafka.consumer.auto-offset-reset=earliest",
                    "--spring.kafka.consumer.properties.allow.auto.create.topics=false",
                    "--payflow.secrets.adapter=local-files",
                    "--payflow.secrets.directory=" + temporary.resolve("secrets"),
                    "--payflow.secrets.master-key-file=" + temporary.resolve("master-key"),
                    "--payflow.webhook.allow-local=true",
                    "--payflow.outbox.publisher.enabled=true",
                    "--payflow.merchant.outbox.enabled=true",
                    "--payflow.webhook.worker-enabled=true",
                    "--payflow.webhook.poll-interval-ms=100",
                    "--payflow.merchant.outbox.poll-ms=100",
                    "--logging.level.root=WARN",
                    "--spring.main.banner-mode=off");
            contexts.add(context);
            if (context instanceof WebServerApplicationContext web) {
                String path = application == PaymentServiceApplication.class ? "/api/v1/payments/" + UUID.randomUUID()
                        : application == MerchantServiceApplication.class ? "/api/v1/merchants" : "/actuator/info";
                try (var client = HttpClient.newHttpClient()) {
                    var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                            + web.getWebServer().getPort() + path)).GET().build(), HttpResponse.BodyHandlers.discarding());
                    assertEquals(401, response.statusCode(), "Existing anonymous HTTP restrictions remain enforced");
                }
            }
            return context;
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @AfterAll static void stopPipeline() throws Exception {
        // Close consumers/workers before JUnit disposes of their broker/databases and secrets.
        try {
            for (var context : contexts.reversed()) context.close();
        } finally {
            if (receiver != null) receiver.stop(0);
            if (receiverThreads != null) receiverThreads.shutdownNow();
            api.close();
            if (identity != null) identity.close();
            keys.clear();
            for (var loader : loaders) loader.close();
        }
    }

    @Test void successfulPaymentPostsOneBalancedJournalAndDeliversSignedWebhook() throws Exception {
        exercise("CAPTURE", PaymentStatus.SUCCESSFUL);
    }

    @Test void declinedPaymentCreatesNoJournalAndDeliversSignedFailureWebhook() throws Exception {
        exercise("DECLINE", PaymentStatus.FAILED);
    }

    private void exercise(String referencePrefix, PaymentStatus expected) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        String merchantId = "MER-E2E-" + suffix;
        var merchants = merchant.getBean(MerchantService.class);
        var registered = merchants.register(new RegisterMerchantRequest(merchantId, "Lifecycle Test",
                suffix.toLowerCase(Locale.ROOT) + "@example.test", null));
        assertEquals(MerchantStatus.ACTIVE, merchants.activate(registered.id(), registered.version()).status());
        var key = merchant.getBean(WebhookSecretService.class).provision(registered.id());
        keys.put(key.reference(), key);
        merchant.getBean(WebhookConfigurationService.class).update(registered.id(), new UpdateWebhookRequest(
                0L, "http://127.0.0.1:" + receiver.getAddress().getPort() + "/webhook", true,
                key.reference(), key.version()));
        await().atMost(DEADLINE).untilAsserted(() -> assertEquals(1, count(notification,
                "SELECT count(*) FROM merchant_webhook_projection WHERE merchant_id=? AND enabled AND merchant_status='ACTIVE'",
                merchantId)));

        String subject = UUID.randomUUID().toString();
        bind(registered.id(), subject, expected == PaymentStatus.SUCCESSFUL ? MerchantMembershipRole.MERCHANT_ADMIN : MerchantMembershipRole.MERCHANT_OPERATOR);
        String token = identity.user(subject, expected == PaymentStatus.SUCCESSFUL ? "merchant-admin" : "merchant-operator");
        var payments = payment.getBean(PaymentService.class);
        var request = new CreatePaymentRequest(merchantId, referencePrefix + "-" + suffix, AMOUNT, "NGN", "Lifecycle test");
        String idempotencyKey = UUID.randomUUID().toString();
        var created = paymentRequest("POST", "/api/v1/payments", token, request, idempotencyKey);
        assertEquals(202, created.statusCode());
        var initiated = json.readValue(created.body(), PaymentResponse.class);
        UUID paymentId = UUID.fromString(initiated.paymentId());
        var retry = paymentRequest("POST", "/api/v1/payments", token, request, idempotencyKey);
        assertEquals(202, retry.statusCode());
        assertEquals(initiated.paymentId(), json.readValue(retry.body(), PaymentResponse.class).paymentId());

        await().atMost(DEADLINE).untilAsserted(() -> {
            assertEquals(expected, payments.getPaymentById(paymentId).status());
            assertEquals(1, count(processor, "SELECT count(*) FROM processed_payments WHERE payment_id=? AND status=?",
                    paymentId, expected.name()));
            // Wait for Ledger acceptance, so the declined no-journal check cannot pass before consumption.
            assertEquals(1, count(ledger, "SELECT count(*) FROM processed_payment_events WHERE payment_id=? AND status=?",
                    paymentId, expected.name()));
            assertEquals(1, count(notification, "SELECT count(*) FROM notifications WHERE payment_id=? AND payment_status=? AND delivery_state='DELIVERED'",
                    paymentId, expected.name()));
            assertEquals(1, count(payment, "SELECT count(*) FROM outbox_events WHERE aggregate_id=? AND status='PUBLISHED'", paymentId));
            assertEquals(1, count(processor, "SELECT count(*) FROM outbox_events WHERE aggregate_id=? AND status='PUBLISHED'", paymentId));
        });

        var retrieved = paymentRequest("GET", "/api/v1/payments/" + paymentId, token, null, null);
        assertEquals(200, retrieved.statusCode());
        assertEquals(expected, json.readValue(retrieved.body(), PaymentResponse.class).status());

        if (expected == PaymentStatus.SUCCESSFUL) {
            assertEquals(1, count(ledger, "SELECT count(*) FROM journals WHERE payment_id=?", paymentId));
            var journal = jdbc(ledger).queryForMap("SELECT * FROM journals WHERE payment_id=?", paymentId);
            assertEquals("PAYMENT_CAPTURE", journal.get("journal_type"));
            assertEquals("POSTED", journal.get("status"));
            assertEquals(merchantId, journal.get("merchant_id"));
            assertEquals("NGN", journal.get("currency"));
            var lines = jdbc(ledger).queryForList("""
                    SELECT a.account_code, l.entry_type, l.amount FROM journal_lines l
                    JOIN ledger_accounts a ON a.id=l.ledger_account_id WHERE l.journal_id=? ORDER BY a.account_code
                    """, journal.get("id"));
            assertEquals(2, lines.size());
            assertEquals("1001", lines.get(0).get("account_code"));
            assertEquals("DEBIT", lines.get(0).get("entry_type"));
            assertEquals("2001", lines.get(1).get("account_code"));
            assertEquals("CREDIT", lines.get(1).get("entry_type"));
            for (var line : lines) assertEquals(0, AMOUNT.compareTo((BigDecimal) line.get("amount")));
        } else {
            assertEquals(0, count(ledger, "SELECT count(*) FROM journals WHERE payment_id=?", paymentId));
            assertEquals("SIMULATED_DECLINE", jdbc(processor).queryForObject(
                    "SELECT failure_reason FROM processed_payments WHERE payment_id=?", String.class, paymentId));
        }
        var deliveries = received.stream().filter(r -> r.envelope().paymentId().equals(paymentId)).toList();
        assertFalse(deliveries.isEmpty()); // Delivery is at-least-once; do not assert exactly one HTTP request.
        UUID eventId = jdbc(notification).queryForObject("SELECT event_id FROM notifications WHERE payment_id=?", UUID.class, paymentId);
        assertEquals(eventId, jdbc(ledger).queryForObject("SELECT event_id FROM processed_payment_events WHERE payment_id=?", UUID.class, paymentId));
        for (var delivery : deliveries) {
            assertTrue(delivery.signatureValid(), "Receiver independently verifies signature, timestamp and key headers");
            var body = delivery.envelope();
            assertEquals(eventId, body.eventId());
            assertEquals(1, body.version());
            assertEquals("PaymentProcessed", body.type());
            assertEquals(merchantId, body.merchantId());
            assertEquals(request.reference(), body.reference());
            assertEquals(expected.name(), body.status());
            assertEquals(0, AMOUNT.compareTo(body.amount()));
            assertEquals("NGN", body.currency());
        }
        assertEquals(1, count(notification, "SELECT count(*) FROM merchant_webhook_projection WHERE merchant_id=? AND secret_ready", merchantId));
        assertTrue(count(notification, """
                SELECT count(*) FROM notification_attempts a JOIN notifications n ON n.event_id=a.event_id
                WHERE n.payment_id=? AND a.outcome='DELIVERED' AND a.response_status=204
                """, paymentId) >= 1);
    }

    @BeforeEach void resetIdentityFailures() { identity.tokenStatus = 200; identity.malformedToken = false; }

    @Test void tenantsCannotCreateOrReadEachOthersPaymentsOrIdempotencyResults() throws Exception {
        var first = tenant(MerchantMembershipRole.MERCHANT_ADMIN);
        var second = tenant(MerchantMembershipRole.MERCHANT_OPERATOR);
        String a = identity.user(first.subject(), "merchant-admin"), b = identity.user(second.subject(), "merchant-operator");
        String key = UUID.randomUUID().toString(), reference = "ISOLATION-" + key;
        var request = new CreatePaymentRequest(first.merchantId(), reference, AMOUNT, "NGN", null);
        var created = paymentRequest("POST", "/api/v1/payments", a, request, key);
        assertEquals(202, created.statusCode());
        String id = json.readValue(created.body(), PaymentResponse.class).paymentId();
        assertEquals(403, paymentRequest("POST", "/api/v1/payments", b, request, key).statusCode());
        assertEquals(403, paymentRequest("POST", "/api/v1/payments", b,
                new CreatePaymentRequest("MER-UNKNOWN", reference, AMOUNT, "NGN", null), key).statusCode());
        var foreign = paymentRequest("GET", "/api/v1/payments/" + id, b, null, null);
        var missing = paymentRequest("GET", "/api/v1/payments/" + UUID.randomUUID(), b, null, null);
        assertEquals(404, foreign.statusCode()); assertEquals(404, missing.statusCode());
        assertEquals(json.readTree(foreign.body()).get("message"), json.readTree(missing.body()).get("message"));
        var own = paymentRequest("POST", "/api/v1/payments", b,
                new CreatePaymentRequest(second.merchantId(), reference, AMOUNT, "NGN", null), key);
        assertEquals(202, own.statusCode());
        assertNotEquals(id, json.readValue(own.body(), PaymentResponse.class).paymentId());
    }

    @Test void revocationBlocksReadsAndIdempotentCreatesWithTheSameUnexpiredToken() throws Exception {
        var tenant = tenant(MerchantMembershipRole.MERCHANT_OPERATOR);
        String user = identity.user(tenant.subject(), "merchant-operator");
        var request = new CreatePaymentRequest(tenant.merchantId(), "REVOKE-" + UUID.randomUUID(), AMOUNT, "NGN", null);
        String key = UUID.randomUUID().toString();
        var created = paymentRequest("POST", "/api/v1/payments", user, request, key);
        assertEquals(202, created.statusCode());
        String id = json.readValue(created.body(), PaymentResponse.class).paymentId();
        String admin = identity.token(identity.claims("platform-admin", "payflow-merchant-api", "payflow-platform-console",
                "merchant.bindings.manage", "platform-admin"));
        assertEquals(200, request(merchant, "POST", "/api/v1/merchants/" + tenant.merchantUuid()
                + "/memberships/" + tenant.membershipId() + "/revoke", admin, Map.of("version", 0), null).statusCode());
        assertEquals(403, paymentRequest("GET", "/api/v1/payments/" + id, user, null, null).statusCode());
        assertEquals(403, paymentRequest("POST", "/api/v1/payments", user, request, key).statusCode());
        assertEquals(1, count(payment, "SELECT count(*) FROM payments WHERE merchant_id=?", tenant.merchantId()));
    }

    @Test void suspendedMerchantsCanReadButNotCreateAndClosedMerchantsCannotDoEither() throws Exception {
        var tenant = tenant(MerchantMembershipRole.MERCHANT_ADMIN);
        String user = identity.user(tenant.subject(), "merchant-admin");
        var body = new CreatePaymentRequest(tenant.merchantId(), "LIFECYCLE-" + UUID.randomUUID(), AMOUNT, "NGN", null);
        String key = UUID.randomUUID().toString();
        var created = paymentRequest("POST", "/api/v1/payments", user, body, key);
        assertEquals(202, created.statusCode());
        String id = json.readValue(created.body(), PaymentResponse.class).paymentId();
        var merchants = merchant.getBean(MerchantService.class);
        var suspended = merchants.suspend(tenant.merchantUuid(), merchants.getById(tenant.merchantUuid()).version());
        assertEquals(200, paymentRequest("GET", "/api/v1/payments/" + id, user, null, null).statusCode());
        assertEquals(403, paymentRequest("POST", "/api/v1/payments", user, body, key).statusCode());
        merchants.close(tenant.merchantUuid(), suspended.version());
        assertEquals(403, paymentRequest("GET", "/api/v1/payments/" + id, user, null, null).statusCode());
        assertEquals(403, paymentRequest("POST", "/api/v1/payments", user, body, key).statusCode());
    }

    @Test void unboundAndPendingMerchantUsersAreDenied() throws Exception {
        assertEquals(403, paymentRequest("GET", "/api/v1/payments/" + UUID.randomUUID(),
                identity.user(UUID.randomUUID().toString(), "merchant-operator"), null, null).statusCode());
        String suffix = UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        var pending = merchant.getBean(MerchantService.class).register(new RegisterMerchantRequest(
                "MER-PENDING-" + suffix, "Pending", suffix.toLowerCase(Locale.ROOT) + "@example.test", null));
        String subject = UUID.randomUUID().toString();
        bind(pending.id(), subject, MerchantMembershipRole.MERCHANT_OPERATOR);
        assertEquals(403, paymentRequest("GET", "/api/v1/payments/" + UUID.randomUUID(),
                identity.user(subject, "merchant-operator"), null, null).statusCode());
    }

    @Test void invalidUserTokensAndUnapprovedClientsRolesScopesRemainDenied() throws Exception {
        String path = "/api/v1/payments/" + UUID.randomUUID();
        assertEquals(401, paymentRequest("GET", path, "invalid", null, null).statusCode());
        var base = identity.claims("human", "payflow-payment-api", "payflow-portal", "payment.read", "merchant-operator");
        for (var change : List.<Map<String, Object>>of(Map.of("aud", List.of("payflow-merchant-api")),
                Map.of("iss", "https://untrusted.invalid"), Map.of("exp", Date.from(Instant.now().minusSeconds(120))))) {
            var claims = new HashMap<>(base); claims.putAll(change);
            assertEquals(401, paymentRequest("GET", path, identity.token(claims), null, null).statusCode());
        }
        for (var change : List.<Map<String, Object>>of(Map.of("azp", "payflow-platform-console"),
                Map.of("azp", "payflow-payment-service"), Map.of("scope", "payment.create"),
                Map.of("scope", "payment.read.extra"), Map.of("resource_access", Map.of()),
                Map.of("resource_access", Map.of("payflow-payment-api", Map.of("roles", List.of("platform-admin")))))) {
            var claims = new HashMap<>(base); claims.putAll(change);
            assertEquals(403, paymentRequest("GET", path, identity.token(claims), null, null).statusCode());
        }
        assertEquals(403, paymentRequest("POST", "/api/v1/payments", identity.token(base), Map.of(), "denied").statusCode());
        var roleOnly = new HashMap<>(base); roleOnly.put("scope", "");
        assertEquals(403, paymentRequest("GET", path, identity.token(roleOnly), null, null).statusCode());
    }

    @Test void resolverRequiresExactMachineClientRoleAndScopeAndDoesNotGrantMembershipAdministration() throws Exception {
        var tenant = tenant(MerchantMembershipRole.MERCHANT_OPERATOR);
        var body = Map.of("issuer", identity.issuer(), "subject", tenant.subject());
        String path = "/internal/v1/merchant-access/resolve";
        String machine = identity.token(identity.machineClaims());
        var resolved = request(merchant, "POST", path, machine, body, null);
        assertEquals(200, resolved.statusCode());
        assertEquals(tenant.merchantId(), json.readTree(resolved.body()).get("merchantId").asString());
        assertEquals(401, request(merchant, "POST", path, null, body, null).statusCode());
        for (var change : List.<Map<String, Object>>of(Map.of("azp", "payflow-portal"),
                Map.of("azp", "payflow-platform-console"), Map.of("scope", "merchant.access.resolve.extra"),
                Map.of("resource_access", Map.of("payflow-merchant-api", Map.of("roles", List.of("platform-admin")))))) {
            var claims = identity.machineClaims(); claims.putAll(change);
            assertEquals(403, request(merchant, "POST", path, identity.token(claims), body, null).statusCode());
        }
        assertEquals(403, request(merchant, "POST", path, machine,
                Map.of("issuer", "https://foreign.invalid", "subject", tenant.subject()), null).statusCode());
        assertEquals(400, request(merchant, "POST", path, machine,
                Map.of("issuer", identity.issuer(), "subject", tenant.subject(), "merchantId", tenant.merchantId()), null).statusCode());
        assertEquals(403, request(merchant, "POST", "/api/v1/merchants/" + tenant.merchantUuid()
                + "/memberships", machine, Map.of(), null).statusCode());
    }

    @Test void unavailableTokenServiceAndRejectedMachineTokensNeverCreatePaymentState() throws Exception {
        var tenant = tenant(MerchantMembershipRole.MERCHANT_OPERATOR);
        String user = identity.user(tenant.subject(), "merchant-operator");
        var body = new CreatePaymentRequest(tenant.merchantId(), "UNAVAILABLE-" + UUID.randomUUID(), AMOUNT, "NGN", null);
        try {
            identity.tokenStatus = 503;
            var failure = paymentRequest("POST", "/api/v1/payments", user, body, "unavailable");
            assertEquals(503, failure.statusCode());
            assertEquals("Authorization service unavailable", json.readTree(failure.body()).get("message").asString());
            identity.tokenStatus = 200; identity.malformedToken = true;
            assertEquals(503, paymentRequest("POST", "/api/v1/payments", user, body, "invalid-machine").statusCode());
            assertEquals(0, count(payment, "SELECT count(*) FROM payments WHERE merchant_id=?", tenant.merchantId()));
            assertEquals(0, count(payment, "SELECT count(*) FROM idempotency_records WHERE merchant_id=?", tenant.merchantId()));
        } finally { resetIdentityFailures(); }
    }

    private static MerchantMembership bind(UUID merchantId, String subject, MerchantMembershipRole role) {
        return merchant.getBean(MerchantMembershipRepository.class).saveAndFlush(new MerchantMembership(UUID.randomUUID(),
                merchantId, identity.issuer(), subject, role, identity.issuer(), "fixture-admin", Instant.now()));
    }

    private static Tenant tenant(MerchantMembershipRole role) {
        String suffix = UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        var merchants = merchant.getBean(MerchantService.class);
        var registered = merchants.register(new RegisterMerchantRequest("MER-AUTH-" + suffix, "Tenant Test",
                suffix.toLowerCase(Locale.ROOT) + "@example.test", null));
        merchants.activate(registered.id(), registered.version());
        String subject = UUID.randomUUID().toString();
        var membership = bind(registered.id(), subject, role);
        return new Tenant(registered.id(), registered.merchantId(), subject, membership.getId());
    }
    private record Tenant(UUID merchantUuid, String merchantId, String subject, UUID membershipId) {}
    private static String baseUrl(ConfigurableApplicationContext context) {
        return "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
    }
    private static HttpResponse<String> paymentRequest(String method, String path, String token, Object body, String key) throws Exception {
        return request(payment, method, path, token, body, key);
    }
    private static HttpResponse<String> request(ConfigurableApplicationContext context, String method, String path,
            String token, Object body, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl(context) + path)).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return api.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JdbcTemplate jdbc(ConfigurableApplicationContext context) { return context.getBean(JdbcTemplate.class); }
    private static int count(ConfigurableApplicationContext context, String sql, Object... arguments) {
        return jdbc(context).queryForObject(sql, Integer.class, arguments);
    }

    private static void startReceiver() throws Exception {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiverThreads = Executors.newVirtualThreadPerTaskExecutor();
        receiver.setExecutor(receiverThreads);
        receiver.createContext("/webhook", exchange -> {
            int status = 400;
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                var headers = exchange.getRequestHeaders();
                var envelope = json.readValue(body, WebhookEnvelope.class);
                String timestamp = headers.getFirst("PayFlow-Webhook-Timestamp");
                var key = keys.get(UUID.fromString(headers.getFirst("PayFlow-Webhook-Key-Id")));
                var mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(key.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
                String expected = "v1=" + HexFormat.of().formatHex(mac.doFinal(body));
                String actual = headers.getFirst("PayFlow-Webhook-Signature");
                boolean valid = "POST".equals(exchange.getRequestMethod())
                        && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))
                        && Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp)) <= 300
                        && Long.toString(key.version()).equals(headers.getFirst("PayFlow-Webhook-Key-Version"))
                        && envelope.eventId().toString().equals(headers.getFirst("PayFlow-Webhook-Id"));
                received.add(new Received(envelope, valid));
                status = valid ? 204 : 400;
            } catch (Exception invalid) {
                // Invalid delivery stays rejected; never log key material or raw request headers.
            } finally {
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
            }
        });
        receiver.start();
    }

    private record Received(WebhookEnvelope envelope, boolean signatureValid) {}

    private static final class ServiceResources extends URLClassLoader {
        ServiceResources(Class<?> application) {
            super(new URL[]{application.getProtectionDomain().getCodeSource().getLocation()}, application.getClassLoader());
        }
        private boolean owned(String name) { return name.startsWith("db/") || name.equals("application.yml"); }
        @Override public URL getResource(String name) { return owned(name) ? findResource(name) : super.getResource(name); }
        @Override public Enumeration<URL> getResources(String name) throws java.io.IOException {
            return owned(name) ? findResources(name) : super.getResources(name);
        }
    }
}
