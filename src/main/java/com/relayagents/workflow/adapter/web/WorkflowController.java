package com.relayagents.workflow.adapter.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.relayagents.shared.domain.Actor;
import com.relayagents.workflow.application.StoredWorkflow;
import com.relayagents.workflow.application.WorkflowService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/workflows")
class WorkflowController {

    private final WorkflowService workflows;

    WorkflowController(WorkflowService workflows) {
        this.workflows = workflows;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    WorkflowView create(Actor actor, @RequestBody JsonNode body) {
        return WorkflowView.of(workflows.create(actor, body));
    }

    @PostMapping("/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    WorkflowView publish(Actor actor, @PathVariable UUID id, @RequestBody JsonNode body) {
        return WorkflowView.of(workflows.publishVersion(actor, id, body));
    }

    @GetMapping
    List<WorkflowView> list(Actor actor) {
        return workflows.list(actor).stream().map(WorkflowView::of).toList();
    }

    @GetMapping("/{id}")
    WorkflowView get(Actor actor, @PathVariable UUID id, @RequestParam(required = false) Integer version) {
        return WorkflowView.of(workflows.get(actor.tenant(), id, version));
    }

    record WorkflowView(UUID id, int version, String name, int steps, int depth, List<String> order,
                        JsonNode definition, String createdBy, Instant createdAt) {

        static WorkflowView of(StoredWorkflow w) {
            return new WorkflowView(w.id(), w.version(), w.name(), w.definition().dag().size(),
                w.definition().dag().depth(), w.definition().dag().topologicalOrder(), w.source(), w.createdBy(),
                w.createdAt());
        }
    }
}
