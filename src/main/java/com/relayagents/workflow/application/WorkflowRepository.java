package com.relayagents.workflow.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowRepository {

    record Row(UUID id, int version, String tenant, String name, JsonNode definition, String createdBy,
               Instant createdAt) {
    }

    /**
     * @return false if that (id, version) already exists, which means a concurrent publish won
     */
    boolean insert(UUID id, int version, String tenant, String name, JsonNode definition, String createdBy);

    Optional<Row> find(String tenant, UUID id, int version);

    Optional<Row> findLatest(String tenant, UUID id);

    List<Row> listLatest(String tenant);
}
