package com.relayagents.run.adapter.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.relayagents.run.application.ApprovalRecord;
import com.relayagents.run.application.RunDiff;
import com.relayagents.run.application.RunRecord;
import com.relayagents.run.application.RunRepository;
import com.relayagents.run.application.RunService;
import com.relayagents.run.application.StepRecord;
import com.relayagents.run.domain.BudgetLimits;
import com.relayagents.run.domain.RunMode;
import com.relayagents.run.domain.RunStatus;
import com.relayagents.shared.domain.Actor;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RunController {

    private final RunService runs;
    private final RunDiff diff;

    RunController(RunService runs, RunDiff diff) {
        this.runs = runs;
        this.diff = diff;
    }

    record BudgetBody(Long maxTokens, Long maxCostMicros, Integer maxToolCalls, Long maxDurationSeconds) {

        BudgetLimits toLimits() {
            BudgetLimits d = RunService.DEFAULT_BUDGET;
            return new BudgetLimits(maxTokens == null ? d.maxTokens() : maxTokens,
                maxCostMicros == null ? d.maxCostMicros() : maxCostMicros,
                maxToolCalls == null ? d.maxToolCalls() : maxToolCalls,
                maxDurationSeconds == null ? d.maxDuration() : Duration.ofSeconds(maxDurationSeconds));
        }
    }

    record StartBody(JsonNode input, BudgetBody budget, Integer version) {
    }

    record ReplayBody(RunMode mode) {
    }

    record DecisionBody(boolean approve, String comment) {
    }

    @PostMapping("/v1/workflows/{workflowId}/runs")
    ResponseEntity<RunView> start(Actor actor, @PathVariable UUID workflowId,
                                  @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                  @RequestBody(required = false) StartBody body) {
        StartBody request = body == null ? new StartBody(null, null, null) : body;
        RunService.Started started = runs.start(actor, workflowId, request.version(), request.input(),
            request.budget() == null ? null : request.budget().toLimits(), idempotencyKey);
        RunView view = RunView.of(started.run(), runs.steps(actor, started.run().id()));
        if (started.existing()) {
            return ResponseEntity.ok(view);
        }
        return ResponseEntity.created(URI.create("/v1/runs/" + started.run().id())).body(view);
    }

    @GetMapping("/v1/runs")
    List<RunView> list(Actor actor, @RequestParam(required = false) RunStatus status,
                       @RequestParam(defaultValue = "50") int limit) {
        return runs.list(actor, status, limit).stream().map(r -> RunView.of(r, List.of())).toList();
    }

    @GetMapping("/v1/runs/{id}")
    RunView get(Actor actor, @PathVariable UUID id) {
        return RunView.of(runs.get(actor, id), runs.steps(actor, id));
    }

    @PostMapping("/v1/runs/{id}/cancel")
    RunView cancel(Actor actor, @PathVariable UUID id) {
        runs.cancel(actor, id);
        return get(actor, id);
    }

    @PostMapping("/v1/runs/{id}/resume")
    RunView resume(Actor actor, @PathVariable UUID id) {
        runs.resume(actor, id);
        return get(actor, id);
    }

    @PostMapping("/v1/runs/{id}/replay")
    @ResponseStatus(HttpStatus.CREATED)
    RunView replay(Actor actor, @PathVariable UUID id, @RequestBody(required = false) ReplayBody body) {
        RunMode mode = body == null || body.mode() == null ? RunMode.REPLAY_RECORDED : body.mode();
        RunRecord replay = runs.replay(actor, id, mode);
        return RunView.of(replay, runs.steps(actor, replay.id()));
    }

    @GetMapping("/v1/runs/{id}/diff")
    RunDiff.Diff diff(Actor actor, @PathVariable UUID id) {
        return diff.diff(actor, id);
    }

    @PostMapping("/v1/runs/{id}/approvals/{stepId}")
    RunView decide(Actor actor, @PathVariable UUID id, @PathVariable String stepId, @RequestBody DecisionBody body) {
        runs.decide(actor, id, stepId, body.approve(), body.comment());
        return get(actor, id);
    }

    @GetMapping("/v1/approvals")
    List<ApprovalView> approvals(Actor actor) {
        return runs.pendingApprovals(actor).stream().map(ApprovalView::of).toList();
    }

    @GetMapping("/v1/costs")
    List<RunRepository.CostRow> costs(Actor actor, @RequestParam(defaultValue = "30") int days) {
        return runs.costs(actor, days);
    }

    record StepView(String id, String type, String status, int attempt, int maxAttempts, String lastError) {

        static StepView of(StepRecord s) {
            return new StepView(s.stepId(), s.type().name(), s.status().name(), s.attempt(), s.maxAttempts(),
                s.lastError());
        }
    }

    record Usage(long tokens, long costMicros, int toolCalls, long maxTokens, long maxCostMicros, int maxToolCalls) {
    }

    record RunView(UUID id, UUID workflowId, int workflowVersion, String status, String mode, UUID sourceRunId,
                   JsonNode input, JsonNode output, String failure, Usage usage, Instant deadline, String createdBy,
                   Instant createdAt, Instant updatedAt, List<StepView> steps) {

        static RunView of(RunRecord r, List<StepRecord> steps) {
            return new RunView(r.id(), r.workflowId(), r.workflowVersion(), r.status().name(), r.mode().name(),
                r.sourceRunId(), r.input(), r.output(), r.failure(),
                new Usage(r.spentTokens(), r.spentCostMicros(), r.toolCalls(), r.maxTokens(), r.maxCostMicros(),
                    r.maxToolCalls()),
                r.deadline(), r.createdBy(), r.createdAt(), r.updatedAt(), steps.stream().map(StepView::of).toList());
        }
    }

    record ApprovalView(UUID id, UUID runId, String stepId, String kind, String tool, JsonNode arguments,
                        String reason, String requestedBy, Instant createdAt) {

        static ApprovalView of(ApprovalRecord a) {
            return new ApprovalView(a.id(), a.runId(), a.stepId(), a.kind().name(), a.tool(), a.arguments(),
                a.reason(), a.requestedBy(), a.createdAt());
        }
    }
}
