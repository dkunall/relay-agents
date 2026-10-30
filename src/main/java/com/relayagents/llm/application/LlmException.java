package com.relayagents.llm.application;

/**
 * {@code retriable} separates "try again later" (timeouts, 429, 5xx) from "this request will never work"
 * (bad model name, 400), so the step retry policy only spends attempts where they can help.
 */
public class LlmException extends RuntimeException {

    private final boolean retriable;

    public LlmException(String message, boolean retriable) {
        super(message);
        this.retriable = retriable;
    }

    public LlmException(String message, boolean retriable, Throwable cause) {
        super(message, cause);
        this.retriable = retriable;
    }

    public boolean retriable() {
        return retriable;
    }
}
