package com.relayagents.run.domain;

import java.time.Duration;

/**
 * Hard ceilings for one run. Cost is in micro-dollars so it stays an integer.
 */
public record BudgetLimits(long maxTokens, long maxCostMicros, int maxToolCalls, Duration maxDuration) {

    public BudgetLimits {
        if (maxTokens <= 0 || maxTokens > 10_000_000) {
            throw new IllegalArgumentException("maxTokens must be between 1 and 10,000,000");
        }
        if (maxCostMicros < 0 || maxCostMicros > 100_000_000) {
            throw new IllegalArgumentException("maxCostMicros must be between 0 and 100,000,000");
        }
        if (maxToolCalls < 0 || maxToolCalls > 1000) {
            throw new IllegalArgumentException("maxToolCalls must be between 0 and 1000");
        }
        if (maxDuration.isNegative() || maxDuration.isZero() || maxDuration.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("maxDuration must be between 1s and 24h");
        }
    }
}
