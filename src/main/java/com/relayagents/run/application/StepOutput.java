package com.relayagents.run.application;

import com.fasterxml.jackson.databind.JsonNode;

public record StepOutput(JsonNode value, boolean untrusted) {
}
