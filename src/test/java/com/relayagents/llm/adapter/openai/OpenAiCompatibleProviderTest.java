package com.relayagents.llm.adapter.openai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.relayagents.llm.application.ChatMessage;
import com.relayagents.llm.application.LlmException;
import com.relayagents.llm.application.LlmRequest;
import com.relayagents.llm.application.LlmResponse;
import com.relayagents.llm.application.ToolSpec;
import com.relayagents.shared.json.Json;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Runs against a stub, not a real model: it pins the wire format (which Ollama's /v1 endpoint also speaks),
 * not model behaviour.
 */
class OpenAiCompatibleProviderTest {

    private final Json json = new Json(new ObjectMapper());
    private WireMockServer server;
    private OpenAiCompatibleProvider provider;

    @BeforeEach
    void start() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        provider = new OpenAiCompatibleProvider(server.baseUrl() + "/v1/", "LLM_KEY", Duration.ofSeconds(1),
            name -> name.equals("LLM_KEY") ? Optional.of("stub-key") : Optional.empty(), json);
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private LlmRequest request() {
        return new LlmRequest("qwen2.5", List.of(ChatMessage.system("be brief"), ChatMessage.user("O-5003?")),
            List.of(new ToolSpec("sql_query", "read orders", json.read("{\"type\":\"object\"}"))), 0.0, 256,
            json.object(), Duration.ofSeconds(1));
    }

    @Test
    void sendsToolsAndParsesToolCallsAndUsage() {
        server.stubFor(post("/v1/chat/completions").willReturn(aResponse().withHeader("Content-Type", "application/json")
            .withBody("""
                {"choices": [{"finish_reason": "tool_calls", "message": {"content": null, "tool_calls": [
                  {"id": "c1", "type": "function", "function": {"name": "sql_query",
                   "arguments": "{\\"sql\\": \\"select 1\\"}"}}]}}],
                 "usage": {"prompt_tokens": 42, "completion_tokens": 7}}
                """)));

        LlmResponse response = provider.complete(request());

        assertThat(response.toolCalls()).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("sql_query");
            assertThat(c.arguments().path("sql").asText()).isEqualTo("select 1");
        });
        assertThat(response.promptTokens()).isEqualTo(42);
        assertThat(response.completionTokens()).isEqualTo(7);
        server.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
            .withHeader("Authorization", equalTo("Bearer stub-key"))
            .withRequestBody(matchingJsonPath("$.tools[0].function.name", equalTo("sql_query")))
            .withRequestBody(matchingJsonPath("$.messages[1].role", equalTo("user"))));
    }

    @Test
    void separatesRetriableFromPermanentFailures() {
        server.stubFor(post("/v1/chat/completions").willReturn(aResponse().withStatus(429)));
        assertThatThrownBy(() -> provider.complete(request()))
            .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.retriable()).isTrue());

        server.resetAll();
        server.stubFor(post("/v1/chat/completions").willReturn(aResponse().withStatus(400)));
        assertThatThrownBy(() -> provider.complete(request()))
            .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.retriable()).isFalse());
    }

    @Test
    void slowEndpointTimesOutAsRetriable() {
        server.stubFor(post("/v1/chat/completions").willReturn(aResponse().withFixedDelay(1500).withBody("{}")));
        assertThatThrownBy(() -> provider.complete(request()))
            .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.retriable()).isTrue());
    }
}
