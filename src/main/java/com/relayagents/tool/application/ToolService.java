package com.relayagents.tool.application;

import com.relayagents.secret.application.SecretResolver;
import com.relayagents.shared.domain.Actor;
import com.relayagents.shared.error.InvalidRequestException;
import com.relayagents.shared.error.StateConflictException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class ToolService {

    private static final Pattern SECRET_REF = Pattern.compile("\\{\\{\\s*secret\\.([A-Za-z0-9_]+)\\s*}}");
    private static final Pattern ANY_PLACEHOLDER = Pattern.compile("\\{\\{[^}]*}}");

    private final ToolRegistry registry;
    private final HttpToolRepository repository;
    private final HttpToolFactory factory;
    private final ArgumentValidator validator;

    public ToolService(ToolRegistry registry, HttpToolRepository repository, HttpToolFactory factory,
                       ArgumentValidator validator) {
        this.registry = registry;
        this.repository = repository;
        this.factory = factory;
        this.validator = validator;
    }

    public HttpToolDefinition registerHttp(Actor actor, HttpToolDefinition definition) {
        if (!ToolDescriptor.NAME.matcher(definition.name()).matches()) {
            throw new InvalidRequestException("invalid-tool", "Tool names must match " + ToolDescriptor.NAME.pattern());
        }
        if (registry.isBuiltin(definition.name())) {
            throw new StateConflictException("tool-exists", "'" + definition.name() + "' is a built-in tool");
        }
        try {
            validator.checkSchema(definition.schema());
            definition.headers().values().forEach(ToolService::checkHeaderTemplate);
            factory.check(definition);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("invalid-tool", e.getMessage());
        }
        HttpToolDefinition owned = new HttpToolDefinition(actor.tenant(), definition.name(), definition.description(),
            definition.schema(), definition.scopes(), definition.requiresApproval(), definition.method(),
            definition.urlTemplate(), definition.headers(), actor.subject());
        if (!repository.insert(owned)) {
            throw new StateConflictException("tool-exists", "Tool '" + definition.name() + "' already exists");
        }
        return owned;
    }

    private static void checkHeaderTemplate(String value) {
        Matcher secrets = SECRET_REF.matcher(value);
        while (secrets.find()) {
            SecretResolver.checkName(secrets.group(1));
        }
        if (ANY_PLACEHOLDER.matcher(SECRET_REF.matcher(value).replaceAll("")).find()) {
            throw new IllegalArgumentException("Header values may only contain {{secret.NAME}} placeholders");
        }
    }
}
