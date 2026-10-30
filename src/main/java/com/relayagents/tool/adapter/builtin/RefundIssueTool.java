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
 * Records a refund request. Marked as always needing human approval: moving money on a model's say-so is
 * exactly the kind of action a reviewer should see first.
 */
@Component
class RefundIssueTool implements Tool {

    private final ToolDescriptor descriptor;
    private final JdbcClient jdbc;
    private final Json json;

    RefundIssueTool(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
        this.descriptor = new ToolDescriptor("refund_issue", "Issues a refund for an order, in minor units.", json.read("""
            {"type": "object", "additionalProperties": false, "required": ["orderId", "amountMinor", "reason"],
             "properties": {
               "orderId": {"type": "string", "pattern": "^O-[0-9]{1,10}$"},
               "amountMinor": {"type": "integer", "minimum": 1, "maximum": 10000000},
               "reason": {"type": "string", "minLength": 3, "maxLength": 500}}}
            """), Set.of("payments.write"), true, false, "builtin");
    }

    @Override
    public ToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ToolResult invoke(ToolInvocation invocation) {
        JsonNode args = invocation.arguments();
        jdbc.sql("""
                INSERT INTO refund_request (id, tenant_id, idempotency_key, order_id, amount_minor, reason)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (idempotency_key) DO NOTHING
                """)
            .params(UUID.randomUUID(), invocation.tenant(), invocation.idempotencyKey(), args.path("orderId").asText(),
                args.path("amountMinor").asLong(), args.path("reason").asText())
            .update();
        UUID id = jdbc.sql("SELECT id FROM refund_request WHERE idempotency_key = ?")
            .param(invocation.idempotencyKey()).query(UUID.class).single();
        return ToolResult.of(json.object().put("refundId", id.toString()).put("status", "REQUESTED"));
    }
}
