package com.relayagents.run.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.relayagents.run.domain.RunMode;
import com.relayagents.run.domain.RunStatus;
import java.time.Instant;
import java.util.UUID;

public record RunRecord(UUID id, String tenant, UUID workflowId, int workflowVersion, RunStatus status, RunMode mode,
                        UUID sourceRunId, JsonNode input, JsonNode output, String failure, long maxTokens,
                        long maxCostMicros, int maxToolCalls, Instant deadline, long spentTokens,
                        long spentCostMicros, int toolCalls, long reservedTokens, long reservedCostMicros,
                        String createdBy, Instant createdAt, Instant updatedAt, String idempotencyHash) {
}
