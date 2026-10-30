package com.relayagents.tool.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.relayagents.shared.crypto.Sha256;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * JSON Schema (2020-12) checks on tool arguments. Compiled schemas are cached by content hash, which
 * is safe because a schema with a given hash never changes.
 */
@Component
public class ArgumentValidator {

    private static final int MAX_CACHED = 1_000;

    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private final Map<String, JsonSchema> compiled = new ConcurrentHashMap<>();

    public List<String> validate(JsonNode schema, JsonNode arguments) {
        Set<ValidationMessage> errors = compile(schema).validate(arguments);
        return errors.stream().map(ValidationMessage::getMessage).sorted().toList();
    }

    /**
     * Fails if the schema itself is malformed or does not describe an object.
     */
    public void checkSchema(JsonNode schema) {
        if (schema == null || !schema.isObject() || !"object".equals(schema.path("type").asText())) {
            throw new IllegalArgumentException("Tool schemas must be JSON objects with \"type\": \"object\"");
        }
        try {
            compile(schema);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Tool schema does not compile: " + e.getMessage(), e);
        }
    }

    private JsonSchema compile(JsonNode schema) {
        String key = Sha256.hex(schema.toString());
        JsonSchema cached = compiled.get(key);
        if (cached != null) {
            return cached;
        }
        if (compiled.size() >= MAX_CACHED) {
            compiled.clear();
        }
        JsonSchema fresh = factory.getSchema(schema);
        compiled.put(key, fresh);
        return fresh;
    }
}
