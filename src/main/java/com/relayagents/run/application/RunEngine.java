package com.relayagents.run.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relayagents.outbox.application.OutboxWriter;
import com.relayagents.run.domain.Backoff;
import com.relayagents.run.domain.BudgetKind;
import com.relayagents.run.domain.RunEventType;
import com.relayagents.run.domain.RunStatus;
import com.relayagents.run.domain.Scheduler;
import com.relayagents.run.domain.StepStatus;
import com.relayagents.shared.domain.Actor;
import com.relayagents.shared.error.ForbiddenException;
import com.relayagents.shared.error.NotFoundException;
import com.relayagents.shared.error.StateConflictException;
import com.relayagents.shared.json.Json;
import com.relayagents.shared.messaging.Topics;
import com.relayagents.workflow.application.WorkflowService;
import com.relayagents.workflow.domain.StepDefinition;
import com.relayagents.workflow.domain.WorkflowDefinition;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns every change to a run and its steps. Each method takes the run row lock first, applies one
 * transition, appends the matching events and outbox rows, and lets the scheduler release whatever became
 * ready, all in one transaction. The run and step tables are a cache of the event log kept in lock step.
 */
@Service
public class RunEngine {

    private static final Logger log = LoggerFactory.getLogger(RunEngine.class);

    private final RunRepository runs;
    private final OutboxWriter outbox;
    private final WorkflowService workflows;
    private final Json json;
    private final Clock clock;

