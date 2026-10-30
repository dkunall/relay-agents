package com.relayagents.run.application;

import com.relayagents.agent.domain.AgentDefinition;
import com.relayagents.run.domain.BudgetKind;
import com.relayagents.run.domain.RunMode;
import com.relayagents.workflow.domain.StepDefinition;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/**
 * Everything an executor may read while running one step attempt.
 */
public final class StepContext {

    private final RunRecord run;
    private final StepFence fence;
    private final StepDefinition step;
    private final Map<String, AgentDefinition> agents;
    private final Map<String, StepOutput> upstream;
    private final StepJournal journal;
    private final StepJournal source;
    private final Clock clock;
    private boolean tainted;

    public StepContext(RunRecord run, StepFence fence, StepDefinition step, Map<String, AgentDefinition> agents,
                       Map<String, StepOutput> upstream, boolean tainted, StepJournal journal, StepJournal source,
                       Clock clock) {
        this.run = run;
        this.fence = fence;
        this.step = step;
        this.agents = agents;
        this.upstream = upstream;
        this.tainted = tainted;
        this.journal = journal;
        this.source = source;
        this.clock = clock;
    }

    public RunRecord run() {
        return run;
    }

    public StepFence fence() {
        return fence;
    }

    public StepDefinition step() {
        return step;
    }

    public RunMode mode() {
        return run.mode();
    }

    public String tenant() {
        return run.tenant();
    }

    public AgentDefinition agent(String name) {
        AgentDefinition agent = agents.get(name);
        if (agent == null) {
            throw new IllegalStateException("agent '" + name + "' missing from the run snapshot");
        }
        return agent;
    }

    public TemplateRenderer renderer() {
        return new TemplateRenderer(run.input(), upstream);
    }

    public StepJournal journal() {
        return journal;
    }

    public StepJournal source() {
        return source;
    }

    /**
     * True once anything untrusted is in this step's context: an untrusted upstream output, or a tool
     * result this step received. Write tools need approval from then on.
     */
    public boolean tainted() {
        return tainted;
    }

    public void taint() {
        tainted = true;
    }

    /**
     * The time a call may take: the step timeout, cut short by the run deadline.
     */
    public Duration callTimeout() {
        Duration remaining = Duration.between(clock.instant(), run.deadline());
        if (remaining.isNegative() || remaining.isZero()) {
            throw new BudgetExceededException(BudgetKind.WALL_CLOCK, "run deadline " + run.deadline() + " passed");
        }
        return remaining.compareTo(step.timeout()) < 0 ? remaining : step.timeout();
    }
}
