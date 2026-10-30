package com.relayagents.trigger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.relayagents.shared.error.InvalidRequestException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TriggerSignatureTest {

    @Test
    void hmacMatchesTheKnownVector() {
        assertThat(TriggerService.hmac("key", "The quick brown fox jumps over the lazy dog"))
            .isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }

    @Test
    void cronRunsInUtcAndAtMostOncePerMinute() {
        Instant after = Instant.parse("2026-10-30T10:15:30Z");
        assertThat(TriggerService.nextFire(TriggerService.parseCron("0 0 * * * *"), after))
            .isEqualTo(Instant.parse("2026-10-30T11:00:00Z"));
        assertThatThrownBy(() -> TriggerService.nextFire(TriggerService.parseCron("*/10 * * * * *"), after))
            .isInstanceOf(InvalidRequestException.class).hasMessageContaining("once a minute");
        assertThatThrownBy(() -> TriggerService.parseCron("every day")).isInstanceOf(InvalidRequestException.class);
    }
}
