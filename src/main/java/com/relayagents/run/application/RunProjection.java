package com.relayagents.run.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.relayagents.run.domain.RunEventType;
import com.relayagents.run.domain.RunStatus;
import com.relayagents.run.domain.StepStatus;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Rebuilds a run's state from its events alone. The tables are what the engine reads; this fold is how
 * tests prove the event log is complete enough to replace them.
 */
public final class RunProjection {

    public record State(RunStatus status, Map<String, StepStatus> steps, JsonNode output, String failure,
                        long tokens, long costMicros, int toolCalls) {
    }

    private RunProjection() {
    }

    public static State fold(List<RunEvent> events) {
        RunStatus status = null;
        Map<String, StepStatus> steps = new TreeMap<>();
        JsonNode output = null;
        String failure = null;
        long tokens = 0;
        long cost = 0;
        int toolCalls = 0;
        for (RunEvent event : events) {
            JsonNode payload = event.payload();
            if (payload.hasNonNull("runStatus")) {
                status = RunStatus.valueOf(payload.get("runStatus").asText());
                if (payload.hasNonNull("failure")) {
                    failure = payload.get("failure").asText();
                } else if (status == RunStatus.RUNNING) {
                    failure = null;
                }
            }
            if (event.stepId() != null && payload.hasNonNull("status")) {
                steps.put(event.stepId(), StepStatus.valueOf(payload.get("status").asText()));
            }
            if (event.type() == RunEventType.RUN_SUCCEEDED) {
                output = payload.get("output");
            }
            if (event.type() == RunEventType.LLM_CALLED) {
                tokens += payload.path("tokens").asLong();
                cost += payload.path("costMicros").asLong();
            }
            if (event.type() == RunEventType.TOOL_CALLED && !payload.path("replayed").asBoolean()) {
                toolCalls++;
            }
        }
        return new State(status, steps, output, failure, tokens, cost, toolCalls);
    }
}
