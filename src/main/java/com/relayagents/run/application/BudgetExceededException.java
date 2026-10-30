package com.relayagents.run.application;

import com.relayagents.run.domain.BudgetKind;

public class BudgetExceededException extends RuntimeException {

    private final BudgetKind kind;

    public BudgetExceededException(BudgetKind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public BudgetKind kind() {
        return kind;
    }
}
