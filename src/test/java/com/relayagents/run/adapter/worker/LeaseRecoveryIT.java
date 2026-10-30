package com.relayagents.run.adapter.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.relayagents.run.application.RunEngine;
import com.relayagents.run.application.StepFence;
import com.relayagents.run.application.StepOutcome;
import com.relayagents.support.Fixtures;
import com.relayagents.support.IntegrationTest;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class LeaseRecoveryIT extends IntegrationTest {

    @Autowired
    StepWorkerPool workers;

    @Autowired
    RunEngine engine;

    @Test
    void stepHeldByADeadWorkerIsRetriedAndItsLateOutcomeIsDropped() {
        saveAgent("triage", Fixtures.text("agent-triage.json"));
        String workflow = createWorkflow("""
            {"name": "classify-only", "steps": [
              {"id": "classify", "type": "AGENT", "agent": "triage", "prompt": "{{input.message}}"}
            ]}
            """);

        Optional<StepFence> dead;
        String runId;
        workers.stop();
        try {
            runId = startRun(workflow, "{\"input\": {\"message\": \"Where is O-5003?\"}}").path("id").asText();
            // Claim the step as a worker that then goes silent: no heartbeat, no outcome.
            dead = engine.claim("dead-worker", Duration.ofMillis(200));
        } finally {
            workers.start();
        }
        assertThat(dead).isPresent();
        assertThat(dead.get().runId().toString()).isEqualTo(runId);

        JsonNode done = awaitStatus(runId, "SUCCEEDED", "FAILED");
        assertThat(done.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.path("steps").get(0).path("attempt").asInt()).isEqualTo(2);

        boolean accepted = engine.complete(dead.get(), new StepOutcome.Failed("late result from a dead worker", false));

        assertThat(accepted).isFalse();
        assertThat(writer.get("/v1/runs/" + runId).body().path("status").asText()).isEqualTo("SUCCEEDED");
        String events = writer.get("/v1/runs/" + runId + "/events").body().toString();
        assertThat(events).contains("STEP_LEASE_EXPIRED").contains("dead-worker");
    }
}
