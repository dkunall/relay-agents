package com.relayagents.trigger.adapter.persistence;

import com.relayagents.run.domain.BudgetLimits;
import com.relayagents.shared.json.Json;
import com.relayagents.trigger.application.Trigger;
import com.relayagents.trigger.application.TriggerRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcTriggerRepository implements TriggerRepository {

    private final JdbcClient jdbc;
    private final Json json;

    JdbcTriggerRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void insert(Trigger t) {
        jdbc.sql("""
                INSERT INTO run_trigger (id, tenant_id, workflow_id, type, secret_name, cron, input, budget, enabled,
                                         next_fire_at, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?)
                """)
            .params(t.id(), t.tenant(), t.workflowId(), t.type().name(), t.secretName(), t.cron(),
                json.write(t.input()), json.write(t.budget()), t.enabled(),
                t.nextFireAt() == null ? null : Timestamp.from(t.nextFireAt()), t.createdBy(),
                Timestamp.from(t.createdAt()))
            .update();
    }

    @Override
    public Optional<Trigger> find(String tenant, UUID id) {
        return jdbc.sql("SELECT * FROM run_trigger WHERE tenant_id = ? AND id = ?").params(tenant, id)
            .query(this::row).optional();
    }

    @Override
    public Optional<Trigger> findById(UUID id) {
        return jdbc.sql("SELECT * FROM run_trigger WHERE id = ?").param(id).query(this::row).optional();
    }

    @Override
    public List<Trigger> list(String tenant) {
        return jdbc.sql("SELECT * FROM run_trigger WHERE tenant_id = ? ORDER BY created_at").param(tenant)
            .query(this::row).list();
    }

    @Override
    public void setEnabled(String tenant, UUID id, boolean enabled) {
        jdbc.sql("UPDATE run_trigger SET enabled = ? WHERE tenant_id = ? AND id = ?").params(enabled, tenant, id)
            .update();
    }

    @Override
    public List<Trigger> lockDue(Instant now, int limit) {
        return jdbc.sql("""
                SELECT * FROM run_trigger
                WHERE type = 'CRON' AND enabled AND next_fire_at <= ?
                ORDER BY next_fire_at LIMIT ? FOR UPDATE SKIP LOCKED
                """)
            .params(Timestamp.from(now), limit).query(this::row).list();
    }

    @Override
    public void setNextFire(UUID id, Instant nextFireAt) {
        jdbc.sql("UPDATE run_trigger SET next_fire_at = ? WHERE id = ?").params(Timestamp.from(nextFireAt), id)
            .update();
    }

    private Trigger row(ResultSet rs, int n) throws SQLException {
        Timestamp next = rs.getTimestamp("next_fire_at");
        return new Trigger(rs.getObject("id", UUID.class), rs.getString("tenant_id"),
            rs.getObject("workflow_id", UUID.class), Trigger.Type.valueOf(rs.getString("type")),
            rs.getString("secret_name"), rs.getString("cron"), json.read(rs.getString("input")),
            json.convert(json.read(rs.getString("budget")), BudgetLimits.class), rs.getBoolean("enabled"),
            next == null ? null : next.toInstant(), rs.getString("created_by"),
            rs.getTimestamp("created_at").toInstant());
    }
}
