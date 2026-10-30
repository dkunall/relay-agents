package com.relayagents.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.relayagents.support.Api;
import com.relayagents.support.IntegrationTest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class IdempotencyIT extends IntegrationTest {

    private static final String ONE_STEP = """
        {"name": "classify-only", "steps": [
          {"id": "classify", "type": "AGENT", "agent": "triage", "prompt": "{{input.message}}"}
        ]}
        """;

    @Test
    void concurrentStartsWithOneKeyCreateOneRun() throws Exception {
        saveAgent("triage", com.relayagents.support.Fixtures.text("agent-triage.json"));
        String workflow = createWorkflow(ONE_STEP);
        String body = "{\"input\": {\"message\": \"Where is O-5003?\"}}";
        int callers = 8;
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<Api.Response>> calls = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            calls.add(() -> {
                go.await();
                return writer.send(org.springframework.http.HttpMethod.POST, "/v1/workflows/" + workflow + "/runs", body,
                    Map.of("Idempotency-Key", "order-5003-question"));
            });
        }
        List<Api.Response> responses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
            List<Future<Api.Response>> futures = calls.stream().map(pool::submit).toList();
            go.countDown();
            for (Future<Api.Response> f : futures) {
                responses.add(f.get());
            }
        }

        Set<String> ids = new HashSet<>();
        int created = 0;
        for (Api.Response r : responses) {
            assertThat(r.status()).isIn(200, 201);
            created += r.status() == 201 ? 1 : 0;
            ids.add(r.body().path("id").asText());
        }
        assertThat(created).isEqualTo(1);
        assertThat(ids).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM run WHERE tenant_id = ?").param(tenant).query(Long.class).single())
            .isEqualTo(1);
    }

    @Test
    void reusingAKeyForADifferentRequestIsRejected() {
        saveAgent("triage", com.relayagents.support.Fixtures.text("agent-triage.json"));
        String workflow = createWorkflow(ONE_STEP);
        writer.send(org.springframework.http.HttpMethod.POST, "/v1/workflows/" + workflow + "/runs", "{\"input\": {\"message\": \"a\"}}",
            Map.of("Idempotency-Key", "k1")).expect(201);

        Api.Response reused = writer.send(org.springframework.http.HttpMethod.POST, "/v1/workflows/" + workflow + "/runs",
            "{\"input\": {\"message\": \"b\"}}", Map.of("Idempotency-Key", "k1"));

        assertThat(reused.status()).isEqualTo(422);
        assertThat(reused.body().path("code").asText()).isEqualTo("idempotency-key-reused");
    }
}
