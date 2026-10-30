package com.relayagents.run.application;

import com.relayagents.workflow.domain.StepType;

/**
 * Runs one attempt of one kind of step. Anything other than success is signalled by an exception, which
 * {@link StepRunner} maps to an outcome.
 */
public interface StepExecutor {

    StepType type();

    StepOutcome.Succeeded execute(StepContext ctx);
}
