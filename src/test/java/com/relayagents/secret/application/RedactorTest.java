package com.relayagents.secret.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RedactorTest {

    @Test
    void longestSecretWinsAndShortValuesAreIgnored() {
        String text = Redactor.redact("auth=abcd1234wxyz and abcd1234 and id=7",
            Map.of("FULL", "abcd1234wxyz", "PREFIX", "abcd1234", "TINY", "7"));

        assertThat(text).isEqualTo("auth=[redacted:FULL] and [redacted:PREFIX] and id=7");
    }
}
