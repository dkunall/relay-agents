package com.relayagents.llm.adapter.scripted;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayagents.llm.application.ChatMessage;
import com.relayagents.llm.application.LlmRequest;
import com.relayagents.llm.application.LlmResponse;
import com.relayagents.llm.application.ToolCall;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScriptedLlmProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ScriptedLlmProvider provider = new ScriptedLlmProvider();
    private final JsonNode params = read("""
        {"script": [
          {"when": "order (O-\\\\d+)", "call": {"name": "sql_query", "args": {"sql": "select status from orders where id = '$1'"}}},
          {"when": "DAMAGED", "reply": "Damaged in transit: refund approved path. ($last)"},
          {"reply": "I could not find an order."}
        ]}
        """);

    @Test
    void callsTheToolOnceThenAnswersFromItsResult() {
        LlmResponse first = provider.complete(request(List.of(ChatMessage.user("Where is order O-5002?"))));

        assertThat(first.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("sql_query");
            assertThat(call.arguments().path("sql").asText()).isEqualTo("select status from orders where id = 'O-5002'");
        });

        ToolCall call = first.toolCalls().getFirst();
        LlmResponse second = provider.complete(request(List.of(
            ChatMessage.user("Where is order O-5002?"),
            ChatMessage.assistant("", List.of(call)),
            ChatMessage.tool(call.id(), "{\"rows\": [{\"status\": \"DAMAGED_IN_TRANSIT\"}]} order O-5002"))));

        assertThat(second.toolCalls()).isEmpty();
        assertThat(second.content()).startsWith("Damaged in transit").contains("DAMAGED_IN_TRANSIT");
    }

    @Test
    void sameRequestGivesTheSameAnswerAndTokens() {
        LlmRequest request = request(List.of(ChatMessage.system("Be brief."), ChatMessage.user("hello")));
        assertThat(provider.complete(request)).isEqualTo(provider.complete(request));
        assertThat(provider.complete(request).content()).isEqualTo("I could not find an order.");
    }

    private LlmRequest request(List<ChatMessage> messages) {
        return new LlmRequest("scripted/support", messages, List.of(), 0.0, 512, params, Duration.ofSeconds(5));
    }

    private static JsonNode read(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
