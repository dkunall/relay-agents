package com.relayagents.run.adapter.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.relayagents.run.application.ApprovalRecord;
import com.relayagents.run.application.NewRun;
import com.relayagents.run.application.RunEvent;
import com.relayagents.run.application.RunRecord;
import com.relayagents.run.application.RunRepository;
import com.relayagents.run.application.StepFence;
import com.relayagents.run.application.StepRecord;
import com.relayagents.run.domain.RunEventType;
import com.relayagents.run.domain.RunMode;
import com.relayagents.run.domain.RunStatus;
import com.relayagents.run.domain.StepStatus;
import com.relayagents.shared.json.Json;
import com.relayagents.workflow.domain.StepType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRunRepository implements RunRepository {

    private static final String ACTIVE = "('RUNNING', 'WAITING_APPROVAL')";

    private final JdbcClient jdbc;
    private final Json json;

    JdbcRunRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void insertRun(NewRun run) {
        jdbc.sql("""
                INSERT INTO run (id, tenant_id, workflow_id, workflow_version, status, mode, source_run_id, input,
                                 max_tokens, max_cost_micros, max_tool_calls, deadline, created_by,
                                 idempotency_key, request_hash)
                VALUES (?, ?, ?, ?, 'RUNNING', ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?)
                """)
            .params(run.id(), run.tenant(), run.workflowId(), run.workflowVersion(), run.mode().name(),
                run.sourceRunId(), json.write(run.input()), run.limits().maxTokens(), run.limits().maxCostMicros(),
                run.limits().maxToolCalls(), Timestamp.from(run.deadline()), run.createdBy(), run.idempotencyKey(),
                run.requestHash())
            .update();
    }

    @Override
    public void insertStep(UUID runId, String stepId, StepType type, int maxAttempts) {
        jdbc.sql("INSERT INTO step (run_id, step_id, type, status, max_attempts) VALUES (?, ?, ?, 'PENDING', ?)")
            .params(runId, stepId, type.name(), maxAttempts).update();
    }

    @Override
    public Optional<RunRecord> find(String tenant, UUID id) {
        return jdbc.sql("SELECT * FROM run WHERE tenant_id = ? AND id = ?").params(tenant, id)
            .query(this::run).optional();
    }

    @Override
    public Optional<RunRecord> findById(UUID id) {
        return jdbc.sql("SELECT * FROM run WHERE id = ?").param(id).query(this::run).optional();
    }

    @Override
    public Optional<RunRecord> findByIdempotencyKey(String tenant, String idempotencyKey) {
        return jdbc.sql("SELECT * FROM run WHERE tenant_id = ? AND idempotency_key = ?")
            .params(tenant, idempotencyKey).query(this::run).optional();
    }

    @Override
    public Optional<RunRecord> lock(UUID id) {
        return jdbc.sql("SELECT * FROM run WHERE id = ? FOR UPDATE").param(id).query(this::run).optional();
    }

    @Override
    public List<RunRecord> list(String tenant, RunStatus status, int limit) {
        return jdbc.sql("""
                SELECT * FROM run WHERE tenant_id = ? AND (CAST(? AS text) IS NULL OR status = ?)
                ORDER BY created_at DESC LIMIT ?
                """)
            .params(tenant, status == null ? null : status.name(), status == null ? null : status.name(), limit)
            .query(this::run).list();
    }

    @Override
    public void setRunStatus(UUID id, RunStatus status, JsonNode output, String failure) {
        jdbc.sql("""
                UPDATE run SET status = ?, output = COALESCE(CAST(? AS jsonb), output), failure = ?, updated_at = now()
                WHERE id = ?
                """)
            .params(status.name(), output == null ? null : json.write(output), failure, id).update();
    }

    @Override
    public void setDeadline(UUID id, Instant deadline) {
        jdbc.sql("UPDATE run SET deadline = ?, updated_at = now() WHERE id = ?")
            .params(Timestamp.from(deadline), id).update();
    }

    @Override
    public long nextSeq(UUID runId) {
        return jdbc.sql("UPDATE run SET last_seq = last_seq + 1 WHERE id = ? RETURNING last_seq")
            .param(runId).query(Long.class).single();
    }

    @Override
    public void insertEvent(UUID runId, long seq, RunEventType type, String stepId, JsonNode payload) {
        jdbc.sql("INSERT INTO run_event (run_id, seq, type, step_id, payload) VALUES (?, ?, ?, ?, CAST(? AS jsonb))")
            .params(runId, seq, type.name(), stepId, json.write(payload)).update();
    }

    @Override
    public List<RunEvent> events(UUID runId, long afterSeq, int limit) {
        return jdbc.sql("SELECT * FROM run_event WHERE run_id = ? AND seq > ? ORDER BY seq LIMIT ?")
            .params(runId, afterSeq, limit).query(this::event).list();
    }

    @Override
    public List<RunEvent> eventsOfTypes(UUID runId, Collection<RunEventType> types) {
        return jdbc.sql("SELECT * FROM run_event WHERE run_id = ? AND type = ANY(?) ORDER BY seq")
            .params(runId, names(types)).query(this::event).list();
    }

    @Override
    public List<RunEvent> stepEvents(UUID runId, String stepId, Collection<RunEventType> types) {
        return jdbc.sql("SELECT * FROM run_event WHERE run_id = ? AND step_id = ? AND type = ANY(?) ORDER BY seq")
            .params(runId, stepId, names(types)).query(this::event).list();
    }

    @Override
    public List<StepRecord> steps(UUID runId) {
        return jdbc.sql("SELECT * FROM step WHERE run_id = ? ORDER BY step_id").param(runId)
            .query(this::step).list();
    }

    @Override
    public Optional<StepRecord> step(UUID runId, String stepId) {
        return jdbc.sql("SELECT * FROM step WHERE run_id = ? AND step_id = ?").params(runId, stepId)
            .query(this::step).optional();
    }

    @Override
    public void setStepStatus(UUID runId, String stepId, StepStatus status, String error) {
        jdbc.sql("""
                UPDATE step SET status = ?, last_error = COALESCE(?, last_error), lease_owner = NULL,
                                lease_expires_at = NULL, updated_at = now()
                WHERE run_id = ? AND step_id = ?
                """)
            .params(status.name(), error, runId, stepId).update();
    }

    @Override
    public void scheduleStep(UUID runId, String stepId, Instant availableAt, int attempt, String error) {
        jdbc.sql("""
                UPDATE step SET status = 'READY', available_at = ?, attempt = ?, last_error = COALESCE(?, last_error),
                                lease_owner = NULL, lease_expires_at = NULL, updated_at = now()
                WHERE run_id = ? AND step_id = ?
                """)
            .params(Timestamp.from(availableAt), attempt, error, runId, stepId).update();
    }

    @Override
    public void parkStep(UUID runId, String stepId, int attempt) {
        jdbc.sql("""
                UPDATE step SET status = 'WAITING_APPROVAL', attempt = ?, lease_owner = NULL, lease_expires_at = NULL,
                                updated_at = now()
                WHERE run_id = ? AND step_id = ?
                """)
            .params(attempt, runId, stepId).update();
    }

    @Override
    public Optional<ClaimKey> lockNextReady(Instant now) {
        // Locks the run row, not the step: every transition takes the run lock first, so a claim never
        // races a completion of a sibling step of the same run.
        return jdbc.sql("""
                SELECT s.run_id, s.step_id FROM step s JOIN run r ON r.id = s.run_id
                WHERE s.status = 'READY' AND s.available_at <= ? AND r.status IN %s
                ORDER BY s.available_at LIMIT 1
                FOR UPDATE OF r SKIP LOCKED
                """.formatted(ACTIVE))
            .param(Timestamp.from(now))
            .query((rs, n) -> new ClaimKey(rs.getObject("run_id", UUID.class), rs.getString("step_id")))
            .optional();
    }

    @Override
    public int startStep(UUID runId, String stepId, String workerId, Instant leaseUntil) {
        return jdbc.sql("""
                UPDATE step SET status = 'RUNNING', attempt = attempt + 1, lease_owner = ?, lease_expires_at = ?,
                                updated_at = now()
                WHERE run_id = ? AND step_id = ? AND status = 'READY'
                RETURNING attempt
                """)
            .params(workerId, Timestamp.from(leaseUntil), runId, stepId).query(Integer.class).optional().orElse(0);
    }

    @Override
    public boolean extendLease(StepFence fence, Instant leaseUntil) {
        return jdbc.sql("""
                UPDATE step SET lease_expires_at = ?
                WHERE run_id = ? AND step_id = ? AND status = 'RUNNING' AND lease_owner = ? AND attempt = ?
                """)
            .params(Timestamp.from(leaseUntil), fence.runId(), fence.stepId(), fence.workerId(), fence.attempt())
            .update() == 1;
    }

    @Override
    public List<ClaimKey> expiredLeases(Instant now, int limit) {
        return jdbc.sql("""
                SELECT run_id, step_id FROM step WHERE status = 'RUNNING' AND lease_expires_at < ?
                ORDER BY lease_expires_at LIMIT ?
                """)
            .params(Timestamp.from(now), limit)
            .query((rs, n) -> new ClaimKey(rs.getObject("run_id", UUID.class), rs.getString("step_id"))).list();
    }

    @Override
    public List<UUID> overdueRuns(Instant now, int limit) {
        return jdbc.sql("SELECT id FROM run WHERE status IN %s AND deadline <= ? ORDER BY deadline LIMIT ?"
                .formatted(ACTIVE))
            .params(Timestamp.from(now), limit).query(UUID.class).list();
    }

    @Override
    public boolean reserve(UUID runId, long tokens, long costMicros) {
        return jdbc.sql("""
                UPDATE run SET reserved_tokens = reserved_tokens + :tokens,
                               reserved_cost_micros = reserved_cost_micros + :cost
                WHERE id = :id
                  AND spent_tokens + reserved_tokens + :tokens <= max_tokens
                  AND spent_cost_micros + reserved_cost_micros + :cost <= max_cost_micros
                """)
            .param("id", runId).param("tokens", tokens).param("cost", costMicros).update() == 1;
    }

    @Override
    public void settle(UUID runId, long reservedTokens, long reservedCostMicros, long tokens, long costMicros) {
        jdbc.sql("""
                UPDATE run SET reserved_tokens = GREATEST(reserved_tokens - :rt, 0),
                               reserved_cost_micros = GREATEST(reserved_cost_micros - :rc, 0),
                               spent_tokens = spent_tokens + :t,
                               spent_cost_micros = spent_cost_micros + :c,
                               updated_at = now()
                WHERE id = :id
                """)
            .param("id", runId).param("rt", reservedTokens).param("rc", reservedCostMicros)
            .param("t", tokens).param("c", costMicros).update();
    }

    @Override
    public boolean consumeToolCall(UUID runId) {
        return jdbc.sql("UPDATE run SET tool_calls = tool_calls + 1 WHERE id = ? AND tool_calls < max_tool_calls")
            .param(runId).update() == 1;
    }

    @Override
    public boolean insertApproval(ApprovalRecord a) {
        return jdbc.sql("""
                INSERT INTO approval (id, run_id, step_id, iteration, tenant_id, kind, tool, arguments, reason, status,
                                      requested_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, 'PENDING', ?)
                ON CONFLICT (run_id, step_id, iteration) DO NOTHING
                """)
            .params(a.id(), a.runId(), a.stepId(), a.iteration(), a.tenant(), a.kind().name(), a.tool(),
                a.arguments() == null ? null : json.write(a.arguments()), a.reason(), a.requestedBy())
            .update() == 1;
    }

    @Override
    public Optional<ApprovalRecord> findApproval(UUID runId, String stepId, int iteration) {
        return jdbc.sql("SELECT * FROM approval WHERE run_id = ? AND step_id = ? AND iteration = ?")
            .params(runId, stepId, iteration).query(this::approval).optional();
    }

    @Override
    public Optional<ApprovalRecord> pendingApproval(UUID runId, String stepId) {
        return jdbc.sql("""
                SELECT * FROM approval WHERE run_id = ? AND step_id = ? AND status = 'PENDING'
                ORDER BY iteration DESC LIMIT 1
                """)
            .params(runId, stepId).query(this::approval).optional();
    }

    @Override
    public void decideApproval(UUID approvalId, ApprovalRecord.Status status, String decidedBy, String comment) {
        jdbc.sql("""
                UPDATE approval SET status = ?, decided_by = ?, decided_at = now(), comment = ?
                WHERE id = ? AND status = 'PENDING'
                """)
            .params(status.name(), decidedBy, comment, approvalId).update();
    }

    @Override
    public List<ApprovalRecord> pendingApprovals(String tenant, int limit) {
        return jdbc.sql("""
                SELECT a.* FROM approval a JOIN run r ON r.id = a.run_id
                WHERE a.tenant_id = ? AND a.status = 'PENDING' AND r.status IN %s
                ORDER BY a.created_at LIMIT ?
                """.formatted(ACTIVE))
            .params(tenant, limit).query(this::approval).list();
    }

    @Override
    public List<ApprovalRecord> pendingApprovalsCreatedBefore(Instant cutoff, int limit) {
        return jdbc.sql("SELECT * FROM approval WHERE status = 'PENDING' AND created_at < ? ORDER BY created_at LIMIT ?")
            .params(Timestamp.from(cutoff), limit).query(this::approval).list();
    }

    @Override
    public Optional<JsonNode> findToolResult(String idempotencyKey) {
        return jdbc.sql("SELECT result FROM tool_invocation WHERE idempotency_key = ?").param(idempotencyKey)
            .query((rs, n) -> json.read(rs.getString("result"))).optional();
    }

    @Override
    public void insertToolInvocation(String idempotencyKey, UUID runId, String stepId, int iteration, String tool,
                                     JsonNode arguments, JsonNode result) {
        jdbc.sql("""
                INSERT INTO tool_invocation (idempotency_key, run_id, step_id, iteration, tool, arguments, result)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))
                ON CONFLICT (idempotency_key) DO NOTHING
                """)
            .params(idempotencyKey, runId, stepId, iteration, tool, json.write(arguments), json.write(result))
            .update();
    }

    @Override
    public void lockTenant(String tenant) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?))").param("tenant:" + tenant).query().singleRow();
    }

    @Override
    public int activeRuns(String tenant) {
        return jdbc.sql("SELECT count(*) FROM run WHERE tenant_id = ? AND status IN " + ACTIVE)
            .param(tenant).query(Integer.class).single();
    }

    @Override
    public long costSince(String tenant, Instant since) {
        return jdbc.sql("""
                SELECT COALESCE(sum(spent_cost_micros + reserved_cost_micros), 0) FROM run
                WHERE tenant_id = ? AND created_at >= ?
                """)
            .params(tenant, Timestamp.from(since)).query(Long.class).single();
    }

    @Override
    public Optional<Quota> quota(String tenant) {
        return jdbc.sql("SELECT * FROM tenant_quota WHERE tenant_id = ?").param(tenant)
            .query((rs, n) -> new Quota(rs.getInt("max_concurrent_runs"), rs.getLong("daily_cost_cap_micros")))
            .optional();
    }

    @Override
    public List<CostRow> costsByWorkflow(String tenant, Instant since) {
        return jdbc.sql("""
                SELECT r.workflow_id, max(w.name) AS name, count(*) AS runs, sum(r.spent_tokens) AS tokens,
                       sum(r.spent_cost_micros) AS cost, sum(r.tool_calls) AS tool_calls
                FROM run r JOIN workflow w ON w.id = r.workflow_id AND w.version = r.workflow_version
                WHERE r.tenant_id = ? AND r.created_at >= ?
                GROUP BY r.workflow_id ORDER BY cost DESC, r.workflow_id
                """)
            .params(tenant, Timestamp.from(since))
            .query((rs, n) -> new CostRow(rs.getObject("workflow_id", UUID.class), rs.getString("name"),
                rs.getLong("runs"), rs.getLong("tokens"), rs.getLong("cost"), rs.getLong("tool_calls")))
            .list();
    }

    private static String[] names(Collection<RunEventType> types) {
        return types.stream().map(Enum::name).toArray(String[]::new);
    }

    private RunRecord run(ResultSet rs, int n) throws SQLException {
        String output = rs.getString("output");
        return new RunRecord(rs.getObject("id", UUID.class), rs.getString("tenant_id"),
            rs.getObject("workflow_id", UUID.class), rs.getInt("workflow_version"),
            RunStatus.valueOf(rs.getString("status")), RunMode.valueOf(rs.getString("mode")),
            rs.getObject("source_run_id", UUID.class), json.read(rs.getString("input")),
            output == null ? null : json.read(output), rs.getString("failure"), rs.getLong("max_tokens"),
            rs.getLong("max_cost_micros"), rs.getInt("max_tool_calls"), instant(rs, "deadline"),
            rs.getLong("spent_tokens"), rs.getLong("spent_cost_micros"), rs.getInt("tool_calls"),
            rs.getLong("reserved_tokens"), rs.getLong("reserved_cost_micros"), rs.getString("created_by"),
            instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private StepRecord step(ResultSet rs, int n) throws SQLException {
        return new StepRecord(rs.getObject("run_id", UUID.class), rs.getString("step_id"),
            StepType.valueOf(rs.getString("type")), StepStatus.valueOf(rs.getString("status")), rs.getInt("attempt"),
            rs.getInt("max_attempts"), instant(rs, "available_at"), rs.getString("lease_owner"),
            instant(rs, "lease_expires_at"), rs.getString("last_error"));
    }

    private RunEvent event(ResultSet rs, int n) throws SQLException {
        return new RunEvent(rs.getObject("run_id", UUID.class), rs.getLong("seq"),
            RunEventType.valueOf(rs.getString("type")), rs.getString("step_id"), json.read(rs.getString("payload")),
            instant(rs, "created_at"));
    }

    private ApprovalRecord approval(ResultSet rs, int n) throws SQLException {
        String arguments = rs.getString("arguments");
        return new ApprovalRecord(rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class),
            rs.getString("step_id"), rs.getInt("iteration"), rs.getString("tenant_id"),
            ApprovalRecord.Kind.valueOf(rs.getString("kind")), rs.getString("tool"),
            arguments == null ? null : json.read(arguments), rs.getString("reason"),
            ApprovalRecord.Status.valueOf(rs.getString("status")), rs.getString("requested_by"),
            rs.getString("decided_by"), instant(rs, "decided_at"), rs.getString("comment"),
            instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
