package com.relayagents.run.application.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relayagents.run.application.StepContext;
import com.relayagents.run.application.StepExecutor;
import com.relayagents.run.application.StepOutcome;
import com.relayagents.run.application.TemplateRenderer;
import com.relayagents.shared.json.Json;
import com.relayagents.workflow.domain.StepConfig;
import com.relayagents.workflow.domain.StepType;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class BranchStepExecutor implements StepExecutor {

    private final Json json;

    BranchStepExecutor(Json json) {
        this.json = json;
    }

    @Override
    public StepType type() {
        return StepType.BRANCH;
    }

    @Override
    public StepOutcome.Succeeded execute(StepContext ctx) {
        StepConfig.Branch config = (StepConfig.Branch) ctx.step().config();
        TemplateRenderer renderer = ctx.renderer();
        JsonNode value = renderer.value(config.on());
        String text = value.isValueNode() ? value.asText() : value.toString();
        List<String> targets = config.choose(text);
        ObjectNode output = json.object().put("value", text);
        output.set("targets", json.tree(targets));
        return new StepOutcome.Succeeded(output, renderer.anyUntrusted(config.on()), targets);
    }
}
