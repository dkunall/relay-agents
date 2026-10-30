package com.relayagents.trigger.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.relayagents.run.domain.BudgetLimits;
import java.time.Instant;
import java.util.UUID;

public record Trigger(UUID id, String tenant, UUID workflowId, Type type, String secretName, String cron,
                      JsonNode input, BudgetLimits budget, boolean enabled, Instant nextFireAt, String createdBy,
                      Instant createdAt) {

    public enum Type { WEBHOOK, CRON }
}
