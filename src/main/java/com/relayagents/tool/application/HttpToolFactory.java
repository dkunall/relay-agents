package com.relayagents.tool.application;

public interface HttpToolFactory {

    Tool create(HttpToolDefinition definition);

    /**
     * Rejects URLs the sandbox would refuse at call time, so mistakes surface at registration.
     */
    void check(HttpToolDefinition definition);
}
