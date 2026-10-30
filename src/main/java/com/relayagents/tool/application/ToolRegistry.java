package com.relayagents.tool.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Built-in tools are code; HTTP tools are rows. Built-in names win, and registration refuses to shadow them.
 */
@Component
public class ToolRegistry {

    private final Map<String, Tool> builtins;
    private final HttpToolRepository httpTools;
    private final HttpToolFactory httpToolFactory;

    public ToolRegistry(List<Tool> builtins, HttpToolRepository httpTools, HttpToolFactory httpToolFactory) {
        this.builtins = builtins.stream()
            .collect(Collectors.toUnmodifiableMap(t -> t.descriptor().name(), Function.identity()));
        this.httpTools = httpTools;
        this.httpToolFactory = httpToolFactory;
    }

    public Optional<Tool> find(String tenant, String name) {
        Tool builtin = builtins.get(name);
        if (builtin != null) {
            return Optional.of(builtin);
        }
        return httpTools.find(tenant, name).map(httpToolFactory::create);
    }

    public boolean isBuiltin(String name) {
        return builtins.containsKey(name);
    }

    public List<ToolDescriptor> list(String tenant) {
        List<ToolDescriptor> all = new ArrayList<>();
        builtins.values().forEach(t -> all.add(t.descriptor()));
        httpTools.list(tenant).forEach(d -> all.add(httpToolFactory.create(d).descriptor()));
        all.sort(Comparator.comparing(ToolDescriptor::name));
        return all;
    }
}
