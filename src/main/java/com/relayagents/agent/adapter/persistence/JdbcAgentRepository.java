package com.relayagents.agent.adapter.persistence;

import com.relayagents.agent.application.AgentRepository;
import com.relayagents.agent.domain.AgentDefinition;
import com.relayagents.shared.json.Json;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAgentRepository implements AgentRepository {

    private final JdbcClient jdbc;
    private final Json json;

    JdbcAgentRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void upsert(String tenant, AgentDefinition agent, String updatedBy) {
        jdbc.sql("""
                INSERT INTO agent (tenant_id, name, model, system_prompt, tools, scopes, params, memory_window,
                                   updated_by, updated_at)
                VALUES (:tenant, :name, :model, :prompt, :tools, :scopes, CAST(:params AS jsonb), :window, :by, now())
                ON CONFLICT (tenant_id, name) DO UPDATE SET
                    model = EXCLUDED.model, system_prompt = EXCLUDED.system_prompt, tools = EXCLUDED.tools,
                    scopes = EXCLUDED.scopes, params = EXCLUDED.params, memory_window = EXCLUDED.memory_window,
                    updated_by = EXCLUDED.updated_by, updated_at = now()
                """)
            .param("tenant", tenant)
            .param("name", agent.name())
            .param("model", agent.model())
            .param("prompt", agent.systemPrompt())
            .param("tools", agent.tools().stream().sorted().toArray(String[]::new))
            .param("scopes", agent.scopes().stream().sorted().toArray(String[]::new))
            .param("params", agent.params() == null ? "{}" : json.write(agent.params()))
            .param("window", agent.memoryWindow())
            .param("by", updatedBy)
            .update();
    }

    @Override
    public Optional<AgentDefinition> find(String tenant, String name) {
        return jdbc.sql("SELECT * FROM agent WHERE tenant_id = ? AND name = ?")
            .params(tenant, name).query(this::map).optional();
    }

    @Override
    public Map<String, AgentDefinition> findAll(String tenant, Collection<String> names) {
        if (names.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("SELECT * FROM agent WHERE tenant_id = :tenant AND name IN (:names)")
            .param("tenant", tenant).param("names", List.copyOf(names))
            .query(this::map).list().stream()
            .collect(Collectors.toMap(AgentDefinition::name, Function.identity()));
    }

    @Override
    public List<AgentDefinition> list(String tenant) {
        return jdbc.sql("SELECT * FROM agent WHERE tenant_id = ? ORDER BY name").param(tenant).query(this::map).list();
    }

    private AgentDefinition map(ResultSet rs, int row) throws SQLException {
        return new AgentDefinition(rs.getString("name"), rs.getString("model"), rs.getString("system_prompt"),
            strings(rs.getArray("tools")), strings(rs.getArray("scopes")), json.read(rs.getString("params")),
            rs.getInt("memory_window"));
    }

    private static Set<String> strings(Array array) throws SQLException {
        return array == null ? Set.of() : Set.of((String[]) array.getArray());
    }
}
