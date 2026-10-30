package com.relayagents.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayagents.workflow.application.WorkflowParser;
import com.relayagents.workflow.domain.WorkflowDefinition;

public final class Workflows {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Workflows() {
    }

    public static WorkflowDefinition parse(String json) {
        try {
            return WorkflowParser.parse(MAPPER.readTree(json));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
