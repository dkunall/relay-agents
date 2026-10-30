package com.relayagents.outbox.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxWriter {

    private final OutboxRepository repository;
    private final ObjectMapper json;

    public OutboxWriter(OutboxRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String topic, String key, String eventType, Object payload) {
        UUID eventId = UUID.randomUUID();
        try {
            repository.insert(eventId, topic, key, eventType, json.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Event payload is not serialisable", e);
        }
        return eventId;
    }
}
