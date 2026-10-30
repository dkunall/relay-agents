package com.relayagents.run.application.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.relayagents.run.application.StepContext;
import com.relayagents.run.application.StepExecutor;
import com.relayagents.run.application.StepFailedException;
import com.relayagents.run.application.StepOutcome;
import com.relayagents.run.application.ToolCallRejectedException;
import com.relayagents.run.application.ToolGateway;
import com.relayagents.workflow.domain.StepConfig;
import com.relayagents.workflow.domain.StepType;
import org.springframework.stereotype.Component;

@Component
class ToolStepExecutor implements StepExecutor {

    private final ToolGateway tools;

    ToolStepExecutor(ToolGateway tools) {
        this.tools = tools;
    }

    @Override
    public StepType type() {
        return StepType.TOOL;
    }

    @Override
    public StepOutcome.Succeeded execute(StepContext ctx) {
        StepConfig.Tool config = (StepConfig.Tool) ctx.step().config();
        JsonNode arguments = ctx.renderer().tree(config.args());
        try {
            ToolGateway.Result result = tools.call(ctx, 0, config.tool(), arguments, config.grants());
            return new StepOutcome.Succeeded(result.output(), result.untrusted(), null);
        } catch (ToolCallRejectedException e) {
            throw new StepFailedException(e.getMessage(), false);
        }
    }
}
