package com.relayagents.outbox.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository {

    void insert(UUID eventId, String topic, String key, String eventType, String payloadJson);

    List<OutboxMessage> lockUnpublished(int limit);

    void markPublished(List<Long> ids, Instant at);

    long backlog();

    int purgePublishedBefore(Instant cutoff);

    record OutboxMessage(long id, UUID eventId, String topic, String key, String eventType, String payloadJson,
                         Instant createdAt) {
    }
}
