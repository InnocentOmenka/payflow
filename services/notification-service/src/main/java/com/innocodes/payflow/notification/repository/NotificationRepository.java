package com.innocodes.payflow.notification.repository;

import com.innocodes.payflow.notification.model.Notification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class NotificationRepository {
    private final JdbcTemplate jdbc;

    public NotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Notification> findById(UUID eventId) {
        return jdbc.query("SELECT * FROM notifications WHERE event_id = ?", MAPPER, eventId).stream().findFirst();
    }

    public Optional<Notification> findByPaymentId(UUID paymentId) {
        return jdbc.query("SELECT * FROM notifications WHERE payment_id = ?", MAPPER, paymentId).stream().findFirst();
    }

    public void insert(UUID eventId, UUID paymentId, String merchantId, String paymentStatus,
                       String eventPayload, String webhookPayload) {
        jdbc.update("""
                INSERT INTO notifications(event_id, payment_id, merchant_id, event_type,
                    payment_status, event_payload, webhook_payload, destination_ref)
                VALUES (?, ?, ?, 'PaymentProcessed', ?, ?, ?, ?)
                """, eventId, paymentId, merchantId, paymentStatus, eventPayload, webhookPayload, merchantId);
    }

    public static final RowMapper<Notification> MAPPER = (rs, row) -> new Notification(
            rs.getObject("event_id", UUID.class), rs.getObject("payment_id", UUID.class),
            rs.getString("merchant_id"), rs.getString("event_type"), rs.getString("payment_status"),
            rs.getString("event_payload"), rs.getString("webhook_payload"), rs.getString("destination_ref"),
            rs.getString("delivery_state"), rs.getInt("attempt_count"), instant(rs, "next_attempt_at"),
            rs.getObject("claim_token", UUID.class), instant(rs, "lease_until"), instant(rs, "created_at"),
            instant(rs, "updated_at"), instant(rs, "delivered_at"), rs.getString("last_failure")
    );

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
