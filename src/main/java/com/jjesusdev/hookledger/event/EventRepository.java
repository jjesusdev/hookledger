package com.jjesusdev.hookledger.event;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class EventRepository {

    private static final String COLUMNS = """
            id, source, external_id, event_type, payload_hash, status, received_at, published_at
            """;

    private final JdbcClient jdbc;

    public EventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Atomic idempotency: the unique constraint decides the winner, not a read-then-write check.
     */
    public boolean insertIfAbsent(UUID id,
                                  String source,
                                  String externalId,
                                  String eventType,
                                  String payload,
                                  String payloadHash,
                                  String headers) {
        return jdbc.sql("""
                        INSERT INTO webhook_event
                            (id, source, external_id, event_type, payload, payload_hash, headers, status)
                        VALUES
                            (:id, :source, :externalId, :eventType, CAST(:payload AS jsonb), :payloadHash,
                             CAST(:headers AS jsonb), 'RECEIVED')
                        ON CONFLICT (source, external_id) DO NOTHING
                        """)
                .param("id", id)
                .param("source", source)
                .param("externalId", externalId)
                .param("eventType", eventType)
                .param("payload", payload)
                .param("payloadHash", payloadHash)
                .param("headers", headers)
                .update() == 1;
    }

    public Optional<EventRow> findBySourceAndExternalId(String source, String externalId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM webhook_event WHERE source = :source AND external_id = :externalId")
                .param("source", source)
                .param("externalId", externalId)
                .query(EventRepository::mapRow)
                .optional();
    }

    public Optional<EventRow> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM webhook_event WHERE id = :id")
                .param("id", id)
                .query(EventRepository::mapRow)
                .optional();
    }

    public void markPublished(UUID id) {
        jdbc.sql("""
                        UPDATE webhook_event
                        SET status = 'PUBLISHED', published_at = now()
                        WHERE id = :id AND status = 'RECEIVED'
                        """)
                .param("id", id)
                .update();
    }

    public void markDelivered(UUID id) {
        jdbc.sql("""
                        UPDATE webhook_event
                        SET status = 'DELIVERED'
                        WHERE id = :id AND status IN ('RECEIVED', 'PUBLISHED')
                        """)
                .param("id", id)
                .update();
    }

    public void markFailed(UUID id) {
        jdbc.sql("""
                        UPDATE webhook_event
                        SET status = 'FAILED'
                        WHERE id = :id AND status IN ('RECEIVED', 'PUBLISHED')
                        """)
                .param("id", id)
                .update();
    }

    public Map<String, Long> countByStatus() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (EventStatus status : EventStatus.values()) {
            counts.put(status.name(), 0L);
        }
        List<Map.Entry<String, Long>> rows = jdbc.sql("""
                        SELECT status, count(*) AS total
                        FROM webhook_event
                        GROUP BY status
                        """)
                .query((rs, rowNum) -> Map.entry(rs.getString("status"), rs.getLong("total")))
                .list();
        rows.forEach(row -> counts.put(row.getKey(), row.getValue()));
        return counts;
    }

    private static EventRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Timestamp publishedAt = rs.getTimestamp("published_at");
        return new EventRow(
                UUID.fromString(rs.getString("id")),
                rs.getString("source"),
                rs.getString("external_id"),
                rs.getString("event_type"),
                rs.getString("payload_hash"),
                EventStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("received_at").toInstant(),
                publishedAt == null ? null : publishedAt.toInstant()
        );
    }
}
