package com.relayagents.run.adapter.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.relayagents.run.application.RunService;
import com.relayagents.shared.domain.Actor;
import com.relayagents.shared.json.Json;
import com.relayagents.shared.messaging.Topics;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Lets other services start runs by publishing to {@code relay.run-requests}. Without an idempotency key in the
 * message the record's partition and offset stand in, which still makes redelivery after a crash harmless.
 */
@Component
class RunRequestListener {

    private static final Logger log = LoggerFactory.getLogger(RunRequestListener.class);
    private static final Pattern TENANT = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

    private final RunService runs;
    private final Json json;

    RunRequestListener(RunService runs, Json json) {
        this.runs = runs;
        this.json = json;
    }

    @KafkaListener(topics = Topics.RUN_REQUESTS, groupId = "relay-run-requests")
    void onRequest(ConsumerRecord<String, String> record) {
        JsonNode message = json.read(record.value());
        String tenant = message.path("tenant").asText();
        if (!TENANT.matcher(tenant).matches()) {
            throw new IllegalArgumentException("run request without a valid tenant");
        }
        String key = message.hasNonNull("idempotencyKey") ? message.get("idempotencyKey").asText()
            : "kafka:" + record.partition() + ":" + record.offset();
        String requestedBy = message.path("requestedBy").asText("unknown");
        Actor actor = Actor.system(tenant, "kafka:" + requestedBy);
        UUID runId = runs.start(actor, UUID.fromString(message.path("workflowId").asText()), null,
            message.get("input"), null, key).run().id();
        log.info("Started run {} from {} offset {}", runId, record.topic(), record.offset());
    }
}
