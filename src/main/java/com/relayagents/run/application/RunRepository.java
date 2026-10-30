package com.relayagents.run.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.relayagents.run.domain.RunEventType;
import com.relayagents.run.domain.RunStatus;
import com.relayagents.run.domain.StepStatus;
import com.relayagents.workflow.domain.StepType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for runs. Methods that change a run or its steps expect the caller to hold the run row lock
 * ({@link #lock}), always taken before any step row, so two transactions never lock in opposite orders.
 */
public interface RunRepository {

    void insertRun(NewRun run);

    void insertStep(UUID runId, String stepId, StepType type, int maxAttempts);

    Optional<RunRecord> find(String tenant, UUID id);

    Optional<RunRecord> findById(UUID id);

    Optional<RunRecord> findByIdempotencyKey(String tenant, String idempotencyKey);

    Optional<RunRecord> lock(UUID id);

    List<RunRecord> list(String tenant, RunStatus status, int limit);

    void setRunStatus(UUID id, RunStatus status, JsonNode output, String failure);

    void setDeadline(UUID id, Instant deadline);

    long nextSeq(UUID runId);

    void insertEvent(UUID runId, long seq, RunEventType type, String stepId, JsonNode payload);

    List<RunEvent> events(UUID runId, long afterSeq, int limit);

    List<RunEvent> eventsOfTypes(UUID runId, Collection<RunEventType> types);

    List<RunEvent> stepEvents(UUID runId, String stepId, Collection<RunEventType> types);

    List<StepRecord> steps(UUID runId);

    Optional<StepRecord> step(UUID runId, String stepId);

    void setStepStatus(UUID runId, String stepId, StepStatus status, String error);

    /**
     * Back to READY, clearing the lease.
     */
    void scheduleStep(UUID runId, String stepId, Instant availableAt, int attempt, String error);

    /**
     * WAITING_APPROVAL, clearing the lease. The attempt is handed back: waiting is not a failure.
     */
    void parkStep(UUID runId, String stepId, int attempt);

    record ClaimKey(UUID runId, String stepId) {
    }

    /**
     * Locks the run of the oldest ready step whose run is not already locked by someone else.
     */
    Optional<ClaimKey> lockNextReady(Instant now);

    int startStep(UUID runId, String stepId, String workerId, Instant leaseUntil);

    boolean extendLease(StepFence fence, Instant leaseUntil);

    List<ClaimKey> expiredLeases(Instant now, int limit);

    List<UUID> overdueRuns(Instant now, int limit);

    // Budget counters. Reservations make concurrent steps of one run see each other's in-flight calls.

    boolean reserve(UUID runId, long tokens, long costMicros);

    void settle(UUID runId, long reservedTokens, long reservedCostMicros, long tokens, long costMicros);

    boolean consumeToolCall(UUID runId);

    // Approvals

    boolean insertApproval(ApprovalRecord approval);

    Optional<ApprovalRecord> findApproval(UUID runId, String stepId, int iteration);

    Optional<ApprovalRecord> pendingApproval(UUID runId, String stepId);

    void decideApproval(UUID approvalId, ApprovalRecord.Status status, String decidedBy, String comment);

    List<ApprovalRecord> pendingApprovals(String tenant, int limit);

    List<ApprovalRecord> pendingApprovalsCreatedBefore(Instant cutoff, int limit);

    // Tool invocations that actually ran, keyed by idempotency key

    Optional<JsonNode> findToolResult(String idempotencyKey);

    void insertToolInvocation(String idempotencyKey, UUID runId, String stepId, int iteration, String tool,
                              JsonNode arguments, JsonNode result);

    // Tenant quotas

    void lockTenant(String tenant);

    int activeRuns(String tenant);

    long costSince(String tenant, Instant since);

    record Quota(int maxConcurrentRuns, long dailyCostCapMicros) {
    }

    Optional<Quota> quota(String tenant);

    record CostRow(UUID workflowId, String workflowName, long runs, long tokens, long costMicros, long toolCalls) {
    }

    List<CostRow> costsByWorkflow(String tenant, Instant since);
}
