package com.relayagents.tool.application;

public interface Tool {

    ToolDescriptor descriptor();

    /**
     * Arguments have already passed the descriptor's schema. Implementations with side effects must pass
     * {@link ToolInvocation#idempotencyKey()} downstream so a retried call does not act twice.
     */
    ToolResult invoke(ToolInvocation invocation);
}
