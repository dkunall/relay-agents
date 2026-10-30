package com.relayagents.support;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Set;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * One set of containers per JVM and one Spring context for every IT: tenants are unique per test, so tests
 * do not need to clean up after each other.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestJwt.Decoder.class)
public abstract class IntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("relay");

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0")
        .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx256m");

    static {
        Startables.deepStart(POSTGRES, REDIS, KAFKA).join();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("relay.runs.poll-interval", () -> "PT0.05S");
        registry.add("relay.runs.reaper-interval", () -> "PT0.5S");
        registry.add("relay.tools.http.allowed-hosts", () -> "localhost");
        registry.add("relay.secret.orders_api_token", () -> "test-orders-token-123");
        registry.add("relay.secret.hook_secret", () -> "test-hook-secret-456");
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcClient jdbc;

    protected String tenant;
    protected Api writer;
    protected Api approver;
    protected Api anonymous;

    @BeforeEach
    void tenantPerTest() {
        useTenant("t-" + Long.toHexString(System.nanoTime()));
    }

    /**
     * The sandbox support data is seeded for the acme and globex tenants only.
     */
    protected void useTenant(String tenant) {
        this.tenant = tenant;
        anonymous = Api.at(port);
        writer = anonymous.as(TestJwt.token(tenant, "dev-asha", "relay.read", "relay.write"));
        approver = anonymous.as(TestJwt.token(tenant, "lead-ravi", "relay.read", "relay.approve"));
    }

    protected Api as(String tenant, String subject, String... scopes) {
        return anonymous.as(TestJwt.token(tenant, subject, scopes));
    }

    protected String createKnowledgeBase(String name, String docsFixture) {
        String kb = writer.post("/v1/kb", java.util.Map.of("name", name)).expect(201).body().path("id").asText();
        for (JsonNode doc : Fixtures.json(docsFixture)) {
            writer.post("/v1/kb/" + kb + "/documents", doc).expect(201);
        }
        return kb;
    }

    protected String createSupportWorkflow() {
        saveAgent("triage", Fixtures.text("agent-triage.json"));
        saveAgent("support", Fixtures.text("agent-support.json"));
        String kb = createKnowledgeBase("policies-" + Long.toHexString(System.nanoTime()), "kb-docs.json");
        return createWorkflow(Fixtures.text("workflow-support.json").replace("__KB__", kb));
    }

    protected String createWorkflow(String definition) {
        return writer.post("/v1/workflows", definition).expect(201).body().path("id").asText();
    }

    protected void saveAgent(String name, String body) {
        writer.put("/v1/agents/" + name, body).expect(200);
    }

    protected JsonNode startRun(String workflowId, String body) {
        return writer.post("/v1/workflows/" + workflowId + "/runs", body).expect(201).body();
    }

    protected JsonNode awaitStatus(String runId, String... statuses) {
        Set<String> wanted = Set.of(statuses);
        JsonNode[] last = new JsonNode[1];
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).until(() -> {
            last[0] = writer.get("/v1/runs/" + runId).expect(200).body();
            return wanted.contains(last[0].path("status").asText());
        });
        return last[0];
    }

    protected static String step(JsonNode run, String stepId) {
        for (JsonNode step : run.path("steps")) {
            if (step.path("id").asText().equals(stepId)) {
                return step.path("status").asText();
            }
        }
        throw new AssertionError("no step " + stepId + " in " + run);
    }
}
