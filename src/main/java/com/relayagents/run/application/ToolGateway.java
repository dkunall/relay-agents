package com.relayagents.run.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relayagents.run.domain.BudgetKind;
import com.relayagents.run.domain.RunEventType;
import com.relayagents.run.domain.RunMode;
import com.relayagents.secret.application.Redactor;
import com.relayagents.shared.json.Json;
import com.relayagents.tool.application.ArgumentValidator;
import com.relayagents.tool.application.Tool;
import com.relayagents.tool.application.ToolDescriptor;
import com.relayagents.tool.application.ToolException;
import com.relayagents.tool.application.ToolInvocation;
import com.relayagents.tool.application.ToolRateLimiter;
import com.relayagents.tool.application.ToolRegistry;
import com.relayagents.tool.application.ToolResult;
import com.relayagents.tool.domain.ToolPolicy;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The only way a step calls a tool. Policy and schema come first, then the journal and replay, then the
 * approval gate, and only then budget, rate limit and the call itself.
 */
@Component
public class ToolGateway {

    public record Result(JsonNode output, boolean untrusted) {
    }

    private final ToolRegistry registry;
    private final ArgumentValidator validator;
    private final ToolRateLimiter rateLimiter;
    private final RunRepository runs;
    private final RunEngine engine;
    private final Json json;

    public ToolGateway(ToolRegistry registry, ArgumentValidator validator, ToolRateLimiter rateLimiter,
                       RunRepository runs, RunEngine engine, Json json) {
        this.registry = registry;
        this.validator = validator;
        this.rateLimiter = rateLimiter;
        this.runs = runs;
        this.engine = engine;
        this.json = json;
    }

    public Result call(StepContext ctx, int callIndex, String name, JsonNode arguments, Set<String> granted) {
        Tool tool = registry.find(ctx.tenant(), name)
            .orElseThrow(() -> new ToolCallRejectedException("unknown tool '" + name + "'"));
        ToolDescriptor descriptor = tool.descriptor();
        ToolPolicy.Decision decision = ToolPolicy.decide(descriptor.scopes(), granted, descriptor.requiresApproval(),
            ctx.tainted());
        if (decision instanceof ToolPolicy.Deny deny) {
            throw new ToolCallRejectedException("tool '" + name + "' denied: " + deny.reason());
        }
        List<String> errors = validator.validate(descriptor.schema(), arguments);
        if (!errors.isEmpty()) {
            throw new ToolCallRejectedException("invalid arguments for '" + name + "': " + String.join("; ", errors));
        }

        String key = JournalKeys.tool(callIndex);
        var own = ctx.journal().get(key).filter(e -> arguments.equals(e.path("arguments")));
        if (own.isPresent()) {
            return seen(ctx, descriptor, own.get().path("output"));
        }

        boolean fromSource = ctx.mode() == RunMode.REPLAY_RECORDED
            || ctx.mode() == RunMode.REPLAY_LIVE && descriptor.hasSideEffects();
        if (fromSource) {
            JsonNode recorded = ctx.source().get(key)
                .orElseThrow(() -> new ReplayDivergedException("source run has no call " + key + " to '" + name
                    + "' in step '" + ctx.step().id() + "'"));
            JsonNode output = recorded.path("output");
            ObjectNode payload = payload(key, descriptor, arguments, output, 0);
            payload.put("replayed", true).put("diverged", !arguments.equals(recorded.path("arguments")));
            engine.journal(ctx.fence(), RunEventType.TOOL_CALLED, payload);
            return seen(ctx, descriptor, output);
        }

        if (decision instanceof ToolPolicy.NeedsApproval needs) {
            awaitApproval(ctx, callIndex, descriptor, arguments, needs.reason());
        }

        String idempotencyKey = ctx.run().id() + ":" + ctx.step().id() + ":" + callIndex;
        var executed = runs.findToolResult(idempotencyKey);
        if (executed.isPresent()) {
            engine.journal(ctx.fence(), RunEventType.TOOL_CALLED,
                payload(key, descriptor, arguments, executed.get(), 0));
            return seen(ctx, descriptor, executed.get());
        }

        // Throttle first: a throttled attempt is retried and must not spend the run's tool-call budget.
        if (!rateLimiter.tryAcquire(ctx.tenant(), name)) {
            throw new ToolException("rate limit for '" + name + "' reached", true);
        }
        if (!runs.consumeToolCall(ctx.run().id())) {
            throw new BudgetExceededException(BudgetKind.TOOL_CALLS, "tool call limit " + ctx.run().maxToolCalls()
                + " reached");
        }

        long started = System.nanoTime();
        ToolResult result = tool.invoke(new ToolInvocation(ctx.tenant(), arguments, idempotencyKey,
            ctx.callTimeout()));
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        JsonNode output = result.usedSecrets().isEmpty()
            ? result.output()
            : json.read(Redactor.redact(json.write(result.output()), result.usedSecrets()));

        runs.insertToolInvocation(idempotencyKey, ctx.run().id(), ctx.step().id(), callIndex, name, arguments, output);
        engine.journal(ctx.fence(), RunEventType.TOOL_CALLED, payload(key, descriptor, arguments, output, latencyMs));
        return seen(ctx, descriptor, output);
    }

    private void awaitApproval(StepContext ctx, int callIndex, ToolDescriptor descriptor, JsonNode arguments,
                               String reason) {
        UUID runId = ctx.run().id();
        String stepId = ctx.step().id();
        var existing = runs.findApproval(runId, stepId, callIndex);
        if (existing.isEmpty()) {
            ApprovalRecord request = new ApprovalRecord(UUID.randomUUID(), runId, stepId, callIndex, ctx.tenant(),
                ApprovalRecord.Kind.TOOL, descriptor.name(), arguments, reason, ApprovalRecord.Status.PENDING,
                ctx.run().createdBy(), null, null, null, null);
            runs.insertApproval(request);
            existing = runs.findApproval(runId, stepId, callIndex);
        }
        ApprovalRecord approval = existing.orElseThrow();
        switch (approval.status()) {
            case PENDING -> throw new ApprovalPendingException(approval.id());
            case APPROVED -> {
            }
            case REJECTED, EXPIRED -> throw new ToolException("call to '" + descriptor.name() + "' was "
                + approval.status().name().toLowerCase() + " by " + approval.decidedBy(), false);
        }
    }

    private static Result seen(StepContext ctx, ToolDescriptor descriptor, JsonNode output) {
        if (descriptor.untrustedOutput()) {
            ctx.taint();
        }
        return new Result(output, descriptor.untrustedOutput());
    }

    private ObjectNode payload(String key, ToolDescriptor descriptor, JsonNode arguments, JsonNode output,
                               long latencyMs) {
        ObjectNode payload = json.object().put("key", key).put("tool", descriptor.name());
        payload.set("arguments", arguments);
        payload.set("output", output);
        payload.put("untrusted", descriptor.untrustedOutput())
            .put("sideEffects", descriptor.hasSideEffects())
            .put("latencyMs", latencyMs)
            .put("replayed", false)
            .put("diverged", false);
        return payload;
    }
}
