package com.relayagents.llm.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * @param params provider-specific settings from the agent definition (the scripted provider reads its
 *               rules from here)
 */
public record LlmRequest(String model, List<ChatMessage> messages, List<ToolSpec> tools, double temperature,
                         int maxTokens, JsonNode params, Duration timeout) {

    public LlmRequest {
        Objects.requireNonNull(model, "model");
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        Objects.requireNonNull(timeout, "timeout");
    }

    public String provider() {
        int slash = model.indexOf('/');
        return slash < 0 ? model : model.substring(0, slash);
    }

    public String providerModel() {
        int slash = model.indexOf('/');
        return slash < 0 ? model : model.substring(slash + 1);
    }

    public LlmRequest withModel(String newModel) {
        return new LlmRequest(newModel, messages, tools, temperature, maxTokens, params, timeout);
    }

    public long promptChars() {
        long chars = 0;
        for (ChatMessage m : messages) {
            chars += m.content().length();
            for (ToolCall call : m.toolCalls()) {
                chars += call.name().length() + String.valueOf(call.arguments()).length();
            }
        }
        for (ToolSpec tool : tools) {
            chars += tool.name().length() + tool.description().length() + String.valueOf(tool.parameters()).length();
        }
        return chars;
    }
}
