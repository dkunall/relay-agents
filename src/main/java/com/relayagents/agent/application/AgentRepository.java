package com.relayagents.agent.application;

import com.relayagents.agent.domain.AgentDefinition;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface AgentRepository {

    void upsert(String tenant, AgentDefinition agent, String updatedBy);

    Optional<AgentDefinition> find(String tenant, String name);

    Map<String, AgentDefinition> findAll(String tenant, Collection<String> names);

    List<AgentDefinition> list(String tenant);
}
