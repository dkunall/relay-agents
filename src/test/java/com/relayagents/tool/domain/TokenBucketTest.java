package com.relayagents.tool.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenBucketTest {

    @Test
    void burstsToCapacityThenRefillsOverTime() {
        TokenBucket bucket = new TokenBucket(2, 1.0, 0);
        assertThat(bucket.tryTake(0)).isTrue();
        assertThat(bucket.tryTake(0)).isTrue();
        assertThat(bucket.tryTake(0)).isFalse();
        assertThat(bucket.tryTake(999)).isFalse();
        assertThat(bucket.tryTake(1000)).isTrue();
        assertThat(bucket.available(60_000)).isEqualTo(2.0);
    }
}
