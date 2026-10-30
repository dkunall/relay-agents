package com.relayagents.shared.domain;

import java.util.Objects;
import java.util.UUID;

public record RunId(UUID value) {

    public RunId {
        Objects.requireNonNull(value, "value");
    }

    public static RunId random() {
        return new RunId(UUID.randomUUID());
    }

    public static RunId parse(String raw) {
        return new RunId(UUID.fromString(raw));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
