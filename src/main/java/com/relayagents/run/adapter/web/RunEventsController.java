package com.relayagents.run.adapter.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.relayagents.run.application.RunEvent;
import com.relayagents.run.application.RunService;
import com.relayagents.shared.domain.Actor;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The event log as JSON pages, or as a server-sent stream that ends when the run does. The stream polls
 * Postgres; at a few viewers per run that is cheaper than wiring Kafka consumers per connection.
 */
@RestController
class RunEventsController {

    private static final Duration POLL = Duration.ofMillis(250);
    private static final Duration STREAM_LIMIT = Duration.ofMinutes(10);

    private final RunService runs;
    private final ExecutorService streams = Executors.newVirtualThreadPerTaskExecutor();

    RunEventsController(RunService runs) {
        this.runs = runs;
    }

    record EventView(long seq, String type, String stepId, JsonNode payload, Instant at) {

        static EventView of(RunEvent e) {
            return new EventView(e.seq(), e.type().name(), e.stepId(), e.payload(), e.createdAt());
        }
    }

    @GetMapping(value = "/v1/runs/{id}/events", produces = MediaType.APPLICATION_JSON_VALUE)
    List<EventView> page(Actor actor, @PathVariable UUID id, @RequestParam(defaultValue = "0") long after,
                         @RequestParam(defaultValue = "500") int limit) {
        return runs.events(actor, id, after, limit).stream().map(EventView::of).toList();
    }

    @GetMapping(value = "/v1/runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(Actor actor, @PathVariable UUID id, @RequestParam(defaultValue = "0") long after) {
        runs.get(actor, id);
        SseEmitter emitter = new SseEmitter(STREAM_LIMIT.toMillis());
        streams.submit(() -> pump(actor, id, after, emitter));
        return emitter;
    }

    private void pump(Actor actor, UUID id, long after, SseEmitter emitter) {
        long cursor = after;
        Instant until = Instant.now().plus(STREAM_LIMIT);
        try {
            while (Instant.now().isBefore(until)) {
                boolean finished = !runs.get(actor, id).status().isActive();
                List<RunEvent> batch = runs.events(actor, id, cursor, 500);
                for (RunEvent event : batch) {
                    emitter.send(SseEmitter.event().id(Long.toString(event.seq())).name(event.type().name())
                        .data(EventView.of(event), MediaType.APPLICATION_JSON));
                    cursor = event.seq();
                }
                if (finished && batch.isEmpty()) {
                    break;
                }
                Thread.sleep(POLL);
            }
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (RuntimeException e) {
            emitter.completeWithError(e);
        }
    }
}
