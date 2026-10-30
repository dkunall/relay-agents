package com.relayagents.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.relayagents.support.Fixtures;
import com.relayagents.support.IntegrationTest;
import org.junit.jupiter.api.Test;

class BudgetIT extends IntegrationTest {

    @Test
    void runStopsBeforeAModelCallThatWouldOverspend() {
        saveAgent("triage", Fixtures.text("agent-triage.json"));
        String workflow = createWorkflow("""
            {"name": "classify-only", "steps": [
              {"id": "classify", "type": "AGENT", "agent": "triage", "prompt": "{{input.message}}"}
            ]}
            """);

        String runId = startRun(workflow, """
            {"input": {"message": "Where is O-5003?"}, "budget": {"maxTokens": 10}}
            """).path("id").asText();
        JsonNode done = awaitStatus(runId, "BUDGET_EXCEEDED", "SUCCEEDED", "FAILED");

        assertThat(done.path("status").asText()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(done.path("usage").path("tokens").asLong()).isLessThanOrEqualTo(10);
        assertThat(writer.get("/v1/runs/" + runId + "/events").body().toString()).doesNotContain("LLM_CALLED");
    }

    @Test
    void toolCallLimitStopsAnAgentLoop() {
        saveAgent("support", Fixtures.text("agent-support.json"));
        String workflow = createWorkflow("""
            {"name": "lookup", "steps": [
              {"id": "status", "type": "AGENT", "agent": "support", "prompt": "{{input.message}}"}
            ]}
            """);

        String runId = startRun(workflow, """
            {"input": {"message": "Where is O-5003?"}, "budget": {"maxToolCalls": 0}}
            """).path("id").asText();

        assertThat(awaitStatus(runId, "BUDGET_EXCEEDED", "SUCCEEDED", "FAILED").path("status").asText())
            .isEqualTo("BUDGET_EXCEEDED");
    }
}
