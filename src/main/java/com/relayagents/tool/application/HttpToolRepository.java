package com.relayagents.tool.application;

import java.util.List;
import java.util.Optional;

public interface HttpToolRepository {

    /**
     * @return false when the tenant already has a tool with this name
     */
    boolean insert(HttpToolDefinition definition);

    Optional<HttpToolDefinition> find(String tenant, String name);

    List<HttpToolDefinition> list(String tenant);
}
