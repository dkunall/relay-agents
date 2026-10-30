package com.relayagents.tool.adapter.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.relayagents.shared.domain.Actor;
import com.relayagents.tool.application.HttpToolDefinition;
import com.relayagents.tool.application.ToolDescriptor;
import com.relayagents.tool.application.ToolRegistry;
import com.relayagents.tool.application.ToolService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/tools")
class ToolController {

    private final ToolRegistry registry;
    private final ToolService service;

    ToolController(ToolRegistry registry, ToolService service) {
        this.registry = registry;
        this.service = service;
    }

    @GetMapping
    List<ToolView> list(Actor actor) {
        return registry.list(actor.tenant()).stream().map(ToolView::of).toList();
    }

    @PostMapping("/http")
    @ResponseStatus(HttpStatus.CREATED)
    ToolView registerHttp(Actor actor, @Valid @RequestBody RegisterHttpTool body) {
        HttpToolDefinition saved = service.registerHttp(actor, new HttpToolDefinition(actor.tenant(), body.name(),
            body.description(), body.schema(), body.scopes() == null ? Set.of() : body.scopes(),
            Boolean.TRUE.equals(body.requiresApproval()), body.method(), body.urlTemplate(),
            body.headers() == null ? Map.of() : body.headers(), actor.subject()));
        return registry.find(actor.tenant(), saved.name()).map(t -> ToolView.of(t.descriptor())).orElseThrow();
    }

    record RegisterHttpTool(@NotBlank String name, @NotBlank @Size(max = 500) String description,
                            @NotNull JsonNode schema, Set<@Pattern(regexp = "[a-z][a-z0-9_.-]{1,62}") String> scopes,
                            Boolean requiresApproval, @Pattern(regexp = "GET|POST") String method,
                            @NotBlank @Size(max = 500) String urlTemplate, Map<String, String> headers) {
    }

    record ToolView(String name, String description, JsonNode schema, Set<String> scopes, boolean requiresApproval,
                    boolean untrustedOutput, String kind) {

        static ToolView of(ToolDescriptor d) {
            return new ToolView(d.name(), d.description(), d.schema(), d.scopes(), d.requiresApproval(),
                d.untrustedOutput(), d.kind());
        }
    }
}
