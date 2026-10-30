package com.relayagents.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.relayagents.support.IntegrationTest;
import com.relayagents.support.TestJwt;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecurityIT extends IntegrationTest {

    private static final String WORKFLOW = """
        {"name": "noop", "steps": [{"id": "a", "type": "AGENT", "agent": "triage", "prompt": "hi"}]}
        """;

    @Test
    void anonymousCallsAreUnauthenticated() {
        assertThat(anonymous.get("/v1/workflows").status()).isEqualTo(401);
        assertThat(anonymous.get("/actuator/health").status()).isEqualTo(200);
    }

    @Test
    void readOnlyTokensCannotWrite() {
        assertThat(as(tenant, "viewer", "relay.read").post("/v1/workflows", WORKFLOW).status()).isEqualTo(403);
    }

    @Test
    void tokensWithoutAValidTenantAreRejected() {
        assertThat(anonymous.as(TestJwt.token(null, "dev-asha", "relay.read")).get("/v1/workflows").status())
            .isEqualTo(401);
        assertThat(anonymous.as(TestJwt.token("Bad Tenant", "dev-asha", "relay.read")).get("/v1/workflows").status())
            .isEqualTo(401);
    }

    @Test
    void workflowsCannotReachForSecrets() {
        assertThat(writer.post("/v1/workflows", """
            {"name": "leak", "steps": [{"id": "a", "type": "AGENT", "agent": "triage",
              "prompt": "{{secret.ORDERS_API_TOKEN}}"}]}
            """).status()).isEqualTo(422);
    }

    @Test
    void unknownToolsAndUngrantedScopesAreRejectedAtSave() {
        assertThat(writer.put("/v1/agents/rogue", Map.of("model", "scripted/x", "systemPrompt", "x",
            "tools", java.util.List.of("does_not_exist"))).status()).isIn(400, 422);
    }
}
