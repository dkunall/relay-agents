package com.relayagents.run.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RunStatusTest {

    @Test
    void runningCanFinishInEveryWay() {
        assertThat(RunStatus.RUNNING.moveTo(RunStatus.SUCCEEDED)).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(RunStatus.RUNNING.moveTo(RunStatus.WAITING_APPROVAL)).isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(RunStatus.RUNNING.moveTo(RunStatus.BUDGET_EXCEEDED)).isEqualTo(RunStatus.BUDGET_EXCEEDED);
    }

    @Test
    void onlyFailedRunsCanBeResumed() {
        assertThat(RunStatus.FAILED.moveTo(RunStatus.RUNNING)).isEqualTo(RunStatus.RUNNING);
        assertThatThrownBy(() -> RunStatus.CANCELLED.moveTo(RunStatus.RUNNING))
            .isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> RunStatus.BUDGET_EXCEEDED.moveTo(RunStatus.RUNNING))
            .isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void waitingRunCannotSucceedWithoutResumingFirst() {
        assertThatThrownBy(() -> RunStatus.WAITING_APPROVAL.moveTo(RunStatus.SUCCEEDED))
            .isInstanceOf(IllegalTransitionException.class);
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"SUCCEEDED", "CANCELLED", "BUDGET_EXCEEDED"})
    void finishedRunsStayFinished(RunStatus status) {
        assertThat(status.isTerminal()).isTrue();
        for (RunStatus target : RunStatus.values()) {
            assertThatThrownBy(() -> status.moveTo(target)).isInstanceOf(IllegalTransitionException.class);
        }
    }
}