    public RunEngine(RunRepository runs, OutboxWriter outbox, WorkflowService workflows, Json json, Clock clock) {
        this.runs = runs;
        this.outbox = outbox;
        this.workflows = workflows;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void create(NewRun run, WorkflowDefinition workflow, ObjectNode started) {
        runs.insertRun(run);
        for (StepDefinition step : workflow.steps()) {
            runs.insertStep(run.id(), step.id(), step.type(), step.retry().maxAttempts());
        }
        append(run.id(), RunEventType.RUN_STARTED, null, started.put("runStatus", RunStatus.RUNNING.name()));
        advance(runs.findById(run.id()).orElseThrow(), workflow);
    }

    @Transactional
    public Optional<StepFence> claim(String workerId, Duration lease) {
        Instant now = clock.instant();
        Optional<RunRepository.ClaimKey> key = runs.lockNextReady(now);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        UUID runId = key.get().runId();
        String stepId = key.get().stepId();
        int attempt = runs.startStep(runId, stepId, workerId, now.plus(lease));
        if (attempt == 0) {
            return Optional.empty();
        }
        append(runId, RunEventType.STEP_STARTED, stepId,
            status(StepStatus.RUNNING).put("attempt", attempt).put("worker", workerId));
        return Optional.of(new StepFence(runId, stepId, workerId, attempt));
    }

    public boolean heartbeat(StepFence fence, Duration lease) {
        return runs.extendLease(fence, clock.instant().plus(lease));
    }

    /**
     * Appends a journal entry (a model call, tool call, retrieval) for a step still owned by this worker.
     */
    @Transactional
    public void journal(StepFence fence, RunEventType type, ObjectNode payload) {
        lockFenced(fence);
        append(fence.runId(), type, fence.stepId(), payload);
    }

    /**
     * Settles a model call's reservation against what it really used and journals it. The money is settled
     * even if the worker lost the step, since the provider charged for the call either way.
     *
     * @return false if this worker no longer owns the step
     */
    @Transactional
    public boolean settleModelCall(StepFence fence, long reservedTokens, long reservedCost, long tokens, long cost,
                                   ObjectNode payload) {
        runs.lock(fence.runId()).orElseThrow();
        runs.settle(fence.runId(), reservedTokens, reservedCost, tokens, cost);
        boolean held = runs.step(fence.runId(), fence.stepId()).map(s -> s.heldBy(fence)).orElse(false);
        if (held) {
            append(fence.runId(), RunEventType.LLM_CALLED, fence.stepId(), payload);
        }
        return held;
    }

    /**
     * @return false if the outcome was dropped because the worker no longer owns the step
     */
    @Transactional
    public boolean complete(StepFence fence, StepOutcome outcome) {
        RunRecord run = runs.lock(fence.runId()).orElseThrow();
        StepRecord step = runs.step(fence.runId(), fence.stepId()).orElseThrow();
        if (!step.heldBy(fence)) {
            log.info("Dropping stale outcome for {}/{} attempt {} from {}", fence.runId(), fence.stepId(),
                fence.attempt(), fence.workerId());
            return false;
        }
        WorkflowDefinition workflow = workflow(run);
        switch (outcome) {
            case StepOutcome.Succeeded s -> {
                runs.setStepStatus(run.id(), step.stepId(), StepStatus.SUCCEEDED, null);
                ObjectNode payload = status(StepStatus.SUCCEEDED).put("attempt", step.attempt())
                    .put("untrusted", s.untrusted());
                payload.set("output", s.output());
                append(run.id(), RunEventType.STEP_SUCCEEDED, step.stepId(), payload);
                if (s.branchTargets() != null) {
                    ObjectNode taken = json.object();
                    taken.set("targets", json.tree(s.branchTargets()));
                    append(run.id(), RunEventType.BRANCH_TAKEN, step.stepId(), taken);
                }
                if (run.status().isActive()) {
                    advance(run, workflow);
                }
            }
            case StepOutcome.WaitingApproval w -> {
                runs.parkStep(run.id(), step.stepId(), step.attempt() - 1);
                append(run.id(), RunEventType.APPROVAL_REQUESTED, step.stepId(),
                    status(StepStatus.WAITING_APPROVAL).put("approvalId", w.approvalId().toString()));
                if (run.status().isActive()) {
                    syncWaiting(run, statuses(run.id()));
                }
            }
            case StepOutcome.Failed f -> fail(run, step, workflow, f.error(), f.retriable());
            case StepOutcome.BudgetExceeded b -> {
                String detail = b.kind() + ": " + b.detail();
                runs.setStepStatus(run.id(), step.stepId(), StepStatus.FAILED, "budget exceeded, " + detail);
                append(run.id(), RunEventType.STEP_FAILED, step.stepId(), status(StepStatus.FAILED)
                    .put("attempt", step.attempt()).put("error", "budget exceeded, " + detail));
                if (run.status().isActive()) {
                    exceedBudget(run, b.kind(), b.detail());
                }
            }
        }
        return true;
    }

    @Transactional
    public void cancel(Actor actor, UUID runId) {
        RunRecord run = lockOwned(actor.tenant(), runId);
        if (!run.status().isActive()) {
            throw new StateConflictException("run-not-active", "Run is " + run.status());
        }
        stop(run, RunStatus.CANCELLED, RunEventType.RUN_CANCELLED, "cancelled by " + actor.subject());
    }

    /**
     * Restarts failed steps from scratch with a fresh deadline. Steps that already succeeded keep their
     * output, and a re-run step finds its earlier model and tool calls in the journal.
     */
    @Transactional
    public void resume(Actor actor, UUID runId) {
        RunRecord run = lockOwned(actor.tenant(), runId);
        run.status().moveTo(RunStatus.RUNNING);
        Instant now = clock.instant();
        runs.setDeadline(run.id(), now.plus(Duration.between(run.createdAt(), run.deadline())));
        runs.setRunStatus(run.id(), RunStatus.RUNNING, null, null);
        append(run.id(), RunEventType.RUN_RESUMED, null,
            runStatus(RunStatus.RUNNING).put("by", actor.subject()));
        for (StepRecord step : runs.steps(run.id())) {
            if (step.status() == StepStatus.FAILED) {
                runs.scheduleStep(run.id(), step.stepId(), now, 0, null);
                append(run.id(), RunEventType.STEP_READY, step.stepId(), status(StepStatus.READY).put("attempt", 0));
            }
        }
        advance(runs.findById(run.id()).orElseThrow(), workflow(run));
    }

    @Transactional
    public void decide(Actor actor, UUID runId, String stepId, boolean approve, String comment) {
        RunRecord run = lockOwned(actor.tenant(), runId);
        if (!run.status().isActive()) {
            throw new StateConflictException("run-not-active", "Run is " + run.status());
        }
        ApprovalRecord approval = runs.pendingApproval(runId, stepId)
            .orElseThrow(() -> new StateConflictException("no-pending-approval", "Step '" + stepId
                + "' is not waiting for approval"));
        if (actor.subject().equals(run.createdBy())) {
            throw new ForbiddenException("self-approval", "The user who started a run cannot approve its steps");
        }
        settleApproval(run, approval, approve ? ApprovalRecord.Status.APPROVED : ApprovalRecord.Status.REJECTED,
            actor.subject(), comment);
    }

    @Transactional
    public void expireApproval(ApprovalRecord stale) {
        RunRecord run = runs.lock(stale.runId()).orElseThrow();
        runs.findApproval(stale.runId(), stale.stepId(), stale.iteration())
            .filter(a -> a.status() == ApprovalRecord.Status.PENDING)
            .ifPresent(a -> settleApproval(run, a, ApprovalRecord.Status.EXPIRED, "system", "not decided in time"));
    }

    @Transactional
    public void reapLease(RunRepository.ClaimKey key) {
        RunRecord run = runs.lock(key.runId()).orElseThrow();
        StepRecord step = runs.step(key.runId(), key.stepId()).orElseThrow();
        if (step.status() != StepStatus.RUNNING || step.leaseExpiresAt() == null
            || step.leaseExpiresAt().isAfter(clock.instant())) {
            return;
        }
        log.warn("Lease of {}/{} held by {} expired", key.runId(), key.stepId(), step.leaseOwner());
        append(run.id(), RunEventType.STEP_LEASE_EXPIRED, step.stepId(),
            json.object().put("worker", step.leaseOwner()).put("attempt", step.attempt()));
        fail(run, step, workflow(run), "lease expired, worker presumed dead", true);
    }

    @Transactional
    public void expireDeadline(UUID runId) {
        RunRecord run = runs.lock(runId).orElseThrow();
        if (run.status().isActive() && !run.deadline().isAfter(clock.instant())) {
            exceedBudget(run, BudgetKind.WALL_CLOCK, "deadline " + run.deadline() + " passed");
        }
    }

    public Map<String, StepOutput> stepOutputs(UUID runId) {
        Map<String, StepOutput> outputs = new HashMap<>();
        for (RunEvent event : runs.eventsOfTypes(runId, List.of(RunEventType.STEP_SUCCEEDED))) {
            outputs.put(event.stepId(), new StepOutput(event.payload().path("output"),
                event.payload().path("untrusted").asBoolean()));
        }
        return outputs;
    }

    private void advance(RunRecord run, WorkflowDefinition workflow) {
        Map<String, StepStatus> statuses = statuses(run.id());
        Map<String, List<String>> choices = new HashMap<>();
        for (RunEvent event : runs.eventsOfTypes(run.id(), List.of(RunEventType.BRANCH_TAKEN))) {
            List<String> targets = new ArrayList<>();
            event.payload().path("targets").forEach(t -> targets.add(t.asText()));
            choices.put(event.stepId(), targets);
        }
        Map<String, StepStatus> changes = Scheduler.advance(workflow.dag(), statuses, choices);
        Instant now = clock.instant();
        changes.forEach((stepId, next) -> {
            if (next == StepStatus.READY) {
                runs.scheduleStep(run.id(), stepId, now, 0, null);
                append(run.id(), RunEventType.STEP_READY, stepId, status(next).put("attempt", 0));
            } else {
                runs.setStepStatus(run.id(), stepId, next, null);
                append(run.id(), RunEventType.STEP_SKIPPED, stepId, status(next));
            }
            statuses.put(stepId, next);
        });
        if (Scheduler.allSettled(statuses)) {
            succeed(run, workflow);
        } else {
            syncWaiting(run, statuses);
        }
    }

    private void succeed(RunRecord run, WorkflowDefinition workflow) {
        Map<String, StepOutput> outputs = stepOutputs(run.id());
        JsonNode output;
        if (workflow.output() != null) {
            try {
                output = new TemplateRenderer(run.input(), outputs).value(workflow.output());
            } catch (TemplateRenderer.MissingValueException e) {
                runs.setRunStatus(run.id(), RunStatus.FAILED, null, "output template: " + e.getMessage());
                append(run.id(), RunEventType.RUN_FAILED, null,
                    runStatus(RunStatus.FAILED).put("failure", "output template: " + e.getMessage()));
                return;
            }
        } else {
            ObjectNode leaves = json.object();
            for (String leaf : workflow.dag().leaves()) {
                if (outputs.containsKey(leaf)) {
                    leaves.set(leaf, outputs.get(leaf).value());
                }
            }
            output = leaves;
        }
        runs.setRunStatus(run.id(), RunStatus.SUCCEEDED, output, null);
        ObjectNode payload = runStatus(RunStatus.SUCCEEDED);
        payload.set("output", output);
        append(run.id(), RunEventType.RUN_SUCCEEDED, null, payload);
    }

    private void fail(RunRecord run, StepRecord step, WorkflowDefinition workflow, String error, boolean retriable) {
        if (retriable && step.attempt() < step.maxAttempts()) {
            Duration delay = Backoff.delay(workflow.dag().step(step.stepId()).retry(), step.attempt(),
                ThreadLocalRandom.current().nextDouble());
            runs.scheduleStep(run.id(), step.stepId(), clock.instant().plus(delay), step.attempt(), error);
            append(run.id(), RunEventType.STEP_RETRY_SCHEDULED, step.stepId(), status(StepStatus.READY)
                .put("attempt", step.attempt()).put("error", error).put("delayMs", delay.toMillis()));
            return;
        }
        runs.setStepStatus(run.id(), step.stepId(), StepStatus.FAILED, error);
        append(run.id(), RunEventType.STEP_FAILED, step.stepId(), status(StepStatus.FAILED)
            .put("attempt", step.attempt()).put("error", error));
        outbox.append(Topics.STEP_DEAD_LETTER, run.id().toString(), "STEP_DEAD_LETTERED", json.object()
            .put("runId", run.id().toString()).put("tenant", run.tenant()).put("stepId", step.stepId())
            .put("attempt", step.attempt()).put("error", error));
        if (run.status().isActive()) {
            String failure = "step '" + step.stepId() + "' failed: " + error;
            runs.setRunStatus(run.id(), RunStatus.FAILED, null, failure);
            append(run.id(), RunEventType.RUN_FAILED, null, runStatus(RunStatus.FAILED).put("failure", failure));
        }
    }

    private void exceedBudget(RunRecord run, BudgetKind kind, String detail) {
        stop(run, RunStatus.BUDGET_EXCEEDED, RunEventType.RUN_BUDGET_EXCEEDED, kind + ": " + detail);
    }

    private void stop(RunRecord run, RunStatus status, RunEventType type, String reason) {
        run.status().moveTo(status);
        for (StepRecord step : runs.steps(run.id())) {
            if (!step.status().isTerminal()) {
                runs.setStepStatus(run.id(), step.stepId(), StepStatus.CANCELLED, null);
                append(run.id(), RunEventType.STEP_CANCELLED, step.stepId(), status(StepStatus.CANCELLED));
            }
        }
        runs.setRunStatus(run.id(), status, null, reason);
        append(run.id(), type, null, runStatus(status).put("failure", reason));
    }

    private void settleApproval(RunRecord run, ApprovalRecord approval, ApprovalRecord.Status decision, String by,
                                String comment) {
        runs.decideApproval(approval.id(), decision, by, comment);
        append(run.id(), RunEventType.APPROVAL_DECIDED, approval.stepId(), json.object()
            .put("key", JournalKeys.approval(approval.iteration()))
            .put("approvalId", approval.id().toString())
            .put("decision", decision.name())
            .put("decidedBy", by)
            .put("comment", comment));
        StepRecord step = runs.step(run.id(), approval.stepId()).orElseThrow();
        if (step.status() == StepStatus.WAITING_APPROVAL && run.status().isActive()) {
            runs.scheduleStep(run.id(), step.stepId(), clock.instant(), step.attempt(), null);
            append(run.id(), RunEventType.STEP_READY, step.stepId(),
                status(StepStatus.READY).put("attempt", step.attempt()));
            syncWaiting(run, statuses(run.id()));
        }
    }

    /**
     * A run is WAITING_APPROVAL while any of its steps is; other branches keep running meanwhile.
     */
    private void syncWaiting(RunRecord run, Map<String, StepStatus> statuses) {
        if (!run.status().isActive()) {
            return;
        }
        boolean waiting = statuses.containsValue(StepStatus.WAITING_APPROVAL);
        RunStatus target = waiting ? RunStatus.WAITING_APPROVAL : RunStatus.RUNNING;
        if (run.status() == target) {
            return;
        }
        runs.setRunStatus(run.id(), run.status().moveTo(target), null, null);
        append(run.id(), waiting ? RunEventType.RUN_WAITING_APPROVAL : RunEventType.RUN_RESUMED, null,
            runStatus(target));
    }

    private Map<String, StepStatus> statuses(UUID runId) {
        Map<String, StepStatus> statuses = new LinkedHashMap<>();
        runs.steps(runId).forEach(s -> statuses.put(s.stepId(), s.status()));
        return statuses;
    }

    private RunRecord lockFenced(StepFence fence) {
        RunRecord run = runs.lock(fence.runId()).orElseThrow();
        boolean held = runs.step(fence.runId(), fence.stepId()).map(s -> s.heldBy(fence)).orElse(false);
        if (!held) {
            throw new FencedOutException(fence);
        }
        return run;
    }

    private RunRecord lockOwned(String tenant, UUID runId) {
        RunRecord run = runs.lock(runId).filter(r -> r.tenant().equals(tenant))
            .orElseThrow(() -> new NotFoundException("run-not-found", "No run " + runId));
        return run;
    }

    private WorkflowDefinition workflow(RunRecord run) {
        return workflows.get(run.tenant(), run.workflowId(), run.workflowVersion()).definition();
    }

    private void append(UUID runId, RunEventType type, String stepId, ObjectNode payload) {
        long seq = runs.nextSeq(runId);
        runs.insertEvent(runId, seq, type, stepId, payload);
        ObjectNode wire = json.object().put("runId", runId.toString()).put("seq", seq).put("type", type.name())
            .put("stepId", stepId);
        wire.set("payload", payload);
        outbox.append(Topics.RUN_EVENTS, runId.toString(), type.name(), wire);
    }

    private ObjectNode status(StepStatus status) {
        return json.object().put("status", status.name());
    }

    private ObjectNode runStatus(RunStatus status) {
        return json.object().put("runStatus", status.name());
    }
}
