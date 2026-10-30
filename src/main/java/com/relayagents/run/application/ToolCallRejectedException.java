package com.relayagents.run.application;

/**
 * The call never reached the tool: unknown name, missing scope or bad arguments. An agent gets the
 * message back and can correct itself; a tool step fails.
 */
public class ToolCallRejectedException extends RuntimeException {

    public ToolCallRejectedException(String message) {
        super(message);
    }
}
