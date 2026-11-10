package com.innocodes.payflow.merchant.webhook;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(
        name = "payflow.merchant.outbox.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class MerchantOutboxPublisher {
    public static final String TOPIC = "merchant-webhook-config";
    private final String topic;
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<Object, Object> kafka;
    private final TransactionTemplate transactions;
    private final WebhookConfigurationService webhooks;

    public MerchantOutboxPublisher(
            JdbcTemplate jdbc,
            KafkaTemplate<Object, Object> kafka,
            PlatformTransactionManager tx,
            WebhookConfigurationService webhooks) {
        this(jdbc, kafka, tx, webhooks, TOPIC);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MerchantOutboxPublisher(
            JdbcTemplate jdbc,
            KafkaTemplate<Object, Object> kafka,
            PlatformTransactionManager tx,
            WebhookConfigurationService webhooks,
            @org.springframework.beans.factory.annotation.Value(
                            "${payflow.kafka.merchant-config-topic:merchant-webhook-config}")
                    String topic) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.transactions = new TransactionTemplate(tx);
        this.webhooks = webhooks;
        this.topic = topic;
    }

    @Scheduled(fixedDelayString = "${payflow.merchant.outbox.poll-ms:1000}")
    public void publishOne() {
        var row =
                transactions.execute(
                        status -> {
                            var rows =
                                    jdbc.queryForList(
                                            """
                                            SELECT * FROM merchant_config_outbox WHERE (state='PENDING' AND next_attempt_at<=clock_timestamp())
                                             OR(state='IN_FLIGHT' AND lease_until<clock_timestamp())
                                            ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED
                                            """);
                            if (rows.isEmpty()) return null;
                            var entry = rows.getFirst();
                            if (((Number) entry.get("attempts")).intValue() >= 8) {
                                jdbc.update(
                                        "UPDATE merchant_config_outbox SET"
                                            + " state='DEAD',claim_token=NULL,lease_until=NULL,last_failure='PUBLISH_EXHAUSTED'"
                                            + " WHERE event_id=?",
                                        entry.get("event_id"));
                                return null;
                            }
                            UUID token = UUID.randomUUID();
                            entry.put("token", token);
                            jdbc.update(
                                    "UPDATE merchant_config_outbox SET"
                                        + " state='IN_FLIGHT',attempts=attempts+1,claim_token=?,lease_until=clock_timestamp()+interval"
                                        + " '20 seconds' WHERE event_id=?",
                                    token,
                                    entry.get("event_id"));
                            return entry;
                        });
        if (row == null) return;
        boolean sent = false;
        try {
            kafka.send(topic, row.get("merchant_id"), row.get("payload")).get(6, TimeUnit.SECONDS);
            sent = true;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn("Merchant configuration publish failed event={}", row.get("event_id"));
        }
        boolean success = sent;
        transactions.executeWithoutResult(
                status -> {
                    int attempts = ((Number) row.get("attempts")).intValue() + 1;
                    jdbc.update(
                            """
                            UPDATE merchant_config_outbox SET state=?,claim_token=NULL,lease_until=NULL,
                            last_failure=?,published_at=CASE WHEN ? THEN clock_timestamp() ELSE NULL END,
                            next_attempt_at=clock_timestamp()+(? * interval '1 second')
                            WHERE event_id=? AND claim_token=? AND lease_until>clock_timestamp()
                            """,
                            success ? "PUBLISHED" : attempts >= 8 ? "DEAD" : "PENDING",
                            success ? null : "KAFKA_UNAVAILABLE",
                            success,
                            Math.min(300, 1L << attempts),
                            row.get("event_id"),
                            row.get("token"));
                });
    }

    @Scheduled(fixedDelayString = "${payflow.merchant.snapshot.poll-ms:10000}")
    public void snapshots() {
        webhooks.snapshotDue();
    }
}
