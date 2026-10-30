package com.relayagents.run.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.relayagents.workflow.domain.Template;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Fills {@code {{...}}} placeholders from the run input and earlier step outputs.
 */
public final class TemplateRenderer {

    private final JsonNode input;
    private final Map<String, StepOutput> outputs;

    public TemplateRenderer(JsonNode input, Map<String, StepOutput> outputs) {
        this.input = input;
        this.outputs = outputs;
    }

    public static class MissingValueException extends RuntimeException {
        MissingValueException(String message) {
            super(message);
        }
    }

    /**
     * Renders to text. With {@code fenceUntrusted}, values from untrusted steps are wrapped in
     * {@code <untrusted>} tags so the system prompt can tell the model to treat them as data.
     */
    public String text(String source, boolean fenceUntrusted) {
        StringBuilder out = new StringBuilder();
        for (Template.Part part : Template.parse(source).parts()) {
            if (part instanceof Template.Literal literal) {
                out.append(literal.text());
                continue;
            }
            String value = asText(resolve(part));
            if (fenceUntrusted && isUntrusted(part)) {
                out.append(fence(value));
            } else {
                out.append(value);
            }
        }
        return out.toString();
    }

    /**
     * Renders to JSON: a string that is exactly one placeholder becomes the referenced value itself, so a
     * tool argument can receive a number or an object, not its string form.
     */
    public JsonNode value(String source) {
        Template template = Template.parse(source);
        if (template.isSingleReference()) {
            return resolve(template.parts().getFirst());
        }
        return TextNode.valueOf(text(source, false));
    }

    public JsonNode tree(Object node) {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        return switch (node) {
            case null -> nodes.nullNode();
            case String s -> value(s);
            case Map<?, ?> map -> {
                ObjectNode object = nodes.objectNode();
                map.forEach((k, v) -> object.set(String.valueOf(k), tree(v)));
                yield object;
            }
            case List<?> list -> {
                ArrayNode array = nodes.arrayNode();
                list.forEach(v -> array.add(tree(v)));
                yield array;
            }
            case Boolean b -> nodes.booleanNode(b);
            case Integer i -> nodes.numberNode(i);
            case Long l -> nodes.numberNode(l);
            case Double d -> nodes.numberNode(d);
            case Number n -> nodes.numberNode(new BigDecimal(n.toString()));
            default -> TextNode.valueOf(node.toString());
        };
    }

    public boolean anyUntrusted(String source) {
        return Template.parse(source).parts().stream().anyMatch(this::isUntrusted);
    }

    static String fence(String value) {
        return "<untrusted>\n" + value.replace("</untrusted>", "<\\/untrusted>") + "\n</untrusted>";
    }

    private boolean isUntrusted(Template.Part part) {
        return part instanceof Template.StepRef ref && outputs.containsKey(ref.stepId())
            && outputs.get(ref.stepId()).untrusted();
    }

    private JsonNode resolve(Template.Part part) {
        return switch (part) {
            case Template.InputRef ref -> walk(input, ref.path(), "input");
            case Template.StepRef ref -> {
                StepOutput output = outputs.get(ref.stepId());
                if (output == null) {
                    throw new MissingValueException("steps." + ref.stepId() + " has no output (skipped?)");
                }
                yield walk(output.value(), ref.path(), "steps." + ref.stepId() + ".output");
            }
            case Template.SecretRef ref -> throw new IllegalStateException("secret." + ref.name() + " in a prompt");
            case Template.Literal literal -> TextNode.valueOf(literal.text());
        };
    }

    private static JsonNode walk(JsonNode root, List<String> path, String name) {
        JsonNode node = root;
        StringBuilder at = new StringBuilder(name);
        for (String segment : path) {
            at.append('.').append(segment);
            node = node.isArray() && segment.chars().allMatch(Character::isDigit)
                ? node.path(Integer.parseInt(segment))
                : node.path(segment);
            if (node.isMissingNode()) {
                throw new MissingValueException(at + " is missing");
            }
        }
        return node;
    }

    private static String asText(JsonNode node) {
        return node.isValueNode() ? node.asText() : node.toString();
    }
}
