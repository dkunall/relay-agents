package com.relayagents.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.relayagents.run.application.RunProjection;
import com.relayagents.run.application.RunService;
import com.relayagents.run.domain.RunStatus;
import com.relayagents.run.domain.StepStatus;
import com.relayagents.shared.domain.Actor;
import com.relayagents.support.IntegrationTest;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SupportWorkflowIT extends IntegrationTest {

    @Autowired
    RunService runs;

    String workflow;

    @BeforeEach
    void acme() {
        useTenant("acme");
        workflow = createSupportWorkflow();
    }

    @Test
    void statusQuestionIsAnsweredFromTheSandboxDatabase() {
        JsonNode run = startRun(workflow, """
            {"input": {"message": "Where is my order O-5003?"}}
            """);

        JsonNode done = awaitStatus(run.path("id").asText(), "SUCCEEDED", "FAILED");

        assertThat(done.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.path("output").toString()).contains("Your order is currently SHIPPED.");
        assertThat(step(done, "policy")).isEqualTo("SKIPPED");
        assertThat(step(done, "refund")).isEqualTo("SKIPPED");
        assertThat(done.path("usage").path("toolCalls").asInt()).isEqualTo(1);
        assertThat(done.path("usage").path("tokens").asLong()).isPositive();
    }

    @Test
    void refundWaitsForSomeoneOtherThanTheRequesterThenRunsExactlyOnce() {
        JsonNode run = startRun(workflow, """
            {"input": {"message": "My order O-5002 arrived damaged, I want a refund", "amountMinor": 129900}}
            """);
        String runId = run.path("id").asText();

        JsonNode waiting = awaitStatus(runId, "WAITING_APPROVAL", "FAILED");
        assertThat(waiting.path("status").asText()).isEqualTo("WAITING_APPROVAL");
        JsonNode approval = approver.get("/v1/approvals").expect(200).body().get(0);
        assertThat(approval.path("tool").asText()).isEqualTo("refund_issue");
        assertThat(approval.path("arguments").path("orderId").asText()).isEqualTo("O-5002");

        as("acme", "dev-asha", "relay.read", "relay.approve")
            .post("/v1/runs/" + runId + "/approvals/refund", Map.of("approve", true)).expect(403);
        approver.post("/v1/runs/" + runId + "/approvals/refund", Map.of("approve", true, "comment", "photo checked"))
            .expect(200);

        JsonNode done = awaitStatus(runId, "SUCCEEDED", "FAILED");
        assertThat(done.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.sql("SELECT count(*) FROM refund_request WHERE idempotency_key LIKE ?")
            .param(runId + "%").query(Long.class).single()).isEqualTo(1);
        approver.post("/v1/runs/" + runId + "/approvals/refund", Map.of("approve", true)).expect(409);
    }

    @Test
    void rejectedApprovalFailsTheRunWithoutCallingTheTool() {
        String runId = startRun(workflow, """
            {"input": {"message": "Order O-5001 is broken", "amountMinor": 1000}}
            """).path("id").asText();
        awaitStatus(runId, "WAITING_APPROVAL");

        approver.post("/v1/runs/" + runId + "/approvals/refund", Map.of("approve", false, "comment", "no evidence"))
            .expect(200);

        JsonNode done = awaitStatus(runId, "FAILED", "SUCCEEDED");
        assertThat(done.path("status").asText()).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT count(*) FROM refund_request WHERE idempotency_key LIKE ?")
            .param(runId + "%").query(Long.class).single()).isZero();
    }

    @Test
    void eventLogFoldsToTheSameStateAsTheTables() {
        String runId = startRun(workflow, """
            {"input": {"message": "Where is O-5001?"}}
            """).path("id").asText();
        JsonNode done = awaitStatus(runId, "SUCCEEDED", "FAILED");

        Actor actor = new Actor("dev-asha", "acme", Set.of("relay.read"));
        RunProjection.State folded = RunProjection.fold(runs.events(actor, UUID.fromString(runId), 0, 1000));

        assertThat(folded.status()).isEqualTo(RunStatus.valueOf(done.path("status").asText()));
        for (JsonNode step : done.path("steps")) {
            assertThat(folded.steps().get(step.path("id").asText()))
                .isEqualTo(StepStatus.valueOf(step.path("status").asText()));
        }
        assertThat(folded.output()).isEqualTo(done.path("output"));
        assertThat(folded.tokens()).isEqualTo(done.path("usage").path("tokens").asLong());
        assertThat(folded.costMicros()).isEqualTo(done.path("usage").path("costMicros").asLong());
        assertThat(folded.toolCalls()).isEqualTo(done.path("usage").path("toolCalls").asInt());
    }

    @Test
    void anotherTenantCannotSeeTheRunOrTheSandboxRows() {
        String runId = startRun(workflow, """
            {"input": {"message": "Where is O-7001?"}}
            """).path("id").asText();
        JsonNode done = awaitStatus(runId, "SUCCEEDED", "FAILED");

        assertThat(done.path("output").toString()).contains("could not find that order");
        as("globex", "dev-lena", "relay.read").get("/v1/runs/" + runId).expect(404);
    }
}
