package com.relayagents.run.application.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.relayagents.run.application.ApprovalPendingException;
import com.relayagents.run.application.ApprovalRecord;
import com.relayagents.run.application.JournalKeys;
import com.relayagents.run.application.ReplayDivergedException;
import com.relayagents.run.application.RunRepository;
import com.relayagents.run.application.StepContext;
import com.relayagents.run.application.StepExecutor;
import com.relayagents.run.application.StepFailedException;
import com.relayagents.run.application.StepOutcome;
import com.relayagents.shared.json.Json;
import com.relayagents.workflow.domain.StepConfig;
import com.relayagents.workflow.domain.StepType;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A gate step: the run pauses here until someone other than the person who started it approves.
 */
@Component
class ApprovalStepExecutor implements StepExecutor {

    static final int GATE_ITERATION = 0;

    private final RunRepository runs;
    private final Json json;

    ApprovalStepExecutor(RunRepository runs, Json json) {
        this.runs = runs;
        this.json = json;
    }

    @Override
    public StepType type() {
        return StepType.APPROVAL;
    }

    @Override
    public StepOutcome.Succeeded execute(StepContext ctx) {
        if (ctx.mode().isReplay()) {
            JsonNode decided = ctx.source().get(JournalKeys.approval(GATE_ITERATION))
                .orElseThrow(() -> new ReplayDivergedException("source run has no decision for gate '"
                    + ctx.step().id() + "'"));
            return outcome(decided.path("decision").asText(), decided.path("decidedBy").asText(),
                decided.path("comment").asText(null));
        }
        UUID runId = ctx.run().id();
        String stepId = ctx.step().id();
        var approval = runs.findApproval(runId, stepId, GATE_ITERATION);
        if (approval.isEmpty()) {
            StepConfig.Approval config = (StepConfig.Approval) ctx.step().config();
            runs.insertApproval(new ApprovalRecord(UUID.randomUUID(), runId, stepId, GATE_ITERATION, ctx.tenant(),
                ApprovalRecord.Kind.GATE, null, null, ctx.renderer().text(config.reason(), false),
                ApprovalRecord.Status.PENDING, ctx.run().createdBy(), null, null, null, null));
            approval = runs.findApproval(runId, stepId, GATE_ITERATION);
        }
        ApprovalRecord record = approval.orElseThrow();
        if (record.status() == ApprovalRecord.Status.PENDING) {
            throw new ApprovalPendingException(record.id());
        }
        return outcome(record.status().name(), record.decidedBy(), record.comment());
    }

    private StepOutcome.Succeeded outcome(String decision, String by, String comment) {
        if (!ApprovalRecord.Status.APPROVED.name().equals(decision)) {
            throw new StepFailedException("gate " + decision.toLowerCase() + " by " + by
                + (comment == null ? "" : ": " + comment), false);
        }
        return new StepOutcome.Succeeded(json.object().put("approvedBy", by).put("comment", comment), false, null);
    }
}
