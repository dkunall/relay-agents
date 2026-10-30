package com.relayagents.run.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relayagents.agent.application.AgentService;
import com.relayagents.run.domain.BudgetLimits;
import com.relayagents.run.domain.RunMode;
import com.relayagents.run.domain.RunStatus;
import com.relayagents.shared.domain.Actor;
import com.relayagents.shared.error.BusinessRuleException;
import com.relayagents.shared.error.ForbiddenException;
import com.relayagents.shared.error.InvalidRequestException;
import com.relayagents.shared.error.NotFoundException;
import com.relayagents.shared.error.QuotaExceededException;
import com.relayagents.shared.error.StateConflictException;
import com.relayagents.shared.json.Json;
import com.relayagents.shared.crypto.Sha256;
import com.relayagents.workflow.application.StoredWorkflow;
import com.relayagents.workflow.application.WorkflowService;
import com.relayagents.workflow.domain.StepConfig;
import com.relayagents.workflow.domain.StepDefinition;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RunService {

    public static final BudgetLimits DEFAULT_BUDGET = new BudgetLimits(50_000, 200_000, 20, Duration.ofMinutes(10));
    private static final int IDEMPOTENCY_KEY_MAX = 128;

    private final RunRepository runs;
    private final RunEngine engine;
    private final WorkflowService workflows;
    private final AgentService agents;
    private final RunProperties properties;
    private final Json json;
    private final Clock clock;

    public RunService(RunRepository runs, RunEngine engine, WorkflowService workflows, AgentService agents,
                      RunProperties properties, Json json, Clock clock) {
        this.runs = runs;
        this.engine = engine;
        this.workflows = workflows;
        this.agents = agents;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    public record Started(RunRecord run, boolean existing) {
    }

    /**
     * Starts a run, or returns the run an earlier request with the same idempotency key started.
     */
    @Transactional
    public Started start(Actor actor, UUID workflowId, Integer version, JsonNode input, BudgetLimits budget,
                         String idempotencyKey) {
        JsonNode runInput = input == null || input.isNull() ? json.object() : input;
        if (!runInput.isObject()) {
            throw new InvalidRequestException("invalid-input", "Run input must be a JSON object");
        }
        if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > IDEMPOTENCY_KEY_MAX)) {
            throw new InvalidRequestException("invalid-idempotency-key", "Idempotency-Key must be 1-128 characters");
        }
        StoredWorkflow workflow = workflows.get(actor.tenant(), workflowId, version);
        BudgetLimits limits = budget == null ? DEFAULT_BUDGET : budget;
        String hash = Sha256.hex(json.canonical(json.object()
            .put("workflowId", workflow.id().toString())
            .put("version", workflow.version())
            .<ObjectNode>set("input", runInput)
            .set("budget", json.tree(limits))));

        runs.lockTenant(actor.tenant());
        if (idempotencyKey != null) {
            var earlier = runs.findByIdempotencyKey(actor.tenant(), idempotencyKey);
            if (earlier.isPresent()) {
                if (!hash.equals(earlier.get().idempotencyHash())) {
                    throw new BusinessRuleException("idempotency-key-reused",
                        "Idempotency-Key was already used for a different request");
                }
                return new Started(earlier.get(), true);
            }
        }
        return new Started(create(actor, workflow, runInput, limits, RunMode.LIVE, null, idempotencyKey, hash),
            false);
    }

    /**
     * Runs a finished run again. REPLAY_RECORDED serves every model, tool and retrieval call from the source
     * run's events; REPLAY_LIVE calls the model again but still serves side-effecting tools from the record.
     */
    @Transactional
    public RunRecord replay(Actor actor, UUID sourceRunId, RunMode mode) {
        if (!mode.isReplay()) {
            throw new InvalidRequestException("invalid-mode", "Replay mode must be REPLAY_RECORDED or REPLAY_LIVE");
        }
        RunRecord source = get(actor, sourceRunId);
        if (!source.status().isTerminal()) {
            throw new StateConflictException("run-not-finished",
                "Only finished runs can be replayed");
        }
        StoredWorkflow workflow = workflows.get(actor.tenant(), source.workflowId(), source.workflowVersion());
        BudgetLimits limits = new BudgetLimits(source.maxTokens(), source.maxCostMicros(), source.maxToolCalls(),
            Duration.between(source.createdAt(), source.deadline()));
        runs.lockTenant(actor.tenant());
        return create(actor, workflow, source.input(), limits, mode, source.id(), null, null);
    }

    public RunRecord get(Actor actor, UUID runId) {
        return runs.find(actor.tenant(), runId)
            .orElseThrow(() -> new NotFoundException("run-not-found", "No run " + runId));
    }

    public List<RunRecord> list(Actor actor, RunStatus status, int limit) {
        return runs.list(actor.tenant(), status, Math.clamp(limit, 1, 200));
    }

    public List<StepRecord> steps(Actor actor, UUID runId) {
        return runs.steps(get(actor, runId).id());
    }

    public List<RunEvent> events(Actor actor, UUID runId, long afterSeq, int limit) {
        return runs.events(get(actor, runId).id(), afterSeq, Math.clamp(limit, 1, 1000));
    }

    public void cancel(Actor actor, UUID runId) {
        engine.cancel(actor, runId);
    }

    public void resume(Actor actor, UUID runId) {
        engine.resume(actor, runId);
    }

    public void decide(Actor actor, UUID runId, String stepId, boolean approve, String comment) {
        if (!actor.has(Actor.APPROVE)) {
            throw new ForbiddenException("missing-scope", "Deciding approvals needs " + Actor.APPROVE);
        }
        if (comment != null && comment.length() > 2000) {
            throw new InvalidRequestException("comment-too-long", "Comment is limited to 2000 characters");
        }
        engine.decide(actor, runId, stepId, approve, comment);
    }

    public List<ApprovalRecord> pendingApprovals(Actor actor) {
        return runs.pendingApprovals(actor.tenant(), 200);
    }

    public List<RunRepository.CostRow> costs(Actor actor, int days) {
        return runs.costsByWorkflow(actor.tenant(), clock.instant().minus(Duration.ofDays(Math.clamp(days, 1, 90))));
    }

    private RunRecord create(Actor actor, StoredWorkflow workflow, JsonNode input, BudgetLimits limits, RunMode mode,
                             UUID sourceRunId, String idempotencyKey, String hash) {
        checkQuota(actor.tenant());
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        NewRun run = new NewRun(id, actor.tenant(), workflow.id(), workflow.version(), mode, sourceRunId, input,
            limits, now.plus(limits.maxDuration()), actor.subject(), idempotencyKey, hash);
        ObjectNode started = json.object()
            .put("workflowId", workflow.id().toString())
            .put("workflowVersion", workflow.version())
            .put("mode", mode.name())
            .put("createdBy", actor.subject());
        started.put("sourceRunId", sourceRunId == null ? null : sourceRunId.toString());
        started.set("input", input);
        started.set("budget", json.tree(limits));
        started.set("agents", agentSnapshot(actor, workflow));
        engine.create(run, workflow.definition(), started);
        return runs.findById(id).orElseThrow();
    }

    // Agents are copied into the run so editing an agent later cannot change what a run or its replay does.
    private ArrayNode agentSnapshot(Actor actor, StoredWorkflow workflow) {
        TreeSet<String> names = new TreeSet<>();
        for (StepDefinition step : workflow.definition().steps()) {
            if (step.config() instanceof StepConfig.Agent agent) {
                names.add(agent.agent());
            }
        }
        ArrayNode snapshot = json.array();
        names.forEach(name -> snapshot.add(json.tree(agents.get(actor, name))));
        return snapshot;
    }

    private void checkQuota(String tenant) {
        RunRepository.Quota quota = runs.quota(tenant).orElse(
            new RunRepository.Quota(properties.defaultMaxConcurrentRuns(), properties.defaultDailyCostCapMicros()));
        if (runs.activeRuns(tenant) >= quota.maxConcurrentRuns()) {
            throw new QuotaExceededException("concurrent-runs",
                "Tenant already has " + quota.maxConcurrentRuns() + " active runs");
        }
        if (runs.costSince(tenant, clock.instant().minus(Duration.ofDays(1))) >= quota.dailyCostCapMicros()) {
            throw new QuotaExceededException("daily-cost-cap", "Tenant spent its cost cap in the last 24 hours");
        }
    }
}
