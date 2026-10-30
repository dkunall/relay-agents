package com.relayagents.workflow.domain;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record StepDefinition(String id, List<String> needs, StepConfig config, RetryPolicy retry, Duration timeout,
                             boolean cache) {

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{0,39}");
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);

    public StepDefinition {
        Objects.requireNonNull(id, "id");
        if (!ID.matcher(id).matches()) {
            throw new WorkflowValidationException("Step id '" + id + "' must match " + ID.pattern());
        }
        needs = List.copyOf(needs);
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(retry, "retry");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new WorkflowValidationException("Step '" + id + "' timeout must be between 1ms and 10m");
        }
    }

    public StepType type() {
        return config.type();
    }
}
