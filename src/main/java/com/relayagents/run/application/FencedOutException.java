package com.relayagents.run.application;

/**
 * This worker no longer owns the step: its lease expired and another worker took over, or the run was
 * cancelled. The worker drops what it was doing.
 */
public class FencedOutException extends RuntimeException {

    public FencedOutException(StepFence fence) {
        super("lost ownership of " + fence.runId() + "/" + fence.stepId() + " attempt " + fence.attempt());
    }
}
