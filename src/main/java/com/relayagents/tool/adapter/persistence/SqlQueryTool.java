package com.relayagents.tool.adapter.persistence;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relayagents.shared.json.Json;
import com.relayagents.tool.application.Tool;
import com.relayagents.tool.application.ToolDescriptor;
import com.relayagents.tool.application.ToolException;
import com.relayagents.tool.application.ToolInvocation;
import com.relayagents.tool.application.ToolResult;
import java.sql.ResultSetMetaData;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lets an agent read the {@code sandbox} schema. The real guards are in the database: the transaction is
 * read-only, runs as the {@code relay_sandbox} role (SELECT on sandbox tables only), has a statement
 * timeout, and row-level security filters by tenant. The lexical checks below only exist to give the
 * model a clear error instead of a permission failure, and to keep the session settings out of reach.
 */
@Component
class SqlQueryTool implements Tool {

    static final int MAX_ROWS = 100;
    private static final Pattern START = Pattern.compile("^\\s*(select|with)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DENIED = Pattern.compile(
        "\\b(set_config|current_setting|pg_\\w*|lo_\\w*|dblink\\w*|copy)\\b|\\bu&", Pattern.CASE_INSENSITIVE);

    private final ToolDescriptor descriptor;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Json json;

    SqlQueryTool(JdbcClient jdbc, TransactionTemplate tx, Json json) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
        this.descriptor = new ToolDescriptor("sql_query",
            "Runs one read-only SELECT against the support data (tables: customer, orders). Returns at most "
                + MAX_ROWS + " rows.",
            json.read("""
                {"type": "object", "additionalProperties": false, "required": ["sql"],
                 "properties": {"sql": {"type": "string", "minLength": 6, "maxLength": 2000}}}
                """), Set.of("data.read"), false, false, "builtin");
    }

    @Override
    public ToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ToolResult invoke(ToolInvocation invocation) {
        String sql = invocation.arguments().path("sql").asText().strip();
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).strip();
        }
        if (!START.matcher(sql).find() || sql.contains(";")) {
            throw new ToolException("Only a single SELECT statement is allowed", false);
        }
        if (DENIED.matcher(sql).find()) {
            throw new ToolException("Query uses a function or relation that is not available", false);
        }
        String query = sql;
        long timeoutMillis = Math.max(100, Math.min(invocation.timeout().toMillis(), 2_000));
        try {
            return tx.execute(status -> {
                jdbc.sql("SET TRANSACTION READ ONLY").update();
                jdbc.sql("SET LOCAL ROLE relay_sandbox").update();
                jdbc.sql("SET LOCAL statement_timeout = " + timeoutMillis).update();
                jdbc.sql("SET LOCAL search_path = sandbox").update();
                jdbc.sql("SELECT set_config('relay.tenant', ?, true)").param(invocation.tenant()).query(String.class).single();
                ArrayNode rows = json.mapper().createArrayNode();
                boolean[] truncated = {false};
                jdbc.sql("SELECT * FROM (" + query + ") q LIMIT " + (MAX_ROWS + 1)).query(rs -> {
                    if (rows.size() >= MAX_ROWS) {
                        truncated[0] = true;
                        return;
                    }
                    ResultSetMetaData meta = rs.getMetaData();
                    ObjectNode row = rows.addObject();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        Object value = rs.getObject(i);
                        row.set(meta.getColumnLabel(i).toLowerCase(Locale.ROOT),
                            value == null ? json.mapper().nullNode() : json.mapper().valueToTree(value.toString()));
                    }
                });
                ObjectNode result = json.object();
                result.set("rows", rows);
                result.put("rowCount", rows.size());
                result.put("truncated", truncated[0]);
                return ToolResult.of(result);
            });
        } catch (DataAccessException e) {
            String reason = e.getMostSpecificCause().getMessage();
            boolean timeout = reason != null && reason.contains("statement timeout");
            throw new ToolException("Query failed: " + firstLine(reason), timeout, e);
        }
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "unknown error";
        }
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }
}
