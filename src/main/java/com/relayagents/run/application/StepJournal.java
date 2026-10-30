package com.relayagents.run.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The calls a step already made, keyed by their position in the step ({@code llm:0}, {@code tool:17}). A
 * step that runs again after a retry, a lost lease or an approval walks the same path and picks results
 * up from here instead of paying for them twice. The latest entry per key wins.
 */
public final class StepJournal {

    private static final StepJournal EMPTY = new StepJournal(Map.of());

    private final Map<String, JsonNode> entries;

    private StepJournal(Map<String, JsonNode> entries) {
        this.entries = entries;
    }

    public static StepJournal empty() {
        return EMPTY;
    }

    public static StepJournal of(List<RunEvent> events) {
        Map<String, JsonNode> entries = new HashMap<>();
        for (RunEvent event : events) {
            String key = event.payload().path("key").asText(null);
            if (key != null) {
                entries.put(key, event.payload());
            }
        }
        return new StepJournal(entries);
    }

    public Optional<JsonNode> get(String key) {
        return Optional.ofNullable(entries.get(key));
    }
}
