package com.innocodes.payflow.ledger.consumer;

import com.innocodes.payflow.contracts.payment.PaymentProcessedEvent;
import com.innocodes.payflow.ledger.config.KafkaTopicConfig;
import com.innocodes.payflow.ledger.enums.EntryType;
import com.innocodes.payflow.ledger.enums.JournalType;
import com.innocodes.payflow.ledger.repository.JournalLineRepository;
import com.innocodes.payflow.ledger.repository.JournalRepository;
import com.innocodes.payflow.ledger.repository.ProcessedPaymentEventRepository;
import com.innocodes.payflow.ledger.service.JournalPostingService;
import com.innocodes.payflow.ledger.service.PaymentProcessedService;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"payflow.kafka.retry.interval-ms=100", "spring.jpa.show-sql=false"})
@Testcontainers
@Import(PaymentProcessedKafkaIntegrationTest.Topics.class)
class PaymentProcessedKafkaIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");
    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.2");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @TestConfiguration
    static class Topics {
        @Bean
        NewTopic sourceTopic() {
            return TopicBuilder.name(KafkaTopicConfig.PAYMENT_PROCESSED_TOPIC).partitions(3).replicas(1).build();
        }
    }

    @Autowired
    private JsonMapper mapper;
    @MockitoSpyBean
    private KafkaTemplate<?, ?> kafkaTemplate;
    @Autowired
    private KafkaTemplate<Object, Object> template;
    @MockitoSpyBean
    private ProcessedPaymentEventRepository events;
    @MockitoSpyBean
    private JournalPostingService posting;
    @MockitoSpyBean
    private PaymentProcessedService service;
    @Autowired
    private JournalRepository journals;
    @Autowired
    private JournalLineRepository lines;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactions;

    private KafkaConsumer<String, String> dlt;
    private AdminClient admin;

    @BeforeEach
    void openClients() {
        dlt = new KafkaConsumer<>(Map.of(
                "bootstrap.servers", kafka.getBootstrapServers(),
                "group.id", "ledger-test-dlt-" + UUID.randomUUID(),
                "enable.auto.commit", false
        ), new StringDeserializer(), new StringDeserializer());
        var partitions = List.of(new TopicPartition(KafkaTopicConfig.PAYMENT_PROCESSED_DLT, 0),
                new TopicPartition(KafkaTopicConfig.PAYMENT_PROCESSED_DLT, 1),
                new TopicPartition(KafkaTopicConfig.PAYMENT_PROCESSED_DLT, 2));
        dlt.assign(partitions);
        dlt.seekToBeginning(partitions);
        admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()));
    }

    @AfterEach
    void closeClients() {
        dlt.close();
        admin.close();
    }

    @Test
    void shouldPostSuccessAndCommitOffsetUsingPayloadIdentity() throws Exception {
        var event = event("SUCCESSFUL");
        var record = new ProducerRecord<Object, Object>(KafkaTopicConfig.PAYMENT_PROCESSED_TOPIC,
                event.paymentId().toString(), mapper.writeValueAsString(event));
        record.headers().add("event-id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        var source = template.send(record).get(10, TimeUnit.SECONDS).getRecordMetadata();
        awaitCommitted(source);
        assertCapture(event);
        assertTrue(events.existsById(event.eventId()));
    }

    @Test
    void shouldRecordFailedPaymentWithoutJournal() throws Exception {
        var event = event("FAILED");
        awaitCommitted(send(event));
        assertTrue(events.existsById(event.eventId()));
        assertTrue(journals.findByPaymentIdAndJournalType(event.paymentId(), JournalType.PAYMENT_CAPTURE).isEmpty());
    }

    @Test
    void shouldNotDuplicateAccountingOnRedelivery() throws Exception {
        var event = event("SUCCESSFUL");
        awaitCommitted(send(event));
        var original = journals.findByPaymentIdAndJournalType(event.paymentId(), JournalType.PAYMENT_CAPTURE)
                .orElseThrow().getId();
        awaitCommitted(send(event));
        assertCapture(event);
        assertEquals(original, journals.findByPaymentIdAndJournalType(event.paymentId(), JournalType.PAYMENT_CAPTURE)
                .orElseThrow().getId());
    }

    @Test
    void shouldDeadLetterConflictWithoutRetryAndPreserveMetadata() throws Exception {
        var event = event("SUCCESSFUL");
        awaitCommitted(send(event));
        var conflict = new PaymentProcessedEvent(event.eventId(), event.paymentId(), event.merchantId(),
                event.reference(), new BigDecimal("200.00"), event.currency(), event.status(), null, event.processedAt());
        var source = send(conflict);
        var recovered = awaitDlt(event.paymentId().toString());
        awaitCommitted(source);
        assertEquals(mapper.writeValueAsString(conflict), recovered.value());
        assertEquals(KafkaTopicConfig.PAYMENT_PROCESSED_TOPIC, headerText(recovered, KafkaHeaders.DLT_ORIGINAL_TOPIC));
        assertEquals(source.partition(), ByteBuffer.wrap(recovered.headers()
                .lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION).value()).getInt());
        assertEquals(source.offset(), ByteBuffer.wrap(recovered.headers()
                .lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET).value()).getLong());
        assertNotNull(recovered.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_FQCN));
        verify(service, times(1)).process(conflict);
        assertCapture(event);
    }

    @Test
    void shouldDeadLetterMalformedJsonAndContinuePartition() throws Exception {
        String key = UUID.randomUUID().toString();
        var source = template.send(KafkaTopicConfig.PAYMENT_PROCESSED_TOPIC, key, "{broken")
                .get(10, TimeUnit.SECONDS).getRecordMetadata();
        assertEquals("{broken", awaitDlt(key).value());
        awaitCommitted(source);
        var valid = event("FAILED");
        awaitCommitted(send(valid));
        assertTrue(events.existsById(valid.eventId()));
    }

    @Test
    void shouldRollBackThenRetryInFreshTransaction() throws Exception {
        var event = event("SUCCESSFUL");
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<UUID> rolledBackJournal = new AtomicReference<>();
        doAnswer(invocation -> {
            var journal = (com.innocodes.payflow.ledger.entity.Journal) invocation.callRealMethod();
            entityManager.flush();
            if (attempts.incrementAndGet() == 1) {
                rolledBackJournal.set(journal.getId());
                throw new TransientDataAccessResourceException("Injected transient failure after writes");
            }
            return journal;
        }).when(posting).postJournal(eq(event.paymentId()), any(), anyString(), anyString(), any(),
                anyString(), anyString(), anyList());
        awaitCommitted(send(event));
        assertEquals(2, attempts.get());
        assertFalse(journals.existsById(rolledBackJournal.get()));
        assertTrue(lines.findByJournal_Id(rolledBackJournal.get()).isEmpty());
        assertCapture(event);
    }

    @Test
    void shouldDeadLetterAfterThreeRetriesWithoutPartialDatabaseWrites() throws Exception {
        var event = event("SUCCESSFUL");
        doThrow(new TransientDataAccessResourceException("Injected persistent outage"))
                .when(posting).postJournal(eq(event.paymentId()), any(), anyString(), anyString(), any(),
                        anyString(), anyString(), anyList());
        var source = send(event);
        awaitDlt(event.paymentId().toString());
        awaitCommitted(source);
        verify(posting, times(4)).postJournal(eq(event.paymentId()), any(), anyString(), anyString(), any(),
                anyString(), anyString(), anyList());
        assertFalse(events.existsById(event.eventId()));
        assertTrue(journals.findByPaymentIdAndJournalType(event.paymentId(), JournalType.PAYMENT_CAPTURE).isEmpty());
    }

    @Test
    void shouldLeaveSourceUncommittedUntilDltPublicationSucceeds() throws Exception {
        String key = UUID.randomUUID().toString();
        AtomicBoolean unavailable = new AtomicBoolean(true);
        AtomicInteger failures = new AtomicInteger();
        doAnswer(invocation -> {
            ProducerRecord<Object, Object> record = invocation.getArgument(0);
            if (record.topic().equals(KafkaTopicConfig.PAYMENT_PROCESSED_DLT)
                    && key.equals(record.key()) && unavailable.get()) {
                failures.incrementAndGet();
                return CompletableFuture.failedFuture(new IllegalStateException("Injected DLT outage"));
            }
            return invocation.callRealMethod();
        }).when(template).send(any(ProducerRecord.class));
        var source = template.send(KafkaTopicConfig.PAYMENT_PROCESSED_TOPIC, key, "bad-json")
                .get(10, TimeUnit.SECONDS).getRecordMetadata();
        try {
            await().atMost(Duration.ofSeconds(20)).until(() -> failures.get() > 0);
            assertFalse(isCommitted(source));
        } finally {
            unavailable.set(false);
        }
        assertEquals("bad-json", awaitDlt(key).value());
        awaitCommitted(source);
    }

    @Test
    void shouldResolveConcurrentDuplicateInsertByRetryingAfterRollback() throws Exception {
        var event = event("SUCCESSFUL");
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger lookups = new AtomicInteger();
        doAnswer(invocation -> {
            // Synchronize real database reads before either transaction inserts.
            // Spring Data repository methods are abstract, so query through JPA here.
            var result = entityManager.createQuery(
                    "select e from ProcessedPaymentEvent e where e.paymentId = :paymentId",
                    com.innocodes.payflow.ledger.entity.ProcessedPaymentEvent.class
            ).setParameter("paymentId", event.paymentId()).getResultStream().findFirst();
            if (lookups.incrementAndGet() <= 2) {
                barrier.await(10, TimeUnit.SECONDS);
            }
            return result;
        }).when(events).findByPaymentId(event.paymentId());
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> processOutcome(event));
            var second = workers.submit(() -> processOutcome(event));
            Throwable a = first.get(20, TimeUnit.SECONDS);
            Throwable b = second.get(20, TimeUnit.SECONDS);
            assertTrue((a == null) != (b == null), "One insert must commit and the other must fail: " + a + " / " + b);
            Throwable failed = a == null ? b : a;
            while (failed.getCause() != null) {
                failed = failed.getCause();
            }
            assertInstanceOf(java.sql.SQLException.class, failed);
            assertEquals("23505", ((java.sql.SQLException) failed).getSQLState());
        }
        // A new transaction sees the committed receipt and returns idempotently.
        service.process(event);
        assertCapture(event);
    }

    private Throwable processOutcome(PaymentProcessedEvent event) {
        try {
            service.process(event);
            return null;
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    private PaymentProcessedEvent event(String status) {
        return new PaymentProcessedEvent(UUID.randomUUID(), UUID.randomUUID(), "MER-KAFKA", "KAFKA-" + UUID.randomUUID(),
                new BigDecimal("100.00"), "NGN", status, "FAILED".equals(status) ? "Declined" : null, Instant.now());
    }

    private RecordMetadata send(PaymentProcessedEvent event) throws Exception {
        return template.send(KafkaTopicConfig.PAYMENT_PROCESSED_TOPIC, event.paymentId().toString(),
                mapper.writeValueAsString(event)).get(10, TimeUnit.SECONDS).getRecordMetadata();
    }

    private void awaitCommitted(RecordMetadata record) {
        await().atMost(Duration.ofSeconds(30)).until(() -> isCommitted(record));
    }

    private boolean isCommitted(RecordMetadata record) throws Exception {
        var offsets = admin.listConsumerGroupOffsets("ledger-service-group").partitionsToOffsetAndMetadata()
                .get(5, TimeUnit.SECONDS);
        var offset = offsets.get(new TopicPartition(record.topic(), record.partition()));
        return offset != null && offset.offset() > record.offset();
    }

    private ConsumerRecord<String, String> awaitDlt(String key) {
        AtomicReference<ConsumerRecord<String, String>> result = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            for (var record : dlt.poll(Duration.ofMillis(200))) {
                if (key.equals(record.key())) {
                    result.set(record);
                }
            }
            return result.get() != null;
        });
        return result.get();
    }

    private String headerText(ConsumerRecord<String, String> record, String header) {
        return new String(record.headers().lastHeader(header).value(), StandardCharsets.UTF_8);
    }

    private void assertCapture(PaymentProcessedEvent event) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var journal = journals.findByPaymentIdAndJournalType(event.paymentId(), JournalType.PAYMENT_CAPTURE)
                    .orElseThrow();
            assertEquals(event.eventId(), journal.getSourceEventId());
            var entries = lines.findByJournal_Id(journal.getId());
            assertEquals(2, entries.size());
            assertTrue(entries.stream().anyMatch(line -> line.getEntryType() == EntryType.DEBIT
                    && line.getLedgerAccount().getAccountCode().equals("1001")));
            assertTrue(entries.stream().anyMatch(line -> line.getEntryType() == EntryType.CREDIT
                    && line.getLedgerAccount().getAccountCode().equals("2001")));
            entries.forEach(line -> assertEquals(0, line.getAmount().compareTo(event.amount())));
        });
    }
}
