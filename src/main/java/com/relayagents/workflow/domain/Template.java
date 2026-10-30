package com.relayagents.workflow.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A string with {@code {{path}}} placeholders. Paths are deliberately tiny: {@code input.a.b},
 * {@code steps.<id>.output[.field...]} and {@code secret.NAME}. No expressions, so a template can never
 * execute anything.
 */
public final class Template {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");

    public sealed interface Part {
    }

    public record Literal(String text) implements Part {
    }

    public record InputRef(List<String> path) implements Part {
    }

    public record StepRef(String stepId, List<String> path) implements Part {
    }

    public record SecretRef(String name) implements Part {
    }

    private final String source;
    private final List<Part> parts;

    private Template(String source, List<Part> parts) {
        this.source = source;
        this.parts = List.copyOf(parts);
    }

    public static Template parse(String source) {
        List<Part> parts = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(source);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                parts.add(new Literal(source.substring(last, m.start())));
            }
            parts.add(reference(m.group(1)));
            last = m.end();
        }
        if (last < source.length()) {
            parts.add(new Literal(source.substring(last)));
        }
        return new Template(source, parts);
    }

    private static Part reference(String path) {
        List<String> segments = Arrays.asList(path.split("\\."));
        return switch (segments.getFirst()) {
            case "input" -> new InputRef(segments.subList(1, segments.size()));
            case "steps" -> {
                if (segments.size() < 3 || !segments.get(2).equals("output")) {
                    throw new WorkflowValidationException("'{{" + path + "}}' must look like steps.<id>.output[.field]");
                }
                yield new StepRef(segments.get(1), segments.subList(3, segments.size()));
            }
            case "secret" -> {
                if (segments.size() != 2) {
                    throw new WorkflowValidationException("'{{" + path + "}}' must look like secret.NAME");
                }
                yield new SecretRef(segments.get(1));
            }
            default -> throw new WorkflowValidationException("Unknown placeholder '{{" + path + "}}'");
        };
    }

    public List<Part> parts() {
        return parts;
    }

    public String source() {
        return source;
    }

    public boolean isSingleReference() {
        return parts.size() == 1 && !(parts.getFirst() instanceof Literal);
    }

    public List<String> referencedSteps() {
        return parts.stream().filter(StepRef.class::isInstance).map(p -> ((StepRef) p).stepId()).distinct().toList();
    }

    public boolean usesSecrets() {
        return parts.stream().anyMatch(SecretRef.class::isInstance);
    }
}
