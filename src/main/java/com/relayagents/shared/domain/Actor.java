package com.relayagents.shared.domain;

import java.util.Objects;
import java.util.Set;

/**
 * Who is calling and on behalf of which tenant. Every repository query is scoped by {@code tenant}.
 */
public record Actor(String subject, String tenant, Set<String> scopes) {

    public static final String APPROVE = "relay.approve";

    public Actor {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(tenant, "tenant");
        scopes = Set.copyOf(scopes);
    }

    public static Actor system(String tenant, String subject) {
        return new Actor(subject, tenant, Set.of());
    }

    public boolean has(String scope) {
        return scopes.contains(scope);
    }
}
