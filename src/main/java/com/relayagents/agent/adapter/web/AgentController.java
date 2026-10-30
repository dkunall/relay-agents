package com.relayagents.agent.adapter.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.relayagents.agent.application.AgentService;
import com.relayagents.agent.domain.AgentDefinition;
import com.relayagents.shared.domain.Actor;
import com.relayagents.shared.error.InvalidRequestException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/agents")
class AgentController {

    private final AgentService agents;

    AgentController(AgentService agents) {
        this.agents = agents;
    }

    @PutMapping("/{name}")
    AgentDefinition save(Actor actor, @PathVariable String name, @Valid @RequestBody SaveAgent body) {
        AgentDefinition agent;
        try {
            agent = new AgentDefinition(name, body.model(), body.systemPrompt(),
                body.tools() == null ? Set.of() : body.tools(), body.scopes() == null ? Set.of() : body.scopes(),
                body.params(), body.memoryWindow() == null ? 20 : body.memoryWindow());
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("invalid-agent", e.getMessage());
        }
        return agents.save(actor, agent);
    }

    @GetMapping
    List<AgentDefinition> list(Actor actor) {
        return agents.list(actor);
    }

    @GetMapping("/{name}")
    AgentDefinition get(Actor actor, @PathVariable String name) {
        return agents.get(actor, name);
    }

    record SaveAgent(@NotBlank String model, @NotBlank String systemPrompt, Set<String> tools, Set<String> scopes,
                     JsonNode params, Integer memoryWindow) {
    }
}
