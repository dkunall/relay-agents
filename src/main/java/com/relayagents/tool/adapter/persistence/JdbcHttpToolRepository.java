package com.relayagents.tool.adapter.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.relayagents.shared.json.Json;
import com.relayagents.tool.application.HttpToolDefinition;
import com.relayagents.tool.application.HttpToolRepository;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcHttpToolRepository implements HttpToolRepository {

    private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final Json json;

    JdbcHttpToolRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public boolean insert(HttpToolDefinition d) {
        return jdbc.sql("""
                INSERT INTO http_tool (tenant_id, name, description, json_schema, scopes, requires_approval, method,
                                       url_template, headers, created_by)
                VALUES (:tenant, :name, :description, CAST(:schema AS jsonb), :scopes, :approval,
                        :method, :url, CAST(:headers AS jsonb), :createdBy)
                ON CONFLICT (tenant_id, name) DO NOTHING
                """)
            .param("tenant", d.tenant())
            .param("name", d.name())
            .param("description", d.description())
            .param("schema", json.write(d.schema()))
            .param("scopes", d.scopes().stream().sorted().toArray(String[]::new))
            .param("approval", d.requiresApproval())
            .param("method", d.method())
            .param("url", d.urlTemplate())
            .param("headers", json.write(d.headers()))
            .param("createdBy", d.createdBy())
            .update() == 1;
    }

    @Override
    public Optional<HttpToolDefinition> find(String tenant, String name) {
        return jdbc.sql("SELECT * FROM http_tool WHERE tenant_id = ? AND name = ?")
            .params(tenant, name).query(this::map).optional();
    }

    @Override
    public List<HttpToolDefinition> list(String tenant) {
        return jdbc.sql("SELECT * FROM http_tool WHERE tenant_id = ? ORDER BY name").param(tenant).query(this::map).list();
    }

    private HttpToolDefinition map(ResultSet rs, int row) throws SQLException {
        Map<String, String> headers;
        try {
            headers = json.mapper().readValue(rs.getString("headers"), HEADERS);
        } catch (Exception e) {
            throw new SQLException("Corrupt headers for tool " + rs.getString("name"), e);
        }
        return new HttpToolDefinition(rs.getString("tenant_id"), rs.getString("name"), rs.getString("description"),
            json.read(rs.getString("json_schema")), strings(rs.getArray("scopes")), rs.getBoolean("requires_approval"),
            rs.getString("method"), rs.getString("url_template"), headers, rs.getString("created_by"));
    }

    private static Set<String> strings(Array array) throws SQLException {
        return array == null ? Set.of() : Set.of((String[]) array.getArray());
    }
}
