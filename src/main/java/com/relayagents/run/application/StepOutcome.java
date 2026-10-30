package com.relayagents.run.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.relayagents.run.domain.BudgetKind;
import java.util.List;
import java.util.UUID;

public sealed interface StepOutcome {

    /**
     * @param untrusted      the output carries text the workflow author does not control
     * @param branchTargets  for branch steps, the dependents chosen; null otherwise
     */
    record Succeeded(JsonNode output, boolean untrusted, List<String> branchTargets) implements StepOutcome {
    }

    record Failed(String error, boolean retriable) implements StepOutcome {
    }

    record WaitingApproval(UUID approvalId) implements StepOutcome {
    }

    record BudgetExceeded(BudgetKind kind, String detail) implements StepOutcome {
    }
}
