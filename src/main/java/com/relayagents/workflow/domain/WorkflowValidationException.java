package com.relayagents.workflow.domain;

import com.relayagents.shared.error.BusinessRuleException;

public class WorkflowValidationException extends BusinessRuleException {

    public WorkflowValidationException(String message) {
        super("invalid-workflow", message);
    }
}
