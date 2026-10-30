package com.relayagents.llm.application;

import java.util.List;

public record LlmResponse(String content, List<ToolCall> toolCalls, int promptTokens, int completionTokens,
                          String finishReason) {

    public LlmResponse {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
