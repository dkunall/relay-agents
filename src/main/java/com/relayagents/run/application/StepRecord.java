package com.relayagents.run.application;

import com.relayagents.run.domain.StepStatus;
import com.relayagents.workflow.domain.StepType;
import java.time.Instant;
import java.util.UUID;

public record StepRecord(UUID runId, String stepId, StepType type, StepStatus status, int attempt, int maxAttempts,
                         Instant availableAt, String leaseOwner, Instant leaseExpiresAt, String lastError) {

    public boolean heldBy(StepFence fence) {
        return status == StepStatus.RUNNING && fence.workerId().equals(leaseOwner) && attempt == fence.attempt();
    }
}
