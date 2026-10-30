package com.relayagents.llm.adapter.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relayagents.llm.application.ChatMessage;
import com.relayagents.llm.application.LlmException;
import com.relayagents.llm.application.LlmProvider;
import com.relayagents.llm.application.LlmRequest;
import com.relayagents.llm.application.LlmResponse;
import com.relayagents.llm.application.ToolCall;
import com.relayagents.llm.application.ToolSpec;
import com.relayagents.secret.application.SecretResolver;
import com.relayagents.shared.json.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Talks the OpenAI chat-completions wire format, which is what TokenGate, Ollama ({@code /v1}), vLLM and
 * most gateways accept. The API key is looked up by secret name per call and only ever placed in the
 * Authorization header.
 */
@Component
@ConditionalOnProperty(prefix = "relay.llm.openai", name = "base-url")
class OpenAiCompatibleProvider implements LlmProvider {

    private final HttpClient http;
    private final URI endpoint;
    private final String apiKeySecret;
    private final SecretResolver secrets;
    private final Json json;

    OpenAiCompatibleProvider(@Value("${relay.llm.openai.base-url}") String baseUrl,
                             @Value("${relay.llm.openai.api-key-secret:}") String apiKeySecret,
                             @Value("${relay.llm.openai.connect-timeout:PT2S}") Duration connectTimeout,
                             SecretResolver secrets, Json json) {
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions");
        this.apiKeySecret = apiKeySecret;
        this.secrets = secrets;
        this.json = json;
        this.http = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    @Override
    public String id() {
        return "openai";
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
            .timeout(request.timeout())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.write(body(request))));
        if (!apiKeySecret.isBlank()) {
            String key = secrets.resolve(apiKeySecret)
                .orElseThrow(() -> new LlmException("Secret " + apiKeySecret + " is not configured", false));
            builder.header("Authorization", "Bearer " + key);
        }
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new LlmException("Model call timed out after " + request.timeout(), true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted", true, e);
        } catch (java.io.IOException e) {
            throw new LlmException("Model endpoint unreachable: " + e.getClass().getSimpleName(), true, e);
        }
        int status = response.statusCode();
        if (status == 429 || status >= 500) {
            throw new LlmException("Model endpoint returned " + status, true);
        }
        if (status >= 400) {
            throw new LlmException("Model endpoint rejected the request with " + status, false);
        }
        return parse(response.body());
    }

    private ObjectNode body(LlmRequest request) {
        ObjectNode body = json.object();
        body.put("model", request.model());
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxTokens());
        ArrayNode messages = body.putArray("messages");
        for (ChatMessage message : request.messages()) {
            ObjectNode m = messages.addObject();
            m.put("role", message.role().name().toLowerCase(Locale.ROOT));
            m.put("content", message.content());
            if (message.toolCallId() != null) {
                m.put("tool_call_id", message.toolCallId());
            }
            if (!message.toolCalls().isEmpty()) {
                ArrayNode calls = m.putArray("tool_calls");
                for (ToolCall call : message.toolCalls()) {
                    ObjectNode c = calls.addObject();
                    c.put("id", call.id());
                    c.put("type", "function");
                    c.putObject("function").put("name", call.name()).put("arguments", json.write(call.arguments()));
                }
            }
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolSpec tool : request.tools()) {
                ObjectNode function = tools.addObject().put("type", "function").putObject("function");
                function.put("name", tool.name());
                function.put("description", tool.description());
                function.set("parameters", tool.parameters());
            }
        }
        return body;
    }

    private LlmResponse parse(String raw) {
        JsonNode root;
        try {
            root = json.read(raw);
        } catch (IllegalArgumentException e) {
            throw new LlmException("Model endpoint returned non-JSON", true, e);
        }
        JsonNode choice = root.path("choices").path(0);
        if (choice.isMissingNode()) {
            throw new LlmException("Model response has no choices", true);
        }
        JsonNode message = choice.path("message");
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            JsonNode function = call.path("function");
            JsonNode arguments;
            try {
                arguments = json.read(function.path("arguments").asText("{}"));
            } catch (IllegalArgumentException e) {
                // Models do emit broken JSON; pass it through as text so schema validation reports it.
                arguments = json.mapper().getNodeFactory().textNode(function.path("arguments").asText());
            }
            calls.add(new ToolCall(call.path("id").asText(), function.path("name").asText(), arguments));
        }
        JsonNode usage = root.path("usage");
        return new LlmResponse(message.path("content").asText(""), calls,
            usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0),
            choice.path("finish_reason").asText(null));
    }
}
