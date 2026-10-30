package com.relayagents.shared.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper so callers do not each handle {@link JsonProcessingException}, which cannot happen for trees
 * we built ourselves.
 */
@Component
public class Json {

    private final ObjectMapper mapper;
    private final ObjectMapper sorted;

    public Json(ObjectMapper mapper) {
        this.mapper = mapper;
        this.sorted = mapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public ObjectNode object() {
        return mapper.createObjectNode();
    }

    public JsonNode tree(Object value) {
        return mapper.valueToTree(value);
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Value is not serialisable to JSON", e);
        }
    }

    public JsonNode read(String text) {
        try {
            return mapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Not valid JSON", e);
        }
    }

    public <T> T convert(JsonNode node, Class<T> type) {
        try {
            return mapper.treeToValue(node, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON does not match " + type.getSimpleName() + ": " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Stable text for hashing: object keys sorted, no whitespace.
     */
    public String canonical(JsonNode node) {
        try {
            return sorted.writeValueAsString(mapper.treeToValue(node, Object.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
