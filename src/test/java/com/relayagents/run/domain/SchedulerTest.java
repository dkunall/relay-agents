package com.relayagents.run.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.relayagents.support.Workflows;
import com.relayagents.workflow.domain.Dag;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchedulerTest {

    private static final Dag BRANCHING = Workflows.parse("""
        {"name": "triage", "steps": [
          {"id": "classify", "type": "TOOL", "tool": "calculator", "args": {"expression": "1+1"}},
          {"id": "route", "type": "BRANCH", "needs": ["classify"], "on": "{{steps.classify.output.value}}",
           "cases": {"refund": ["refund"]}, "default": ["answer"]},
          {"id": "refund", "type": "APPROVAL", "needs": ["route"], "reason": "refund"},
          {"id": "answer", "type": "TOOL", "needs": ["route"], "tool": "calculator", "args": {"expression": "2"}},
          {"id": "notify", "type": "TOOL", "needs": ["refund", "answer"], "tool": "calculator",
           "args": {"expression": "3"}},
          {"id": "audit", "type": "TOOL", "needs": ["refund"], "tool": "calculator", "args": {"expression": "4"}}
        ]}
        """).dag();

    @Test
    void rootsStartImmediately() {
        Map<String, StepStatus> changes = Scheduler.advance(BRANCHING, pending(), Map.of());
        assertThat(changes).containsExactly(Map.entry("classify", StepStatus.READY));
    }

    @Test
    void untakenArmIsSkippedAndTheSkipCascades() {
        Map<String, StepStatus> statuses = pending();
        statuses.put("classify", StepStatus.SUCCEEDED);
        statuses.put("route", StepStatus.SUCCEEDED);

        Map<String, StepStatus> changes = Scheduler.advance(BRANCHING, statuses, Map.of("route", List.of("answer")));

        assertThat(changes).containsEntry("answer", StepStatus.READY)
            .containsEntry("refund", StepStatus.SKIPPED)
            .containsEntry("audit", StepStatus.SKIPPED)
            .doesNotContainKey("notify");
    }

    @Test
    void joinRunsWhenAnyArmFedIt() {
        Map<String, StepStatus> statuses = pending();
        statuses.putAll(Map.of("classify", StepStatus.SUCCEEDED, "route", StepStatus.SUCCEEDED,
            "answer", StepStatus.SUCCEEDED, "refund", StepStatus.SKIPPED, "audit", StepStatus.SKIPPED));

        assertThat(Scheduler.advance(BRANCHING, statuses, Map.of("route", List.of("answer"))))
            .containsExactly(Map.entry("notify", StepStatus.READY));
    }

    @Test
    void failedNeedHoldsDependentsBack() {
        Map<String, StepStatus> statuses = pending();
        statuses.put("classify", StepStatus.FAILED);
        assertThat(Scheduler.advance(BRANCHING, statuses, Map.of())).isEmpty();
        assertThat(Scheduler.allSettled(statuses)).isFalse();
    }

    private static Map<String, StepStatus> pending() {
        Map<String, StepStatus> statuses = new HashMap<>();
        BRANCHING.topologicalOrder().forEach(id -> statuses.put(id, StepStatus.PENDING));
        return statuses;
    }
}
