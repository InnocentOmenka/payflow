package com.innocodes.payflow.merchant.webhook;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import com.innocodes.payflow.merchant.dto.request.RegisterMerchantRequest;
import com.innocodes.payflow.merchant.service.MerchantService;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.*;

@SpringBootTest(
        properties = {"payflow.webhook.allow-local=true", "payflow.merchant.outbox.enabled=false"})
@ActiveProfiles("dev")
@Testcontainers
class MerchantOutboxKafkaIntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:17");

    @Container static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.2");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired MerchantService merchants;
    @Autowired WebhookConfigurationService webhooks;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<Object, Object> template;
    @Autowired PlatformTransactionManager tx;

    @Test
    void shouldPublishCommittedOutboxToKafkaWithMerchantKeyAndStablePayload() {
        var merchant =
                merchants.register(
                        new RegisterMerchantRequest(
                                "MER-KAFKA-OUTBOX", "Test", "outbox@example.com", null));
        webhooks.update(
                merchant.id(),
                new UpdateWebhookRequest(0L, "http://localhost:8090/hook", false, null, null));
        String payload =
                jdbc.queryForObject("SELECT payload FROM merchant_config_outbox", String.class);
        var publisher = new MerchantOutboxPublisher(jdbc, template, tx, webhooks);
        publisher.publishOne();
        assertEquals(
                "PUBLISHED",
                jdbc.queryForObject("SELECT state FROM merchant_config_outbox", String.class));
        try (var consumer =
                new KafkaConsumer<String, String>(
                        Map.of(
                                "bootstrap.servers",
                                kafka.getBootstrapServers(),
                                "group.id",
                                "merchant-outbox-test",
                                "enable.auto.commit",
                                false),
                        new StringDeserializer(),
                        new StringDeserializer())) {
            var partitions =
                    List.of(
                            new TopicPartition(MerchantOutboxPublisher.TOPIC, 0),
                            new TopicPartition(MerchantOutboxPublisher.TOPIC, 1),
                            new TopicPartition(MerchantOutboxPublisher.TOPIC, 2));
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            await().atMost(Duration.ofSeconds(15))
                    .until(
                            () -> {
                                for (var record : consumer.poll(Duration.ofMillis(100)))
                                    if (record.value().equals(payload)) {
                                        assertEquals(merchant.merchantId(), record.key());
                                        return true;
                                    }
                                return false;
                            });
        }
    }
}
