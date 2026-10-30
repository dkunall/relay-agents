package com.relayagents.tool.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * @param usedSecrets secret values the tool sent, keyed by name, so the caller can scrub them from
 *                    anything it records
 */
public record ToolResult(JsonNode output, Map<String, String> usedSecrets) {

    public ToolResult {
        usedSecrets = usedSecrets == null ? Map.of() : Map.copyOf(usedSecrets);
    }

    public static ToolResult of(JsonNode output) {
        return new ToolResult(output, Map.of());
    }
}
