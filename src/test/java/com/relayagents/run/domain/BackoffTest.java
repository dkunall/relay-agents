package com.relayagents.run.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.relayagents.workflow.domain.RetryPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackoffTest {

    private static final RetryPolicy POLICY = new RetryPolicy(10, Duration.ofMillis(200), Duration.ofSeconds(5));

    @Test
    void doublesPerAttemptWithinTheJitterBand() {
        assertThat(Backoff.delay(POLICY, 1, 0.0)).isEqualTo(Duration.ofMillis(100));
        assertThat(Backoff.delay(POLICY, 1, 0.999)).isLessThan(Duration.ofMillis(200));
        assertThat(Backoff.delay(POLICY, 3, 0.0)).isEqualTo(Duration.ofMillis(400));
    }

    @Test
    void capsAtMaxEvenForHugeAttemptNumbers() {
        assertThat(Backoff.delay(POLICY, 64, 0.999)).isLessThanOrEqualTo(Duration.ofSeconds(5));
        assertThat(Backoff.delay(POLICY, 64, 0.0)).isEqualTo(Duration.ofMillis(2500));
    }
}
