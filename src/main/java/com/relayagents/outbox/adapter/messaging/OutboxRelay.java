package com.relayagents.outbox.adapter.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayagents.outbox.application.OutboxRepository;
import com.relayagents.outbox.application.OutboxRepository.OutboxMessage;
import com.relayagents.shared.messaging.EventEnvelope;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls the outbox and publishes in id order. Rows stay locked (SKIP LOCKED) for the duration of a batch,
 * so several app instances can relay at once without sending the same row twice in normal operation.
 * A crash between send and commit resends the batch; events carry their id and the run sequence number,
 * so consumers can drop duplicates.
 */
@Component
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Clock clock;
    private final int batchSize;

    OutboxRelay(OutboxRepository repository, KafkaTemplate<String, String> kafka, TransactionTemplate transactions,
                ObjectMapper json, Clock clock, MeterRegistry meters,
                @Value("${relay.outbox.batch-size:100}") int batchSize) {
        this.repository = repository;
        this.kafka = kafka;
        this.transactions = transactions;
        this.json = json;
        this.clock = clock;
        this.batchSize = batchSize;
        Gauge.builder("relay.outbox.backlog", repository, OutboxRepository::backlog)
            .description("Outbox rows not yet published")
            .register(meters);
    }

    @Scheduled(fixedDelayString = "${relay.outbox.poll-interval:PT0.2S}")
    void relay() {
        Integer sent;
        do {
            sent = transactions.execute(status -> publishBatch());
        } while (sent != null && sent == batchSize);
    }

    private int publishBatch() {
        List<OutboxMessage> batch = repository.lockUnpublished(batchSize);
        List<Long> published = new ArrayList<>(batch.size());
        for (OutboxMessage message : batch) {
            try {
                kafka.send(message.topic(), message.key(), envelope(message)).get(5, TimeUnit.SECONDS);
                published.add(message.id());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException | TimeoutException e) {
                // Stop at the first failure so later events for the same key are not sent ahead of it.
                log.warn("Outbox publish failed at id {}, retrying next poll: {}", message.id(), e.toString());
                break;
            }
        }
        repository.markPublished(published, clock.instant());
        return published.size();
    }

    private String envelope(OutboxMessage message) {
        try {
            return json.writeValueAsString(new EventEnvelope(message.eventId(), message.eventType(),
                message.createdAt(), json.readTree(message.payloadJson())));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox payload " + message.id() + " is not valid JSON", e);
        }
    }
}
