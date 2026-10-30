package com.relayagents.workflow.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayagents.workflow.domain.RetryPolicy;
import com.relayagents.workflow.domain.StepConfig;
import com.relayagents.workflow.domain.StepDefinition;
import com.relayagents.workflow.domain.WorkflowDefinition;
import com.relayagents.workflow.domain.WorkflowValidationException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads the JSON workflow format. Field names follow the README; unknown step fields are rejected so a
 * typo like {@code "need"} does not silently produce a step with no dependencies.
 */
public final class WorkflowParser {

    private static final Set<String> COMMON = Set.of("id", "type", "needs", "retry", "timeoutMs", "cache");
    private static final Map<String, Set<String>> FIELDS = Map.of(
        "AGENT", Set.of("agent", "prompt", "maxIterations"),
        "TOOL", Set.of("tool", "args", "grants"),
        "RETRIEVE", Set.of("knowledgeBase", "query", "k"),
        "BRANCH", Set.of("on", "cases", "default"),
        "APPROVAL", Set.of("reason"));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WorkflowParser() {
    }

    public static WorkflowDefinition parse(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new WorkflowValidationException("A workflow must be a JSON object");
        }
        JsonNode steps = root.path("steps");
        if (!steps.isArray()) {
            throw new WorkflowValidationException("'steps' must be an array");
        }
        List<StepDefinition> definitions = new ArrayList<>();
        for (JsonNode step : steps) {
            definitions.add(step(step));
        }
        JsonNode output = root.get("output");
        return new WorkflowDefinition(text(root, "name", "workflow"), definitions,
            output == null || output.isNull() ? null : output.asText());
    }

    private static StepDefinition step(JsonNode node) {
        if (!node.isObject()) {
            throw new WorkflowValidationException("Each step must be an object");
        }
        String id = text(node, "id", "step");
        String type = text(node, "type", id).toUpperCase();
        Set<String> allowed = FIELDS.get(type);
        if (allowed == null) {
            throw new WorkflowValidationException("Step '" + id + "' has unknown type " + type);
        }
        node.fieldNames().forEachRemaining(field -> {
            if (!COMMON.contains(field) && !allowed.contains(field)) {
                throw new WorkflowValidationException("Step '" + id + "' has unknown field '" + field + "'");
            }
        });
        StepConfig config = switch (type) {
            case "AGENT" -> new StepConfig.Agent(text(node, "agent", id), text(node, "prompt", id),
                node.path("maxIterations").asInt(4));
            case "TOOL" -> new StepConfig.Tool(text(node, "tool", id), map(node.path("args")), strings(node.path("grants")));
            case "RETRIEVE" -> new StepConfig.Retrieve(uuid(node, id), text(node, "query", id), node.path("k").asInt(4));
            case "BRANCH" -> new StepConfig.Branch(text(node, "on", id), cases(node.path("cases")),
                List.copyOf(strings(node.path("default"))));
            case "APPROVAL" -> new StepConfig.Approval(text(node, "reason", id));
            default -> throw new IllegalStateException(type);
        };
        JsonNode retry = node.path("retry");
        RetryPolicy policy = retry.isMissingNode() ? RetryPolicy.DEFAULT : new RetryPolicy(
            retry.path("maxAttempts").asInt(RetryPolicy.DEFAULT.maxAttempts()),
            Duration.ofMillis(retry.path("initialBackoffMs").asLong(RetryPolicy.DEFAULT.initialBackoff().toMillis())),
            Duration.ofMillis(retry.path("maxBackoffMs").asLong(RetryPolicy.DEFAULT.maxBackoff().toMillis())));
        return new StepDefinition(id, List.copyOf(strings(node.path("needs"))), config, policy,
            Duration.ofMillis(node.path("timeoutMs").asLong(60_000)), node.path("cache").asBoolean(false));
    }

    private static String text(JsonNode node, String field, String owner) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isEmpty()) {
            throw new WorkflowValidationException("'" + owner + "' needs a string field '" + field + "'");
        }
        return value.asText();
    }

    private static UUID uuid(JsonNode node, String owner) {
        try {
            return UUID.fromString(text(node, "knowledgeBase", owner));
        } catch (IllegalArgumentException e) {
            throw new WorkflowValidationException("'" + owner + "' knowledgeBase must be a UUID");
        }
    }

    private static Set<String> strings(JsonNode node) {
        Set<String> out = new LinkedHashSet<>();
        if (node.isMissingNode() || node.isNull()) {
            return out;
        }
        if (!node.isArray()) {
            throw new WorkflowValidationException("Expected an array of strings, got " + node.getNodeType());
        }
        node.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static Map<String, Object> map(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new WorkflowValidationException("Tool 'args' must be an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> args = MAPPER.convertValue(node, LinkedHashMap.class);
        return args;
    }

    private static Map<String, List<String>> cases(JsonNode node) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (!node.isObject()) {
            throw new WorkflowValidationException("Branch 'cases' must be an object of value -> [step ids]");
        }
        node.properties().forEach(e -> out.put(e.getKey(), List.copyOf(strings(e.getValue()))));
        return out;
    }
}
