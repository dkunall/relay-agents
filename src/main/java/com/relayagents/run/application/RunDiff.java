package com.relayagents.run.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.relayagents.run.domain.RunEventType;
import com.relayagents.shared.domain.Actor;
import com.relayagents.shared.error.InvalidRequestException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Compares a replay with the run it replayed: which step outputs changed and which model calls saw a
 * different prompt or gave a different answer.
 */
@Component
public class RunDiff {

    public record StepDiff(String stepId, String sourceStatus, String status, boolean outputChanged) {
    }

    public record CallDiff(String stepId, String key, boolean inSource, boolean inReplay, boolean sameRequest,
                           boolean sameResponse, long sourceTokens, long tokens) {
    }

    public record Diff(UUID sourceRunId, UUID runId, String mode, boolean sameOutput, List<StepDiff> steps,
                       List<CallDiff> modelCalls) {
    }

    private final RunService service;
    private final RunRepository runs;
    private final RunEngine engine;

    RunDiff(RunService service, RunRepository runs, RunEngine engine) {
        this.service = service;
        this.runs = runs;
        this.engine = engine;
    }

    public Diff diff(Actor actor, UUID runId) {
        RunRecord run = service.get(actor, runId);
        if (run.sourceRunId() == null) {
            throw new InvalidRequestException("not-a-replay", "Run " + runId + " is not a replay");
        }
        RunRecord source = service.get(actor, run.sourceRunId());

        Map<String, StepRecord> sourceSteps = byId(runs.steps(source.id()));
        Map<String, StepRecord> steps = byId(runs.steps(run.id()));
        Map<String, StepOutput> sourceOutputs = engine.stepOutputs(source.id());
        Map<String, StepOutput> outputs = engine.stepOutputs(run.id());
        List<StepDiff> stepDiffs = new ArrayList<>();
        for (String id : new TreeSet<>(steps.keySet())) {
            StepRecord before = sourceSteps.get(id);
            stepDiffs.add(new StepDiff(id, before == null ? null : before.status().name(), steps.get(id).status().name(),
                !Objects.equals(value(sourceOutputs.get(id)), value(outputs.get(id)))));
        }

        Map<String, JsonNode> sourceCalls = calls(source.id());
        Map<String, JsonNode> calls = calls(run.id());
        TreeSet<String> keys = new TreeSet<>(sourceCalls.keySet());
        keys.addAll(calls.keySet());
        List<CallDiff> callDiffs = new ArrayList<>();
        for (String key : keys) {
            JsonNode a = sourceCalls.get(key);
            JsonNode b = calls.get(key);
            String[] parts = key.split("\\|", 2);
            callDiffs.add(new CallDiff(parts[0], parts[1], a != null, b != null,
                a != null && b != null && a.path("requestHash").equals(b.path("requestHash")),
                a != null && b != null && a.path("response").path("content").equals(b.path("response").path("content")),
                a == null ? 0 : a.path("tokens").asLong(), b == null ? 0 : b.path("tokens").asLong()));
        }
        return new Diff(source.id(), run.id(), run.mode().name(), Objects.equals(source.output(), run.output()),
            stepDiffs, callDiffs);
    }

    private Map<String, JsonNode> calls(UUID runId) {
        Map<String, JsonNode> calls = new LinkedHashMap<>();
        for (RunEvent event : runs.eventsOfTypes(runId, List.of(RunEventType.LLM_CALLED))) {
            calls.put(event.stepId() + "|" + event.payload().path("key").asText(), event.payload());
        }
        return calls;
    }

    private static Map<String, StepRecord> byId(List<StepRecord> steps) {
        Map<String, StepRecord> map = new LinkedHashMap<>();
        steps.forEach(s -> map.put(s.stepId(), s));
        return map;
    }

    private static JsonNode value(StepOutput output) {
        return output == null ? null : output.value();
    }
}
