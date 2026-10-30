package com.relayagents.trigger.adapter.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.relayagents.run.application.RunRecord;
import com.relayagents.run.application.RunService;
import com.relayagents.run.domain.BudgetLimits;
import com.relayagents.shared.domain.Actor;
import com.relayagents.trigger.application.Trigger;
import com.relayagents.trigger.application.TriggerService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TriggerController {

    private final TriggerService triggers;

    TriggerController(TriggerService triggers) {
        this.triggers = triggers;
    }

    record CreateBody(UUID workflowId, Trigger.Type type, String secretName, String cron, JsonNode input,
                      Long maxTokens, Long maxCostMicros, Integer maxToolCalls, Long maxDurationSeconds) {

        BudgetLimits budget() {
            BudgetLimits d = RunService.DEFAULT_BUDGET;
            return new BudgetLimits(maxTokens == null ? d.maxTokens() : maxTokens,
                maxCostMicros == null ? d.maxCostMicros() : maxCostMicros,
                maxToolCalls == null ? d.maxToolCalls() : maxToolCalls,
                maxDurationSeconds == null ? d.maxDuration() : Duration.ofSeconds(maxDurationSeconds));
        }
    }

    record TriggerView(UUID id, UUID workflowId, String type, String secretName, String cron, boolean enabled,
                       Instant nextFireAt, String webhookPath) {

        static TriggerView of(Trigger t) {
            return new TriggerView(t.id(), t.workflowId(), t.type().name(), t.secretName(), t.cron(), t.enabled(),
                t.nextFireAt(), t.type() == Trigger.Type.WEBHOOK ? "/v1/hooks/" + t.id() : null);
        }
    }

    @PostMapping("/v1/triggers")
    @ResponseStatus(HttpStatus.CREATED)
    TriggerView create(Actor actor, @RequestBody CreateBody body) {
        if (body.type() == null || body.workflowId() == null) {
            throw new IllegalArgumentException("workflowId and type are required");
        }
        return TriggerView.of(triggers.create(actor, body.workflowId(), body.type(), body.secretName(), body.cron(),
            body.input(), body.budget()));
    }

    @GetMapping("/v1/triggers")
    List<TriggerView> list(Actor actor) {
        return triggers.list(actor).stream().map(TriggerView::of).toList();
    }

    @PostMapping("/v1/triggers/{id}/disable")
    void disable(Actor actor, @PathVariable UUID id) {
        triggers.setEnabled(actor, id, false);
    }

    @PostMapping("/v1/triggers/{id}/enable")
    void enable(Actor actor, @PathVariable UUID id) {
        triggers.setEnabled(actor, id, true);
    }

    @PostMapping("/v1/hooks/{id}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> deliver(@PathVariable UUID id,
                                @RequestHeader(name = "X-Relay-Timestamp", required = false) String timestamp,
                                @RequestHeader(name = "X-Relay-Signature", required = false) String signature,
                                @RequestHeader(name = "X-Relay-Delivery", required = false) String delivery,
                                @RequestBody byte[] body) {
        RunRecord run = triggers.deliver(id, timestamp, signature, delivery, body);
        return Map.of("runId", run.id(), "status", run.status().name());
    }
}
