package com.relayagents.llm.application;

import java.util.List;
import java.util.Objects;

public record ChatMessage(Role role, String content, List<ToolCall> toolCalls, String toolCallId) {

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content, List.of(), null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content, List.of(), null);
    }

    public static ChatMessage assistant(String content, List<ToolCall> calls) {
        return new ChatMessage(Role.ASSISTANT, content, calls, null);
    }

    public static ChatMessage tool(String callId, String content) {
        return new ChatMessage(Role.TOOL, content, List.of(), callId);
    }
}
