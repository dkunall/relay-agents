package com.relayagents.tool.application;

public class ToolException extends RuntimeException {

    private final boolean retriable;

    public ToolException(String message, boolean retriable) {
        super(message);
        this.retriable = retriable;
    }

    public ToolException(String message, boolean retriable, Throwable cause) {
        super(message, cause);
        this.retriable = retriable;
    }

    public boolean retriable() {
        return retriable;
    }
}
