package com.relayagents.workflow.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.relayagents.workflow.domain.WorkflowDefinition;
import java.time.Instant;
import java.util.UUID;

public record StoredWorkflow(UUID id, int version, String tenant, String name, JsonNode source,
                             WorkflowDefinition definition, String createdBy, Instant createdAt) {
}
