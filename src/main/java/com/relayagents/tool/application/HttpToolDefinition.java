package com.relayagents.tool.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Set;

/**
 * A tenant-registered HTTP tool. {@code urlTemplate} uses {@code {arg}} placeholders filled with
 * URL-encoded argument values; header values may reference secrets with {@code {{secret.NAME}}}.
 */
public record HttpToolDefinition(String tenant, String name, String description, JsonNode schema, Set<String> scopes,
                                 boolean requiresApproval, String method, String urlTemplate,
                                 Map<String, String> headers, String createdBy) {

    public HttpToolDefinition {
        scopes = Set.copyOf(scopes);
        headers = Map.copyOf(headers);
    }
}
