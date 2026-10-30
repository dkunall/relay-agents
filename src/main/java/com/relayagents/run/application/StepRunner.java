package com.relayagents.run.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.relayagents.agent.domain.AgentDefinition;
import com.relayagents.llm.application.LlmException;
import com.relayagents.run.domain.RunEventType;
import com.relayagents.shared.json.Json;
import com.relayagents.tool.application.ToolException;
import com.relayagents.workflow.application.WorkflowService;
import com.relayagents.workflow.domain.StepDefinition;
import com.relayagents.workflow.domain.StepType;
import com.relayagents.workflow.domain.WorkflowDefinition;
import java.time.Clock;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Builds a step's context from the event log and turns whatever the executor did into an outcome.
 */
@Component
public class StepRunner {

    private static final Logger log = LoggerFactory.getLogger(StepRunner.class);

    static final List<RunEventType> JOURNAL = List.of(RunEventType.LLM_CALLED, RunEventType.TOOL_CALLED,
        RunEventType.RETRIEVED, RunEventType.APPROVAL_DECIDED);

    private final RunRepository runs;
    private final WorkflowService workflows;
    private final RunEngine engine;
    private final Map<StepType, StepExecutor> executors = new EnumMap<>(StepType.class);
    private final Json json;
    private final Clock clock;

    public StepRunner(RunRepository runs, WorkflowService workflows, RunEngine engine, List<StepExecutor> executors,
                      Json json, Clock clock) {
        this.runs = runs;
        this.workflows = workflows;
        this.engine = engine;
        executors.forEach(e -> this.executors.put(e.type(), e));
        this.json = json;
        this.clock = clock;
    }

    /**
     * @throws FencedOutException if the worker lost the step; there is nothing to report then
     */
    public StepOutcome run(StepFence fence) {
        try {
            StepContext ctx = context(fence);
            return executors.get(ctx.step().type()).execute(ctx);
        } catch (FencedOutException e) {
            throw e;
        } catch (BudgetExceededException e) {
            return new StepOutcome.BudgetExceeded(e.kind(), e.getMessage());
        } catch (ApprovalPendingException e) {
            return new StepOutcome.WaitingApproval(e.approvalId());
        } catch (StepFailedException e) {
            return new StepOutcome.Failed(e.getMessage(), e.retriable());
        } catch (ToolCallRejectedException | TemplateRenderer.MissingValueException e) {
            return new StepOutcome.Failed(e.getMessage(), false);
        } catch (ReplayDivergedException e) {
            return new StepOutcome.Failed("replay diverged: " + e.getMessage(), false);
        } catch (LlmException e) {
            return new StepOutcome.Failed("model call failed: " + e.getMessage(), e.retriable());
        } catch (ToolException e) {
            return new StepOutcome.Failed("tool failed: " + e.getMessage(), e.retriable());
        } catch (RuntimeException e) {
            log.warn("Step {}/{} threw unexpectedly", fence.runId(), fence.stepId(), e);
            return new StepOutcome.Failed(e.getClass().getSimpleName() + ": " + e.getMessage(), true);
        }
    }

    StepContext context(StepFence fence) {
        RunRecord run = runs.findById(fence.runId()).orElseThrow();
        WorkflowDefinition workflow = workflows.get(run.tenant(), run.workflowId(), run.workflowVersion())
            .definition();
        StepDefinition step = workflow.dag().step(fence.stepId());

        Map<String, StepOutput> outputs = engine.stepOutputs(run.id());
        Set<String> ancestors = workflow.dag().ancestors(step.id());
        Map<String, StepOutput> upstream = new HashMap<>();
        outputs.forEach((id, output) -> {
            if (ancestors.contains(id)) {
                upstream.put(id, output);
            }
        });
        boolean tainted = upstream.values().stream().anyMatch(StepOutput::untrusted);

        StepJournal journal = StepJournal.of(runs.stepEvents(run.id(), step.id(), JOURNAL));
        StepJournal source = run.mode().isReplay()
            ? StepJournal.of(runs.stepEvents(run.sourceRunId(), step.id(), JOURNAL))
            : StepJournal.empty();
        return new StepContext(run, fence, step, agents(run), upstream, tainted, journal, source, clock);
    }

    private Map<String, AgentDefinition> agents(RunRecord run) {
        Map<String, AgentDefinition> agents = new HashMap<>();
        runs.eventsOfTypes(run.id(), List.of(RunEventType.RUN_STARTED)).stream().findFirst().ifPresent(started -> {
            for (JsonNode agent : started.payload().path("agents")) {
                AgentDefinition definition = json.convert(agent, AgentDefinition.class);
                agents.put(definition.name(), definition);
            }
        });
        return agents;
    }
}
