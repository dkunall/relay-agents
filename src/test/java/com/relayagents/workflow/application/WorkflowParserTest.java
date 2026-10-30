package com.relayagents.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.relayagents.support.Workflows;
import com.relayagents.workflow.domain.StepConfig;
import com.relayagents.workflow.domain.WorkflowDefinition;
import com.relayagents.workflow.domain.WorkflowValidationException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class WorkflowParserTest {

    @Test
    void parsesStepsRetryAndOrder() {
        WorkflowDefinition workflow = Workflows.parse("""
            {"name": "w", "output": "{{steps.b.output}}", "steps": [
              {"id": "b", "type": "AGENT", "needs": ["a"], "agent": "helper", "prompt": "Summarise {{steps.a.output}}",
               "retry": {"maxAttempts": 5, "initialBackoffMs": 10, "maxBackoffMs": 100}, "timeoutMs": 2000},
              {"id": "a", "type": "TOOL", "tool": "calculator", "args": {"expression": "{{input.expr}}"}}
            ]}
            """);

        assertThat(workflow.dag().topologicalOrder()).containsExactly("a", "b");
        assertThat(workflow.dag().step("b").retry().maxAttempts()).isEqualTo(5);
        assertThat(workflow.dag().step("b").timeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(workflow.dag().step("b").config()).isInstanceOf(StepConfig.Agent.class);
    }

    @Test
    void rejectsTyposInsteadOfIgnoringThem() {
        assertThatThrownBy(() -> Workflows.parse("""
            {"name": "w", "steps": [{"id": "a", "type": "TOOL", "tool": "calculator", "need": ["x"]}]}
            """)).isInstanceOf(WorkflowValidationException.class).hasMessageContaining("unknown field 'need'");
    }

    @Test
    void rejectsCycles() {
        assertThatThrownBy(() -> Workflows.parse("""
            {"name": "w", "steps": [
              {"id": "a", "type": "APPROVAL", "needs": ["b"], "reason": "x"},
              {"id": "b", "type": "APPROVAL", "needs": ["a"], "reason": "y"}]}
            """)).isInstanceOf(WorkflowValidationException.class).hasMessageContaining("cycle");
    }

    @Test
    void stepsCanOnlyReadTheirAncestors() {
        assertThatThrownBy(() -> Workflows.parse("""
            {"name": "w", "steps": [
              {"id": "a", "type": "APPROVAL", "reason": "{{steps.b.output}}"},
              {"id": "b", "type": "APPROVAL", "reason": "y"}]}
            """)).isInstanceOf(WorkflowValidationException.class).hasMessageContaining("not one of its ancestors");
    }

    @Test
    void secretsCannotBeInterpolatedIntoPrompts() {
        assertThatThrownBy(() -> Workflows.parse("""
            {"name": "w", "steps": [{"id": "a", "type": "AGENT", "agent": "x", "prompt": "key {{secret.API_KEY}}"}]}
            """)).isInstanceOf(WorkflowValidationException.class).hasMessageContaining("secret");
    }

    @Test
    void branchTargetsMustDependOnTheBranch() {
        assertThatThrownBy(() -> Workflows.parse("""
            {"name": "w", "steps": [
              {"id": "r", "type": "BRANCH", "on": "{{input.kind}}", "cases": {"x": ["a"]}},
              {"id": "a", "type": "APPROVAL", "reason": "y"}]}
            """)).isInstanceOf(WorkflowValidationException.class).hasMessageContaining("does not list it in needs");
    }
}
