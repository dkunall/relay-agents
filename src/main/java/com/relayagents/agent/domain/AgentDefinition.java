package com.relayagents.agent.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An agent is a model, a system prompt and the tools it may use. {@code scopes} are the permissions its
 * tool calls carry; a tool whose scopes are not all granted here is refused even if it is in {@code tools}.
 *
 * @param params provider settings: {@code temperature}, {@code maxTokens}, and for the scripted provider
 *               its {@code script}
 */
public record AgentDefinition(String name, String model, String systemPrompt, Set<String> tools, Set<String> scopes,
                              JsonNode params, int memoryWindow) {

    public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_-]{1,47}");
    public static final int DEFAULT_MAX_TOKENS = 512;

    public AgentDefinition {
        Objects.requireNonNull(model, "model");
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Agent names must match " + NAME.pattern());
        }
        if (systemPrompt == null || systemPrompt.isBlank() || systemPrompt.length() > 8_000) {
            throw new IllegalArgumentException("systemPrompt must be 1-8000 characters");
        }
        if (memoryWindow < 2 || memoryWindow > 100) {
            throw new IllegalArgumentException("memoryWindow must be 2-100 messages");
        }
        tools = Set.copyOf(tools);
        scopes = Set.copyOf(scopes);
    }

    public double temperature() {
        return params == null ? 0.0 : params.path("temperature").asDouble(0.0);
    }

    public int maxTokens() {
        int requested = params == null ? DEFAULT_MAX_TOKENS : params.path("maxTokens").asInt(DEFAULT_MAX_TOKENS);
        return Math.max(1, Math.min(requested, 8_192));
    }
}
