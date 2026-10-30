package com.relayagents.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.relayagents.shared.messaging.Topics;
import com.relayagents.support.Fixtures;
import com.relayagents.support.IntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

class KafkaRunRequestIT extends IntegrationTest {

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Test
    void duplicateRequestMessagesStartOneRun() throws Exception {
        saveAgent("triage", Fixtures.text("agent-triage.json"));
        String workflow = createWorkflow("""
            {"name": "classify-only", "steps": [
              {"id": "classify", "type": "AGENT", "agent": "triage", "prompt": "{{input.message}}"}
            ]}
            """);
        String message = """
            {"tenant": "%s", "workflowId": "%s", "idempotencyKey": "crm-ticket-881", "requestedBy": "crm",
             "input": {"message": "Where is O-5003?"}}
            """.formatted(tenant, workflow);

        kafka.send(Topics.RUN_REQUESTS, tenant, message).get();
        kafka.send(Topics.RUN_REQUESTS, tenant, message).get();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
            jdbc.sql("SELECT count(*) FROM run WHERE tenant_id = ? AND status = 'SUCCEEDED'").param(tenant)
                .query(Long.class).single()).isEqualTo(1));
        Thread.sleep(500);
        assertThat(jdbc.sql("SELECT count(*) FROM run WHERE tenant_id = ?").param(tenant).query(Long.class).single())
            .isEqualTo(1);
    }
}
