package com.jjesusdev.hookledger.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class OutboxRepository {

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Must be called inside a transaction. SKIP LOCKED lets multiple publishers share the backlog
     * without handing the same row to two workers.
     */
    public List<OutboxRow> claimBatch(int limit) {
        return jdbc.sql("""
                        SELECT id, event_id, payload::text AS payload, attempts, created_at
                        FROM outbox_event
                        WHERE published_at IS NULL
                        ORDER BY id
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("limit", limit)
                .query((rs, rowNum) -> new OutboxRow(
                        rs.getLong("id"),
                        UUID.fromString(rs.getString("event_id")),
                        rs.getString("payload"),
                        rs.getInt("attempts"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    public void insert(UUID eventId, String payload) {
        jdbc.sql("""
                        INSERT INTO outbox_event (event_id, payload)
                        VALUES (:eventId, CAST(:payload AS jsonb))
                        """)
                .param("eventId", eventId)
                .param("payload", payload)
                .update();
    }

    public void markPublished(long id) {
        jdbc.sql("""
                        UPDATE outbox_event
                        SET published_at = now(), attempts = attempts + 1
                        WHERE id = :id
                        """)
                .param("id", id)
                .update();
    }

    public long countPending() {
        return jdbc.sql("SELECT count(*) FROM outbox_event WHERE published_at IS NULL")
                .query((rs, rowNum) -> rs.getLong(1))
                .single();
    }

    public Instant oldestPendingCreatedAt() {
        List<Instant> values = jdbc.sql("""
                        SELECT min(created_at) AS oldest
                        FROM outbox_event
                        WHERE published_at IS NULL
                        """)
                .query((rs, rowNum) -> {
                    Timestamp oldest = rs.getTimestamp("oldest");
                    return oldest == null ? null : oldest.toInstant();
                })
                .list();
        return values.isEmpty() ? null : values.get(0);
    }
}
