package com.relayagents.shared.domain;

public final class Clamp {

    private Clamp() {
    }

    public static String text(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "...";
    }
}
