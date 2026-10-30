package com.relayagents.tool.domain;

import java.util.Set;
import java.util.TreeSet;

/**
 * Whether a call may go ahead. Two rules on top of plain scope checks:
 * a tool marked {@code requiresApproval} always waits for a human, and a tool with a write scope waits
 * for a human whenever untrusted content (a web page, a retrieved document) is in the step's context.
 * The second rule is the main defence against prompt injection turning into actions.
 */
public final class ToolPolicy {

    private ToolPolicy() {
    }

    public sealed interface Decision {
    }

    public record Allow() implements Decision {
    }

    public record NeedsApproval(String reason) implements Decision {
    }

    public record Deny(String reason) implements Decision {
    }

    public static Decision decide(Set<String> toolScopes, Set<String> granted, boolean requiresApproval,
                                  boolean tainted) {
        Set<String> missing = new TreeSet<>(toolScopes);
        missing.removeAll(granted);
        if (!missing.isEmpty()) {
            return new Deny("missing scopes " + missing);
        }
        if (requiresApproval) {
            return new NeedsApproval("tool requires approval");
        }
        if (tainted && isWrite(toolScopes)) {
            return new NeedsApproval("write tool called after untrusted content entered the context");
        }
        return new Allow();
    }

    public static boolean isWrite(Set<String> scopes) {
        return scopes.stream().anyMatch(s -> s.endsWith(".write"));
    }
}
