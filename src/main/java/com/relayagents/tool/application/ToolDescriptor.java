package com.relayagents.tool.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.relayagents.tool.domain.ToolPolicy;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * @param untrustedOutput the output may contain text written by someone outside the organisation (web pages,
 *                        inbound email), so it taints whatever reads it
 */
public record ToolDescriptor(String name, String description, JsonNode schema, Set<String> scopes,
                             boolean requiresApproval, boolean untrustedOutput, String kind) {

    public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{1,47}");

    public ToolDescriptor {
        Objects.requireNonNull(name, "name");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Tool names must match " + NAME.pattern());
        }
        scopes = Set.copyOf(scopes);
    }

    public boolean hasSideEffects() {
        return ToolPolicy.isWrite(scopes);
    }
}
