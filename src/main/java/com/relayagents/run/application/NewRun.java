package com.relayagents.run.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.relayagents.run.domain.BudgetLimits;
import com.relayagents.run.domain.RunMode;
import java.time.Instant;
import java.util.UUID;

public record NewRun(UUID id, String tenant, UUID workflowId, int workflowVersion, RunMode mode, UUID sourceRunId,
                     JsonNode input, BudgetLimits limits, Instant deadline, String createdBy, String idempotencyKey,
                     String requestHash) {
}
