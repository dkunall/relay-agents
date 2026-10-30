package com.relayagents.workflow.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.relayagents.agent.application.AgentService;
import com.relayagents.rag.application.KnowledgeRepository;
import com.relayagents.shared.domain.Actor;
import com.relayagents.shared.error.NotFoundException;
import com.relayagents.shared.error.StateConflictException;
import com.relayagents.tool.application.ToolDescriptor;
import com.relayagents.tool.application.ToolRegistry;
import com.relayagents.workflow.domain.StepConfig;
import com.relayagents.workflow.domain.StepDefinition;
import com.relayagents.workflow.domain.WorkflowDefinition;
import com.relayagents.workflow.domain.WorkflowValidationException;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class WorkflowService {

    private static final int CACHE_LIMIT = 500;

    private final WorkflowRepository repository;
    private final AgentService agents;
    private final ToolRegistry tools;
    private final KnowledgeRepository knowledge;
    // Versions are immutable, so a parsed definition never goes stale.
    private final Map<String, WorkflowDefinition> parsed = new ConcurrentHashMap<>();

    public WorkflowService(WorkflowRepository repository, AgentService agents, ToolRegistry tools,
                           KnowledgeRepository knowledge) {
        this.repository = repository;
        this.agents = agents;
        this.tools = tools;
        this.knowledge = knowledge;
    }

    public StoredWorkflow create(Actor actor, JsonNode source) {
        return publish(actor, UUID.randomUUID(), 1, source);
    }

    public StoredWorkflow publishVersion(Actor actor, UUID id, JsonNode source) {
        WorkflowRepository.Row latest = repository.findLatest(actor.tenant(), id)
            .orElseThrow(() -> notFound(id));
        return publish(actor, id, latest.version() + 1, source);
    }

    public StoredWorkflow get(String tenant, UUID id, Integer version) {
        WorkflowRepository.Row row = (version == null ? repository.findLatest(tenant, id) : repository.find(tenant, id, version))
            .orElseThrow(() -> notFound(id));
        return toStored(row);
    }

    public List<StoredWorkflow> list(Actor actor) {
        return repository.listLatest(actor.tenant()).stream().map(this::toStored).toList();
    }

    private StoredWorkflow publish(Actor actor, UUID id, int version, JsonNode source) {
        WorkflowDefinition definition = WorkflowParser.parse(source);
        checkReferences(actor, definition);
        if (!repository.insert(id, version, actor.tenant(), definition.name(), source, actor.subject())) {
            throw new StateConflictException("version-conflict",
                "Version " + version + " of workflow " + id + " was published concurrently; retry");
        }
        return get(actor.tenant(), id, version);
    }

    private void checkReferences(Actor actor, WorkflowDefinition definition) {
        for (StepDefinition step : definition.steps()) {
            switch (step.config()) {
                case StepConfig.Agent agent -> {
                    try {
                        agents.get(actor, agent.agent());
                    } catch (NotFoundException e) {
                        throw new WorkflowValidationException("Step '" + step.id() + "' uses unknown agent '"
                            + agent.agent() + "'");
                    }
                }
                case StepConfig.Tool tool -> {
                    ToolDescriptor descriptor = tools.find(actor.tenant(), tool.tool())
                        .orElseThrow(() -> new WorkflowValidationException("Step '" + step.id() + "' uses unknown tool '"
                            + tool.tool() + "'"))
                        .descriptor();
                    TreeSet<String> missing = new TreeSet<>(descriptor.scopes());
                    missing.removeAll(tool.grants());
                    if (!missing.isEmpty()) {
                        throw new WorkflowValidationException("Step '" + step.id() + "' must grant " + missing
                            + " to call " + tool.tool());
                    }
                }
                case StepConfig.Retrieve retrieve -> knowledge.findKnowledgeBase(actor.tenant(), retrieve.knowledgeBase())
                    .orElseThrow(() -> new WorkflowValidationException("Step '" + step.id()
                        + "' reads unknown knowledge base " + retrieve.knowledgeBase()));
                case StepConfig.Branch ignored -> {
                }
                case StepConfig.Approval ignored -> {
                }
            }
        }
    }

    private StoredWorkflow toStored(WorkflowRepository.Row row) {
        String key = row.id() + ":" + row.version();
        WorkflowDefinition definition = parsed.get(key);
        if (definition == null) {
            definition = WorkflowParser.parse(row.definition());
            if (parsed.size() >= CACHE_LIMIT) {
                parsed.clear();
            }
            parsed.put(key, definition);
        }
        return new StoredWorkflow(row.id(), row.version(), row.tenant(), row.name(), row.definition(), definition,
            row.createdBy(), row.createdAt());
    }

    private static NotFoundException notFound(UUID id) {
        return new NotFoundException("workflow-not-found", "No workflow " + id);
    }
}
