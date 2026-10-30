package com.relayagents.workflow.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class TemplateStrings {

    private TemplateStrings() {
    }

    static List<String> collect(Object tree) {
        List<String> out = new ArrayList<>();
        walk(tree, out);
        return out;
    }

    private static void walk(Object node, List<String> out) {
        switch (node) {
            case String s -> out.add(s);
            case Map<?, ?> map -> map.values().forEach(v -> walk(v, out));
            case List<?> list -> list.forEach(v -> walk(v, out));
            case null, default -> {
            }
        }
    }
}
