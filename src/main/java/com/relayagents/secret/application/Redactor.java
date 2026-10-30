package com.relayagents.secret.application;

import java.util.Comparator;
import java.util.Map;

public final class Redactor {

    private static final int MIN_LENGTH = 4;

    private Redactor() {
    }

    /**
     * Replaces every occurrence of a secret value with its name. Longest values first, so a secret that
     * contains another is not half-redacted. Very short values are skipped because replacing them would
     * shred unrelated text and they are not meaningful secrets anyway.
     */
    public static String redact(String text, Map<String, String> secretsByName) {
        if (text == null || secretsByName.isEmpty()) {
            return text;
        }
        String result = text;
        var entries = secretsByName.entrySet().stream()
            .filter(e -> e.getValue() != null && e.getValue().length() >= MIN_LENGTH)
            .sorted(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getValue().length()).reversed())
            .toList();
        for (var entry : entries) {
            result = result.replace(entry.getValue(), "[redacted:" + entry.getKey() + "]");
        }
        return result;
    }
}
