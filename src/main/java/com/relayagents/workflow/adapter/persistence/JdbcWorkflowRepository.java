package com.relayagents.workflow.adapter.persistence;

import com.relayagents.shared.json.Json;
import com.relayagents.workflow.application.WorkflowRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcWorkflowRepository implements WorkflowRepository {

    private final JdbcClient jdbc;
    private final Json json;

    JdbcWorkflowRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public boolean insert(UUID id, int version, String tenant, String name, JsonNode definition, String createdBy) {
        return jdbc.sql("""
                INSERT INTO workflow (id, version, tenant_id, name, definition, created_by)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)
                ON CONFLICT (id, version) DO NOTHING
                """)
            .params(id, version, tenant, name, json.write(definition), createdBy)
            .update() == 1;
    }

    @Override
    public Optional<Row> find(String tenant, UUID id, int version) {
        return jdbc.sql("SELECT * FROM workflow WHERE tenant_id = ? AND id = ? AND version = ?")
            .params(tenant, id, version).query(this::row).optional();
    }

    @Override
    public Optional<Row> findLatest(String tenant, UUID id) {
        return jdbc.sql("SELECT * FROM workflow WHERE tenant_id = ? AND id = ? ORDER BY version DESC LIMIT 1")
            .params(tenant, id).query(this::row).optional();
    }

    @Override
    public List<Row> listLatest(String tenant) {
        return jdbc.sql("""
                SELECT DISTINCT ON (id) * FROM workflow WHERE tenant_id = ?
                ORDER BY id, version DESC
                """)
            .param(tenant).query(this::row).list();
    }

    private Row row(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getInt("version"), rs.getString("tenant_id"),
            rs.getString("name"), json.read(rs.getString("definition")), rs.getString("created_by"),
            rs.getTimestamp("created_at").toInstant());
    }
}
