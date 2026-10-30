package com.relayagents.run.application;

public class StepFailedException extends RuntimeException {

    private final boolean retriable;

    public StepFailedException(String message, boolean retriable) {
        super(message);
        this.retriable = retriable;
    }

    public boolean retriable() {
        return retriable;
    }
}
