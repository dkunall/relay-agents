package com.relayagents.run.domain;

import com.relayagents.workflow.domain.RetryPolicy;
import java.time.Duration;

public final class Backoff {

    private Backoff() {
    }

    /**
     * Exponential backoff with "equal jitter": half the delay is fixed, half random, so retries from many
     * runs hitting the same broken dependency spread out but still back off.
     *
     * @param failedAttempt 1 for the first attempt
     * @param random        a value in [0, 1)
     */
    public static Duration delay(RetryPolicy policy, int failedAttempt, double random) {
        long initial = policy.initialBackoff().toMillis();
        long max = policy.maxBackoff().toMillis();
        int shift = Math.min(failedAttempt - 1, 30);
        long exponential = initial <= 0 ? 0 : Math.min(max, initial << shift);
        if (exponential < 0) {
            exponential = max;
        }
        long half = exponential / 2;
        return Duration.ofMillis(half + (long) (random * (exponential - half)));
    }
}
