package com.relayagents.run.application;

import java.util.UUID;

/**
 * Identifies one attempt of one step by one worker. Every write a worker makes is checked against the
 * current step row, so a worker whose lease expired cannot overwrite the work of the one that took over.
 */
public record StepFence(UUID runId, String stepId, String workerId, int attempt) {
}
