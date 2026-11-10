package com.innocodes.payflow.notification.consumer;

import com.innocodes.payflow.contracts.payment.PaymentProcessedEvent;
import com.innocodes.payflow.notification.config.KafkaTopicConfig;
import com.innocodes.payflow.notification.delivery.WebhookSigner;
import com.innocodes.payflow.notification.repository.NotificationRepository;
import com.innocodes.payflow.notification.service.NotificationIngestionService;
import com.innocodes.payflow.notification.support.MockMerchant;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"payflow.kafka.retry.interval-ms=100", "payflow.webhook.poll-interval-ms=50",
        "payflow.webhook.allow-local=true"})
@ActiveProfiles({"dev","test-static-webhooks"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@Import({NotificationKafkaIntegrationTest.Topics.class,com.innocodes.payflow.notification.support.StaticTestDestinations.class})
class NotificationKafkaIntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");
    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.2");
    static final MockMerchant merchant;
    static {
        try { merchant = new MockMerchant(); } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        r.add("payflow.webhook.merchants[MER-KAFKA].url", () -> merchant.url().toString());
        r.add("payflow.webhook.merchants[MER-KAFKA].secret", () -> MockMerchant.SECRET);
    }

    @TestConfiguration
    static class Topics {
        @Bean NewTopic merchantConfiguration() {
            return TopicBuilder.name("merchant-webhook-config").partitions(3).replicas(1).build();
        }
        @Bean NewTopic paymentProcessed() {
            return TopicBuilder.name(KafkaTopicConfig.PAYMENT_PROCESSED_TOPIC).partitions(3).replicas(1).build();
        }
    }

    @Autowired JsonMapper mapper;
    @Autowired KafkaTemplate<Object, Object> template;
    @MockitoSpyBean NotificationRepository notifications;
    @MockitoSpyBean NotificationIngestionService ingestion;
    @Autowired JdbcTemplate jdbc;
    private KafkaConsumer<String, String> dlt;
    private AdminClient admin;

    @BeforeEach
    void openClients() {
        dlt = new KafkaConsumer<>(Map.of("bootstrap.servers", kafka.getBootstrapServers(),
                "group.id", "notification-test-" + UUID.randomUUID(), "enable.auto.commit", false),
                new StringDeserializer(), new StringDeserializer());
        var partitions = List.of(new TopicPartition(KafkaTopicConfig.PAYMENT_PROCESSED_DLT, 0),
                new TopicPartition(KafkaTopicConfig.PAYMENT_PROCESSED_DLT, 1),
                new TopicPartition(KafkaTopicConfig.PAYMENT_PROCESSED_DLT, 2));
        dlt.assign(partitions);
        dlt.seekToBeginning(partitions);
        admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()));
    }

    @AfterEach void closeClients() { dlt.close(); admin.close(); }
    @AfterAll static void closeReceiver() { merchant.close(); }

    @Test
    void shouldPersistKafkaResultAndDeliverSignedHttp() throws Exception {
        var e = event("SUCCESSFUL");
        var producerRecord = new org.apache.kafka.clients.producer.ProducerRecord<Object, Object>(
                KafkaTopicConfig.PAYMENT_PROCESSED_TOPIC, e.paymentId().toString(), mapper.writeValueAsString(e));
        producerRecord.headers().add("event-id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        committed(template.send(producerRecord).get(10, TimeUnit.SECONDS).getRecordMetadata());
        delivered(e);
        var received = merchant.received.stream().filter(r -> r.body().contains(e.eventId().toString())).findFirst().orElseThrow();
        assertEquals(WebhookSigner.sign(MockMerchant.SECRET, received.header("PayFlow-Webhook-Timestamp"), received.body()),
                received.header("PayFlow-Webhook-Signature"));
        assertEquals(e.eventId().toString(), received.header("PayFlow-Webhook-Id"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM notification_attempts WHERE event_id=? AND outcome='DELIVERED'",
                Integer.class, e.eventId()));
    }

    @Test
    void shouldDeliverFailedResultAndIgnoreReplay() throws Exception {
        var e = event("FAILED");
        committed(send(e));
        delivered(e);
        committed(send(e));
        assertEquals(1, jdbc.queryForObject("SELECT attempt_count FROM notifications WHERE event_id=?", Integer.class, e.eventId()));
        assertEquals("FAILED", notifications.findById(e.eventId()).orElseThrow().paymentStatus());
    }

    @Test
    void shouldDeadLetterConflictingPayloadWithoutRetryOrAnotherHttp() throws Exception {
        var e = event("SUCCESSFUL");
        committed(send(e));
        delivered(e);
        var conflict = new PaymentProcessedEvent(e.eventId(), e.paymentId(), e.merchantId(), e.reference(),
                new BigDecimal("200.00"), e.currency(), e.status(), e.failureReason(), e.processedAt());
        var source = send(conflict);
        var result = recovered(e.paymentId().toString());
        committed(source);
        assertEquals(mapper.writeValueAsString(conflict), result.value());
        assertNotNull(result.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_FQCN));
        verify(ingestion, times(1)).process(conflict);
        assertEquals(1, jdbc.queryForObject("SELECT attempt_count FROM notifications WHERE event_id=?", Integer.class, e.eventId()));
    }

    @Test
    void shouldDeadLetterMalformedJsonAndRetainSourceMetadata() throws Exception {
        String key = UUID.randomUUID().toString();
        var source = template.send(KafkaTopicConfig.PAYMENT_PROCESSED_TOPIC, key, "{broken")
                .get(10, TimeUnit.SECONDS).getRecordMetadata();
        var result = recovered(key);
        committed(source);
        assertEquals("{broken", result.value());
        assertEquals(KafkaTopicConfig.PAYMENT_PROCESSED_TOPIC, new String(result.headers()
                .lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value(), StandardCharsets.UTF_8));
        assertEquals(source.offset(), java.nio.ByteBuffer.wrap(result.headers()
                .lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET).value()).getLong());
    }

    @Test
    void shouldRollBackPersistenceFailureAndRetryBeforeAcknowledgment() throws Exception {
        var e = event("SUCCESSFUL");
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(call -> {
            call.callRealMethod();
            if (attempts.incrementAndGet() == 1) throw new DataAccessResourceFailureException("Injected transient DB failure");
            return null;
        }).when(notifications).insert(eq(e.eventId()), any(), anyString(), anyString(), anyString(), anyString());
        committed(send(e));
        delivered(e);
        assertEquals(2, attempts.get());
        assertEquals(1, jdbc.queryForObject("SELECT attempt_count FROM notifications WHERE event_id=?", Integer.class, e.eventId()));
    }

    @Test
    void shouldBoundPersistentDatabaseRetriesAndDeadLetterWithoutNotification() throws Exception {
        var e = event("SUCCESSFUL");
        doAnswer(call -> {
            call.callRealMethod();
            throw new DataAccessResourceFailureException("Injected persistent DB failure");
        }).when(notifications).insert(eq(e.eventId()), any(), anyString(), anyString(), anyString(), anyString());
        var source = send(e);
        recovered(e.paymentId().toString());
        committed(source);
        verify(notifications, times(4)).insert(eq(e.eventId()), any(), anyString(), anyString(), anyString(), anyString());
        assertTrue(notifications.findById(e.eventId()).isEmpty());
        assertTrue(merchant.received.stream().noneMatch(r -> r.body().contains(e.eventId().toString())));
    }

    @Autowired com.innocodes.payflow.notification.merchant.MerchantProjectionService projection;
    @Test void shouldConsumeDuplicateAndOutOfOrderMerchantConfigurationOverKafka() throws Exception {
        String merchantId=("MER-"+UUID.randomUUID()).toUpperCase(java.util.Locale.ROOT);
        var now=Instant.now();
        var newest=new com.innocodes.payflow.contracts.merchant.MerchantWebhookConfigurationEvent(UUID.randomUUID(),merchantId,2,
                merchant.url().toString(),false,"SUSPENDED",null,null,now);
        template.send("merchant-webhook-config",merchantId,mapper.writeValueAsString(newest)).get(10,TimeUnit.SECONDS);
        template.send("merchant-webhook-config",merchantId,mapper.writeValueAsString(newest)).get(10,TimeUnit.SECONDS);
        var old=new com.innocodes.payflow.contracts.merchant.MerchantWebhookConfigurationEvent(UUID.randomUUID(),merchantId,1,
                merchant.url().toString(),false,"PENDING",null,null,now);
        template.send("merchant-webhook-config",merchantId,mapper.writeValueAsString(old)).get(10,TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(15)).until(()->jdbc.queryForObject("SELECT count(*) FROM merchant_configuration_receipts WHERE merchant_id=?",Integer.class,merchantId)==2);
        assertEquals(2,jdbc.queryForObject("SELECT config_version FROM merchant_webhook_projection WHERE merchant_id=?",Long.class,merchantId));
        assertEquals("SUSPENDED",jdbc.queryForObject("SELECT merchant_status FROM merchant_webhook_projection WHERE merchant_id=?",String.class,merchantId));
    }
    @Test void shouldDeadLetterMalformedMerchantConfiguration() throws Exception {
        try(var consumer=new KafkaConsumer<String,String>(Map.of("bootstrap.servers",kafka.getBootstrapServers(),"group.id","config-dlt-"+UUID.randomUUID(),"enable.auto.commit",false),new StringDeserializer(),new StringDeserializer())) {
            String topic="merchant-webhook-config-notification-dlt";
            var partitions=List.of(new TopicPartition(topic,0),new TopicPartition(topic,1),new TopicPartition(topic,2));
            consumer.assign(partitions);consumer.seekToBeginning(partitions);
            String id="MER-"+UUID.randomUUID();
            template.send("merchant-webhook-config",id,"{broken").get(10,TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(15)).until(()->{
                for(var record:consumer.poll(Duration.ofMillis(100)))if(id.equals(record.key()))return true;
                return false;
            });
        }
    }

    private void delivered(PaymentProcessedEvent e) {
        await().atMost(Duration.ofSeconds(20)).until(() -> notifications.findById(e.eventId())
                .map(n -> n.deliveryState().equals("DELIVERED")).orElse(false));
    }

    private RecordMetadata send(PaymentProcessedEvent event) throws Exception {
        return template.send(KafkaTopicConfig.PAYMENT_PROCESSED_TOPIC, event.paymentId().toString(),
                mapper.writeValueAsString(event)).get(10, TimeUnit.SECONDS).getRecordMetadata();
    }

    private void committed(RecordMetadata source) {
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            var offset = admin.listConsumerGroupOffsets("notification-service-group").partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS).get(new TopicPartition(source.topic(), source.partition()));
            return offset != null && offset.offset() > source.offset();
        });
    }

    private ConsumerRecord<String, String> recovered(String key) {
        AtomicReference<ConsumerRecord<String, String>> value = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            for (var record : dlt.poll(Duration.ofMillis(200))) if (key.equals(record.key())) value.set(record);
            return value.get() != null;
        });
        return value.get();
    }

    private PaymentProcessedEvent event(String status) {
        return new PaymentProcessedEvent(UUID.randomUUID(), UUID.randomUUID(), "MER-KAFKA", "KAFKA-" + UUID.randomUUID(),
                new BigDecimal("100.00"), "NGN", status, status.equals("FAILED") ? "DECLINED" : null, Instant.now());
    }
}
