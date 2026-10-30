package com.relayagents.trigger;

import static org.assertj.core.api.Assertions.assertThat;

import com.relayagents.support.Api;
import com.relayagents.support.Fixtures;
import com.relayagents.support.IntegrationTest;
import com.relayagents.trigger.application.TriggerService;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebhookIT extends IntegrationTest {

    private static final String SECRET = "test-hook-secret-456";

    String path;

    @BeforeEach
    void trigger() {
        saveAgent("triage", Fixtures.text("agent-triage.json"));
        String workflow = createWorkflow("""
            {"name": "classify-ticket", "steps": [
              {"id": "classify", "type": "AGENT", "agent": "triage", "prompt": "{{input.payload.message}}"}
            ]}
            """);
        path = writer.post("/v1/triggers", Map.of("workflowId", workflow, "type", "WEBHOOK",
            "secretName", "HOOK_SECRET", "input", Map.of("source", "helpdesk"))).expect(201)
            .body().path("webhookPath").asText();
    }

    private Api.Response deliver(String body, String signature, String timestamp, String delivery) {
        return anonymous.send(org.springframework.http.HttpMethod.POST, path, body, Map.of("X-Relay-Timestamp", timestamp,
            "X-Relay-Signature", signature, "X-Relay-Delivery", delivery));
    }

    private static String sign(String ts, String body) {
        return "sha256=" + TriggerService.hmac(SECRET, ts + "." + body);
    }

    @Test
    void signedDeliveryStartsOneRunEvenWhenRedelivered() {
        String body = "{\"message\": \"Where is O-5003?\"}";
        String ts = String.valueOf(Instant.now().getEpochSecond());

        Api.Response first = deliver(body, sign(ts, body), ts, "d-1").expect(202);
        Api.Response again = deliver(body, sign(ts, body), ts, "d-1").expect(202);

        assertThat(again.body().path("runId")).isEqualTo(first.body().path("runId"));
        assertThat(awaitStatus(first.body().path("runId").asText(), "SUCCEEDED", "FAILED").path("status").asText())
            .isEqualTo("SUCCEEDED");
    }

    @Test
    void badOrStaleSignaturesAreRejected() {
        String body = "{\"message\": \"hi\"}";
        String now = String.valueOf(Instant.now().getEpochSecond());
        String old = String.valueOf(Instant.now().minusSeconds(900).getEpochSecond());

        deliver(body, sign(now, "{\"message\": \"other\"}"), now, "d-2").expect(403);
        deliver(body, sign(old, body), old, "d-3").expect(403);
        assertThat(jdbc.sql("SELECT count(*) FROM run WHERE tenant_id = ?").param(tenant).query(Long.class).single())
            .isZero();
    }
}
