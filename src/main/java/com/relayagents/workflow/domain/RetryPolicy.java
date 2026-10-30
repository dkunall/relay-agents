package com.relayagents.workflow.domain;

import java.time.Duration;

public record RetryPolicy(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {

    public static final RetryPolicy DEFAULT = new RetryPolicy(3, Duration.ofMillis(500), Duration.ofSeconds(30));

    public RetryPolicy {
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new WorkflowValidationException("retry.maxAttempts must be between 1 and 10");
        }
        if (initialBackoff.isNegative() || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new WorkflowValidationException("retry backoff must be non-negative and max >= initial");
        }
    }
}
