package com.relayagents.run.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * There is no QUEUED state: a run is RUNNING from creation, and the queue lives one level down in the
 * step table, where ready steps wait for a worker.
 */
public enum RunStatus {
    RUNNING,
    WAITING_APPROVAL,
    SUCCEEDED,
    FAILED,
    BUDGET_EXCEEDED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == BUDGET_EXCEEDED || this == CANCELLED;
    }

    public boolean isActive() {
        return this == RUNNING || this == WAITING_APPROVAL;
    }

    public boolean canMoveTo(RunStatus next) {
        return allowedNext().contains(next);
    }

    public RunStatus moveTo(RunStatus next) {
        if (!canMoveTo(next)) {
            throw new IllegalTransitionException("run", name(), next.name());
        }
        return next;
    }

    private Set<RunStatus> allowedNext() {
        return switch (this) {
            case RUNNING -> EnumSet.of(WAITING_APPROVAL, SUCCEEDED, FAILED, BUDGET_EXCEEDED, CANCELLED);
            case WAITING_APPROVAL -> EnumSet.of(RUNNING, FAILED, BUDGET_EXCEEDED, CANCELLED);
            // A failed run can be resumed once the cause (a flaky dependency, a bad secret) is fixed.
            case FAILED -> EnumSet.of(RUNNING);
            case SUCCEEDED, BUDGET_EXCEEDED, CANCELLED -> EnumSet.noneOf(RunStatus.class);
        };
    }
}
