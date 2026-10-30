package com.relayagents.run.domain;

import java.util.EnumSet;
import java.util.Set;

public enum StepStatus {
    PENDING,
    READY,
    RUNNING,
    WAITING_APPROVAL,
    SUCCEEDED,
    FAILED,
    SKIPPED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == SKIPPED || this == CANCELLED;
    }

    /**
     * Finished in a way that lets dependents proceed.
     */
    public boolean isSettled() {
        return this == SUCCEEDED || this == SKIPPED;
    }

    public boolean canMoveTo(StepStatus next) {
        return allowedNext().contains(next);
    }

    public StepStatus moveTo(StepStatus next) {
        if (!canMoveTo(next)) {
            throw new IllegalTransitionException("step", name(), next.name());
        }
        return next;
    }

    private Set<StepStatus> allowedNext() {
        return switch (this) {
            case PENDING -> EnumSet.of(READY, SKIPPED, CANCELLED);
            case READY -> EnumSet.of(RUNNING, CANCELLED);
            case RUNNING -> EnumSet.of(SUCCEEDED, FAILED, READY, WAITING_APPROVAL, CANCELLED);
            // Approved: the step runs again and picks up where it paused, from its journal.
            case WAITING_APPROVAL -> EnumSet.of(READY, FAILED, CANCELLED);
            case FAILED -> EnumSet.of(READY);
            case SUCCEEDED, SKIPPED, CANCELLED -> EnumSet.noneOf(StepStatus.class);
        };
    }
}
