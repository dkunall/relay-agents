package com.relayagents.llm.adapter.scripted;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relayagents.llm.application.ChatMessage;
import com.relayagents.llm.application.LlmException;
import com.relayagents.llm.application.LlmProvider;
import com.relayagents.llm.application.LlmRequest;
import com.relayagents.llm.application.LlmResponse;
import com.relayagents.llm.application.ToolCall;
import com.relayagents.shared.crypto.Sha256;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * A deterministic stand-in for a model, driven by rules in the agent's {@code params.script}:
 *
 * <pre>
 * {"script": [
 *   {"when": "order (O-\\d+)", "call": {"name": "sql_query", "args": {"sql": "select ... '$1'"}}},
 *   {"when": "refund", "reply": "refund"},
 *   {"reply": "fallback answer"}
 * ]}
 * </pre>
 *
 * The latest message is matched against each {@code when} regex in order (case-insensitive, find). A rule
 * with {@code call} fires only if that tool has not been called yet in this conversation, which stops
 * loops. {@code $1}.. in replies and string arguments are regex groups; {@code $last} is the latest message.
 * Token counts are derived from text length, so cost and budget behaviour is reproducible.
 */
@Component
class ScriptedLlmProvider implements LlmProvider {

    private static final Pattern GROUP = Pattern.compile("\\$(\\d|last)");

    @Override
    public String id() {
        return "scripted";
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        simulateLatency(request);
        ChatMessage last = request.messages().getLast();
        List<String> calledTools = request.messages().stream()
            .flatMap(m -> m.toolCalls().stream())
            .map(ToolCall::name)
            .toList();
        JsonNode script = request.params() == null ? null : request.params().get("script");
        if (script != null && script.isArray()) {
            for (JsonNode rule : script) {
                Matcher matcher = matcher(rule, last.content());
                if (matcher == null) {
                    continue;
                }
                JsonNode call = rule.get("call");
                if (call != null) {
                    String tool = call.path("name").asText();
                    if (calledTools.contains(tool)) {
                        continue;
                    }
                    JsonNode args = substitute(call.path("args").deepCopy(), matcher, last.content());
                    String callId = "call_" + Sha256.hex(tool + args + request.messages().size()).substring(0, 12);
                    return response(request, "", List.of(new ToolCall(callId, tool, args)), "tool_calls");
                }
                if (rule.has("reply")) {
                    return response(request, expand(rule.get("reply").asText(), matcher, last.content()), List.of(), "stop");
                }
            }
        }
        String fallback = last.role() == ChatMessage.Role.TOOL ? "Tool result: " + clip(last.content(), 300)
            : "Noted: " + clip(last.content(), 200);
        return response(request, fallback, List.of(), "stop");
    }

    private static Matcher matcher(JsonNode rule, String text) {
        String when = rule.path("when").asText("");
        Matcher matcher = Pattern.compile(when.isEmpty() ? "^" : when, Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
            .matcher(text);
        return matcher.find() ? matcher : null;
    }

    private static JsonNode substitute(JsonNode node, Matcher matcher, String last) {
        if (node instanceof ObjectNode object) {
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(object.properties());
            for (Map.Entry<String, JsonNode> entry : entries) {
                JsonNode value = entry.getValue();
                if (value.isTextual()) {
                    object.put(entry.getKey(), expand(value.asText(), matcher, last));
                } else {
                    object.set(entry.getKey(), substitute(value, matcher, last));
                }
            }
        }
        return node;
    }

    private static String expand(String text, Matcher matcher, String last) {
        Matcher groups = GROUP.matcher(text);
        StringBuilder out = new StringBuilder();
        while (groups.find()) {
            String token = groups.group(1);
            String value;
            if (token.equals("last")) {
                value = last;
            } else {
                int group = Integer.parseInt(token);
                value = group <= matcher.groupCount() && matcher.group(group) != null ? matcher.group(group) : "";
            }
            groups.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        groups.appendTail(out);
        return out.toString();
    }

    private static LlmResponse response(LlmRequest request, String content, List<ToolCall> calls, String finish) {
        int prompt = (int) Math.ceilDiv(request.promptChars(), 4);
        int completion = (int) Math.ceilDiv(content.length(), 4) + calls.size() * 12;
        return new LlmResponse(content, calls, prompt, Math.min(completion, Math.max(1, request.maxTokens())), finish);
    }

    private static void simulateLatency(LlmRequest request) {
        long millis = request.params() == null ? 0 : request.params().path("latencyMs").asLong(0);
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(Math.min(millis, 60_000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted", true, e);
        }
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
