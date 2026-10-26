package com.jjesusdev.hookledger.delivery;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
public class DeliveryRepository {

    private final JdbcClient jdbc;

    public DeliveryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A visibility timeout can redeliver the same SQS message at the same attempt number.
     * The unique constraint keeps the audit trail idempotent.
     */
    public void record(UUID eventId,
                       int attempt,
                       String target,
                       Integer statusCode,
                       DeliveryOutcome outcome,
                       String error) {
        jdbc.sql("""
                        INSERT INTO delivery_attempt
                            (event_id, attempt, target, status_code, outcome, error)
                        VALUES
                            (:eventId, :attempt, :target, :statusCode, :outcome, :error)
                        ON CONFLICT (event_id, attempt) DO NOTHING
                        """)
                .param("eventId", eventId)
                .param("attempt", attempt)
                .param("target", truncate(target, 512))
                .param("statusCode", statusCode)
                .param("outcome", outcome.name())
                .param("error", truncate(error, 1024))
                .update();
    }

    public List<DeliveryAttempt> findByEvent(UUID eventId) {
        return jdbc.sql("""
                        SELECT id, event_id, attempt, target, status_code, outcome, error, created_at
                        FROM delivery_attempt
                        WHERE event_id = :eventId
                        ORDER BY attempt, created_at
                        """)
                .param("eventId", eventId)
                .query(DeliveryRepository::mapRow)
                .list();
    }

    public long countByOutcome(DeliveryOutcome outcome) {
        return jdbc.sql("SELECT count(*) FROM delivery_attempt WHERE outcome = :outcome")
                .param("outcome", outcome.name())
                .query((rs, rowNum) -> rs.getLong(1))
                .single();
    }

    private static DeliveryAttempt mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Integer statusCode = rs.getInt("status_code");
        if (rs.wasNull()) {
            statusCode = null;
        }
        return new DeliveryAttempt(
                rs.getLong("id"),
                UUID.fromString(rs.getString("event_id")),
                rs.getInt("attempt"),
                rs.getString("target"),
                statusCode,
                DeliveryOutcome.valueOf(rs.getString("outcome")),
                rs.getString("error"),
                createdAt.toInstant()
        );
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
