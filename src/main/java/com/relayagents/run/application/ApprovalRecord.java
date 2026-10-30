package com.relayagents.run.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record ApprovalRecord(UUID id, UUID runId, String stepId, int iteration, String tenant, Kind kind, String tool,
                             JsonNode arguments, String reason, Status status, String requestedBy, String decidedBy,
                             Instant decidedAt, String comment, Instant createdAt) {

    public enum Kind {
        TOOL,
        GATE
    }

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED,
        EXPIRED
    }
}
