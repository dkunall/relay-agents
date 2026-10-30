package com.relayagents.agent.application;

import com.relayagents.agent.domain.AgentDefinition;
import com.relayagents.llm.application.LlmRouter;
import com.relayagents.shared.domain.Actor;
import com.relayagents.shared.error.InvalidRequestException;
import com.relayagents.shared.error.NotFoundException;
import com.relayagents.tool.application.ToolRegistry;
import java.util.List;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

@Service
public class AgentService {

    private final AgentRepository repository;
    private final ToolRegistry tools;
    private final LlmRouter llm;

    public AgentService(AgentRepository repository, ToolRegistry tools, LlmRouter llm) {
        this.repository = repository;
        this.tools = tools;
        this.llm = llm;
    }

    public AgentDefinition save(Actor actor, AgentDefinition agent) {
        if (!llm.supports(agent.model())) {
            throw new InvalidRequestException("invalid-agent", "Model must be <provider>/<name> with provider one of "
                + new TreeSet<>(llm.providerIds()));
        }
        List<String> unknown = agent.tools().stream()
            .filter(t -> tools.find(actor.tenant(), t).isEmpty())
            .sorted()
            .toList();
        if (!unknown.isEmpty()) {
            throw new InvalidRequestException("invalid-agent", "Unknown tools " + unknown);
        }
        repository.upsert(actor.tenant(), agent, actor.subject());
        return agent;
    }

    public AgentDefinition get(Actor actor, String name) {
        return repository.find(actor.tenant(), name)
            .orElseThrow(() -> new NotFoundException("agent-not-found", "No agent '" + name + "'"));
    }

    public List<AgentDefinition> list(Actor actor) {
        return repository.list(actor.tenant());
    }
}
