package com.relayagents.llm.application;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A model asking for a tool. {@code arguments} is whatever the model produced and is validated against
 * the tool's schema before anything runs.
 */
public record ToolCall(String id, String name, JsonNode arguments) {
}
