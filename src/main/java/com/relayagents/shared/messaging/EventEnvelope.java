package com.relayagents.shared.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * What goes on the wire. Consumers dedupe on {@code eventId}, because the relay delivers at least once.
 */
public record EventEnvelope(UUID eventId, String eventType, Instant occurredAt, JsonNode payload) {
}
