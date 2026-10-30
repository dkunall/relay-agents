package com.relayagents.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.relayagents.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReplayIT extends IntegrationTest {

    @Test
    void recordedReplayReproducesTheRunWithoutCallingToolsAgain() {
        useTenant("acme");
        String workflow = createSupportWorkflow();
        String source = startRun(workflow, "{\"input\": {\"message\": \"Where is my order O-5001?\"}}")
            .path("id").asText();
        JsonNode original = awaitStatus(source, "SUCCEEDED", "FAILED");
        assertThat(original.path("status").asText()).isEqualTo("SUCCEEDED");
        long toolRows = jdbc.sql("SELECT count(*) FROM tool_invocation").query(Long.class).single();

        String replay = writer.post("/v1/runs/" + source + "/replay", Map.of("mode", "REPLAY_RECORDED"))
            .expect(201).body().path("id").asText();
        JsonNode replayed = awaitStatus(replay, "SUCCEEDED", "FAILED");

        assertThat(replayed.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(replayed.path("output")).isEqualTo(original.path("output"));
        assertThat(replayed.path("usage").path("costMicros").asLong()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM tool_invocation").query(Long.class).single()).isEqualTo(toolRows);
        JsonNode diff = writer.get("/v1/runs/" + replay + "/diff").expect(200).body();
        assertThat(diff.path("sameOutput").asBoolean()).isTrue();
    }
}
