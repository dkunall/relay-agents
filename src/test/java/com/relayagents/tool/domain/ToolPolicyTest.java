package com.relayagents.tool.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolPolicyTest {

    @Test
    void missingScopeIsDeniedEvenWhenApprovalWouldBeAsked() {
        assertThat(ToolPolicy.decide(Set.of("payments.write"), Set.of("data.read"), true, false))
            .isInstanceOf(ToolPolicy.Deny.class);
    }

    @Test
    void approvalToolsAlwaysWait() {
        assertThat(ToolPolicy.decide(Set.of("payments.write"), Set.of("payments.write"), true, false))
            .isInstanceOf(ToolPolicy.NeedsApproval.class);
    }

    @Test
    void writesAfterUntrustedContentWaitButReadsDoNot() {
        assertThat(ToolPolicy.decide(Set.of("tickets.write"), Set.of("tickets.write"), false, true))
            .isInstanceOf(ToolPolicy.NeedsApproval.class);
        assertThat(ToolPolicy.decide(Set.of("tickets.write"), Set.of("tickets.write"), false, false))
            .isInstanceOf(ToolPolicy.Allow.class);
        assertThat(ToolPolicy.decide(Set.of("data.read"), Set.of("data.read"), false, true))
            .isInstanceOf(ToolPolicy.Allow.class);
    }
}
