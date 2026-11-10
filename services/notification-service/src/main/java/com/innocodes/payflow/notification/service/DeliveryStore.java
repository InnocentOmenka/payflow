package com.innocodes.payflow.notification.service;

import com.innocodes.payflow.notification.config.WebhookProperties;
import com.innocodes.payflow.notification.delivery.DeliveryResult;
import com.innocodes.payflow.notification.delivery.RetryPolicy;
import com.innocodes.payflow.notification.model.Notification;
import com.innocodes.payflow.notification.repository.NotificationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class DeliveryStore {
    private final JdbcTemplate jdbc;
    private final NotificationRepository notifications;
    private final WebhookProperties properties;
    private final RetryPolicy retries;

    public DeliveryStore(JdbcTemplate jdbc, NotificationRepository notifications,
                         WebhookProperties properties, RetryPolicy retries) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.properties = properties;
        this.retries = retries;
    }

    @Transactional
    public Optional<Notification> claim() {
        var due = jdbc.query("""
                SELECT * FROM notifications
                WHERE (delivery_state = 'PENDING' AND next_attempt_at <= clock_timestamp())
                   OR (delivery_state = 'IN_FLIGHT' AND lease_until <= clock_timestamp())
                ORDER BY next_attempt_at, event_id
                LIMIT 1 FOR UPDATE SKIP LOCKED
                """, NotificationRepository.MAPPER);
        if (due.isEmpty()) {
            return Optional.empty();
        }
        Notification n = due.getFirst();
        if ("IN_FLIGHT".equals(n.deliveryState())) {
            jdbc.update("""
                    UPDATE notification_attempts SET outcome='LEASE_EXPIRED', finished_at=clock_timestamp(),
                        failure_reason='LEASE_EXPIRED'
                    WHERE id=? AND outcome='STARTED'
                    """, n.claimToken());
        }
        if (n.attemptCount() >= properties.maxAttempts()) {
            jdbc.update("""
                    UPDATE notifications SET delivery_state='FAILED', claim_token=NULL, lease_until=NULL,
                        last_failure='ATTEMPTS_EXHAUSTED_AFTER_LEASE_EXPIRY', updated_at=clock_timestamp()
                    WHERE event_id=?
                    """, n.eventId());
            return Optional.empty();
        }
        UUID token = UUID.randomUUID();
        jdbc.update("""
                UPDATE notifications SET delivery_state='IN_FLIGHT', attempt_count=attempt_count+1,
                    claim_token=?, lease_until=clock_timestamp() + (? * interval '1 millisecond'),
                    updated_at=clock_timestamp() WHERE event_id=?
                """, token, properties.leaseDuration().toMillis(), n.eventId());
        jdbc.update("""
                INSERT INTO notification_attempts(id, event_id, attempt_number, outcome)
                VALUES (?, ?, ?, 'STARTED')
                """, token, n.eventId(), n.attemptCount() + 1);
        return notifications.findById(n.eventId());
    }

    @Transactional
    public boolean complete(Notification claim, DeliveryResult result) {
        var owned = jdbc.query("""
                SELECT * FROM notifications WHERE event_id=? AND claim_token=?
                  AND delivery_state='IN_FLIGHT' AND lease_until > clock_timestamp() FOR UPDATE
                """, NotificationRepository.MAPPER, claim.eventId(), claim.claimToken());
        if (owned.isEmpty()) {
            return false; // A stale worker must never overwrite a newer claim or completed result.
        }
        Notification current = owned.getFirst();
        boolean retry = !result.delivered() && result.retryable() && current.attemptCount() < properties.maxAttempts();
        String state = result.delivered() ? "DELIVERED" : retry ? "PENDING" : "FAILED";
        jdbc.update("""
                UPDATE notification_attempts SET outcome=?, response_status=?, duration_ms=?,
                    failure_reason=?, finished_at=clock_timestamp() WHERE id=? AND outcome='STARTED'
                """, result.delivered() ? "DELIVERED" : "FAILED", result.status(), result.durationMillis(),
                result.failure(), claim.claimToken());
        jdbc.update("""
                UPDATE notifications SET delivery_state=?, claim_token=NULL, lease_until=NULL,
                    next_attempt_at=clock_timestamp() + (? * interval '1 millisecond'),
                    delivered_at=CASE WHEN ?='DELIVERED' THEN clock_timestamp() ELSE NULL END,
                    last_failure=?, updated_at=clock_timestamp() WHERE event_id=?
                """, state, retry ? retries.delayMillis(current.attemptCount()) : 0L, state,
                result.delivered() ? null : result.failure(), claim.eventId());
        return true;
    }
}
