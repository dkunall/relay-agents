package com.relayagents.tool.adapter.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.relayagents.shared.json.Json;
import com.relayagents.tool.application.Tool;
import com.relayagents.tool.application.ToolDescriptor;
import com.relayagents.tool.application.ToolInvocation;
import com.relayagents.tool.application.ToolResult;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Stands in for a ticketing system. The insert is keyed by the invocation's idempotency key, so a step that
 * crashes after the insert and is retried gets the original ticket back instead of a duplicate.
 */
@Component
class TicketCreateTool implements Tool {

    private final ToolDescriptor descriptor;
    private final JdbcClient jdbc;
    private final Json json;

    TicketCreateTool(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
        this.descriptor = new ToolDescriptor("ticket_create", "Opens a support ticket.", json.read("""
            {"type": "object", "additionalProperties": false, "required": ["title", "body"],
             "properties": {
               "title": {"type": "string", "minLength": 3, "maxLength": 200},
               "body": {"type": "string", "maxLength": 4000},
               "priority": {"enum": ["low", "normal", "high"]}}}
            """), Set.of("tickets.write"), false, false, "builtin");
    }

    @Override
    public ToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ToolResult invoke(ToolInvocation invocation) {
        JsonNode args = invocation.arguments();
        jdbc.sql("""
                INSERT INTO ticket (id, tenant_id, idempotency_key, title, body, priority)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (idempotency_key) DO NOTHING
                """)
            .params(UUID.randomUUID(), invocation.tenant(), invocation.idempotencyKey(), args.path("title").asText(),
                args.path("body").asText(""), args.path("priority").asText("normal"))
            .update();
        UUID id = jdbc.sql("SELECT id FROM ticket WHERE idempotency_key = ?")
            .param(invocation.idempotencyKey()).query(UUID.class).single();
        return ToolResult.of(json.object().put("ticketId", id.toString()).put("status", "OPEN"));
    }
}
