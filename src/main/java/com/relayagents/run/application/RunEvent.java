package com.relayagents.run.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.relayagents.run.domain.RunEventType;
import java.time.Instant;
import java.util.UUID;

public record RunEvent(UUID runId, long seq, RunEventType type, String stepId, JsonNode payload, Instant createdAt) {
}
