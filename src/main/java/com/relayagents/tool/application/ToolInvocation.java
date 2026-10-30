package com.relayagents.tool.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;

public record ToolInvocation(String tenant, JsonNode arguments, String idempotencyKey, Duration timeout) {
}
