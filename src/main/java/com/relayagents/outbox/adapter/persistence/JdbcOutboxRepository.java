package com.relayagents.outbox.adapter.persistence;

import com.relayagents.outbox.application.OutboxRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOutboxRepository implements OutboxRepository {

    private final JdbcClient jdbc;

    JdbcOutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(UUID eventId, String topic, String key, String eventType, String payloadJson) {
        jdbc.sql("""
                INSERT INTO outbox (event_id, topic, message_key, event_type, payload)
                VALUES (:event, :topic, :key, :type, CAST(:payload AS jsonb))
                """)
            .param("event", eventId)
            .param("topic", topic)
            .param("key", key)
            .param("type", eventType)
            .param("payload", payloadJson)
            .update();
    }

    @Override
    public List<OutboxMessage> lockUnpublished(int limit) {
        return jdbc.sql("""
                SELECT id, event_id, topic, message_key, event_type, payload::text AS payload, created_at
                FROM outbox WHERE published_at IS NULL
                ORDER BY id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """)
            .param("limit", limit)
            .query((rs, row) -> new OutboxMessage(
                rs.getLong("id"),
                rs.getObject("event_id", UUID.class),
                rs.getString("topic"),
                rs.getString("message_key"),
                rs.getString("event_type"),
                rs.getString("payload"),
                rs.getTimestamp("created_at").toInstant()))
            .list();
    }

    @Override
    public void markPublished(List<Long> ids, Instant at) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE outbox SET published_at = :at WHERE id IN (:ids)")
            .param("at", Timestamp.from(at))
            .param("ids", ids)
            .update();
    }

    @Override
    public long backlog() {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL").query(Long.class).single();
    }

    @Override
    public int purgePublishedBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM outbox WHERE published_at < :cutoff")
            .param("cutoff", Timestamp.from(cutoff))
            .update();
    }
}
