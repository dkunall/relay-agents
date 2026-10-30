package com.relayagents.run;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.relayagents.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RetryIT extends IntegrationTest {

    WireMockServer orders;

    @BeforeEach
    void ordersApi() {
        orders = new WireMockServer(options().dynamicPort());
        orders.start();
        writer.post("/v1/tools/http", Map.of(
            "name", "order_lookup",
            "description", "Fetch an order from the orders API",
            "schema", Map.of("type", "object", "required", List.of("orderId"),
                "properties", Map.of("orderId", Map.of("type", "string"))),
            "scopes", List.of("orders.read"),
            "method", "GET",
            "urlTemplate", "http://localhost:" + orders.port() + "/orders/{orderId}",
            "headers", Map.of("Authorization", "Bearer {{secret.ORDERS_API_TOKEN}}"))).expect(201);
    }

    @AfterEach
    void stop() {
        orders.stop();
    }

    private String workflow() {
        return createWorkflow("""
            {"name": "fetch-order", "steps": [
              {"id": "fetch", "type": "TOOL", "tool": "order_lookup", "grants": ["orders.read"],
               "args": {"orderId": "{{input.orderId}}"},
               "retry": {"maxAttempts": 3, "initialBackoffMs": 50, "maxBackoffMs": 200}}
            ]}
            """);
    }

    @Test
    void transientFailureIsRetriedAndTheSecretNeverLands() {
        orders.stubFor(get("/orders/O-1").inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(503)).willSetStateTo("up"));
        orders.stubFor(get("/orders/O-1").inScenario("flaky").whenScenarioStateIs("up")
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"id\": \"O-1\", \"status\": \"SHIPPED\"}")));

        String runId = startRun(workflow(), "{\"input\": {\"orderId\": \"O-1\"}}").path("id").asText();
        JsonNode done = awaitStatus(runId, "SUCCEEDED", "FAILED");

        assertThat(done.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.path("steps").get(0).path("attempt").asInt()).isEqualTo(2);
        assertThat(done.path("output").toString()).contains("SHIPPED");
        orders.verify(2, getRequestedFor(urlEqualTo("/orders/O-1"))
            .withHeader("Authorization", equalTo("Bearer test-orders-token-123")));
        String events = writer.get("/v1/runs/" + runId + "/events").body().toString();
        assertThat(events).contains("STEP_RETRY_SCHEDULED").doesNotContain("test-orders-token-123");
        assertThat(jdbc.sql("SELECT count(*) FROM run_event WHERE payload::text LIKE '%test-orders-token%'")
            .query(Long.class).single()).isZero();
    }

    @Test
    void clientErrorIsReturnedAsDataWithoutRetrying() {
        orders.stubFor(get("/orders/O-2").willReturn(aResponse().withStatus(404)));

        String runId = startRun(workflow(), "{\"input\": {\"orderId\": \"O-2\"}}").path("id").asText();
        JsonNode done = awaitStatus(runId, "SUCCEEDED", "FAILED");

        orders.verify(1, getRequestedFor(urlEqualTo("/orders/O-2")));
        assertThat(done.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.path("output").toString()).contains("404");
        assertThat(done.path("steps").get(0).path("attempt").asInt()).isEqualTo(1);
    }

    @Test
    void hostsOutsideTheAllowlistCannotBeRegistered() {
        writer.post("/v1/tools/http", Map.of(
            "name", "exfil",
            "description", "nope",
            "schema", Map.of("type", "object"),
            "method", "GET",
            "urlTemplate", "http://169.254.169.254/latest/meta-data")).expect(400);
    }
}
