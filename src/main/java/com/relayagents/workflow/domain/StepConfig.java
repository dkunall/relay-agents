package com.relayagents.workflow.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Type-specific settings of a step. Values that hold {@code {{...}}} placeholders are rendered at run time.
 */
public sealed interface StepConfig {

    StepType type();

    /**
     * Every template string in this config, used to check that a step only reads from its ancestors.
     */
    List<String> templates();

    record Agent(String agent, String prompt, int maxIterations) implements StepConfig {
        public Agent {
            Objects.requireNonNull(agent, "agent");
            Objects.requireNonNull(prompt, "prompt");
            if (maxIterations < 1 || maxIterations > 10) {
                throw new WorkflowValidationException("maxIterations must be between 1 and 10");
            }
        }

        @Override
        public StepType type() {
            return StepType.AGENT;
        }

        @Override
        public List<String> templates() {
            return List.of(prompt);
        }
    }

    /**
     * @param args  JSON-shaped tree (maps, lists, strings, numbers, booleans); string leaves are templates
     * @param grants scopes the workflow author grants this step; they must cover the tool's scopes
     */
    record Tool(String tool, Map<String, Object> args, Set<String> grants) implements StepConfig {
        public Tool {
            Objects.requireNonNull(tool, "tool");
            args = Map.copyOf(args);
            grants = Set.copyOf(grants);
        }

        @Override
        public StepType type() {
            return StepType.TOOL;
        }

        @Override
        public List<String> templates() {
            return TemplateStrings.collect(args);
        }
    }

    record Retrieve(UUID knowledgeBase, String query, int k) implements StepConfig {
        public Retrieve {
            Objects.requireNonNull(knowledgeBase, "knowledgeBase");
            Objects.requireNonNull(query, "query");
            if (k < 1 || k > 20) {
                throw new WorkflowValidationException("k must be between 1 and 20");
            }
        }

        @Override
        public StepType type() {
            return StepType.RETRIEVE;
        }

        @Override
        public List<String> templates() {
            return List.of(query);
        }
    }

    /**
     * Renders {@code on}, then picks the targets of the first case whose key equals the value (trimmed,
     * case-insensitive), or {@code defaultTargets}. Dependents not picked are skipped.
     */
    record Branch(String on, Map<String, List<String>> cases, List<String> defaultTargets) implements StepConfig {
        public Branch {
            Objects.requireNonNull(on, "on");
            cases = Map.copyOf(cases);
            defaultTargets = List.copyOf(defaultTargets);
        }

        public List<String> choose(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase();
            return cases.entrySet().stream()
                .filter(c -> c.getKey().trim().toLowerCase().equals(normalized))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(defaultTargets);
        }

        @Override
        public StepType type() {
            return StepType.BRANCH;
        }

        @Override
        public List<String> templates() {
            return List.of(on);
        }
    }

    record Approval(String reason) implements StepConfig {
        public Approval {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public StepType type() {
            return StepType.APPROVAL;
        }

        @Override
        public List<String> templates() {
            return List.of(reason);
        }
    }
}
