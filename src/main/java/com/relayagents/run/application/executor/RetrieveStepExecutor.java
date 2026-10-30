package com.relayagents.run.application.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relayagents.rag.application.KnowledgeService;
import com.relayagents.rag.application.RetrievedChunk;
import com.relayagents.rag.domain.RetrievalMode;
import com.relayagents.run.application.JournalKeys;
import com.relayagents.run.application.ReplayDivergedException;
import com.relayagents.run.application.RunEngine;
import com.relayagents.run.application.StepContext;
import com.relayagents.run.application.StepExecutor;
import com.relayagents.run.application.StepOutcome;
import com.relayagents.run.domain.RunEventType;
import com.relayagents.run.domain.RunMode;
import com.relayagents.shared.json.Json;
import com.relayagents.workflow.domain.StepConfig;
import com.relayagents.workflow.domain.StepType;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * Retrieved text is always untrusted: anyone who can get a document into a knowledge base can put
 * instructions in it.
 */
@Component
class RetrieveStepExecutor implements StepExecutor {

    private final KnowledgeService knowledge;
    private final RunEngine engine;
    private final Json json;

    RetrieveStepExecutor(KnowledgeService knowledge, RunEngine engine, Json json) {
        this.knowledge = knowledge;
        this.engine = engine;
        this.json = json;
    }

    @Override
    public StepType type() {
        return StepType.RETRIEVE;
    }

    @Override
    public StepOutcome.Succeeded execute(StepContext ctx) {
        StepConfig.Retrieve config = (StepConfig.Retrieve) ctx.step().config();
        String query = ctx.renderer().text(config.query(), false);

        var own = ctx.journal().get(JournalKeys.RETRIEVE).filter(e -> query.equals(e.path("query").asText()));
        if (own.isPresent()) {
            return new StepOutcome.Succeeded(own.get().path("output"), true, null);
        }
        JsonNode output;
        boolean replayed = ctx.mode() == RunMode.REPLAY_RECORDED;
        if (replayed) {
            output = ctx.source().get(JournalKeys.RETRIEVE)
                .orElseThrow(() -> new ReplayDivergedException("source run has no retrieval in step '"
                    + ctx.step().id() + "'"))
                .path("output");
        } else {
            output = search(ctx, config, query);
        }
        ObjectNode payload = json.object().put("key", JournalKeys.RETRIEVE).put("query", query)
            .put("replayed", replayed);
        payload.set("output", output);
        engine.journal(ctx.fence(), RunEventType.RETRIEVED, payload);
        return new StepOutcome.Succeeded(output, true, null);
    }

    private JsonNode search(StepContext ctx, StepConfig.Retrieve config, String query) {
        ObjectNode output = json.object().put("query", query);
        ArrayNode chunks = output.putArray("chunks");
        for (RetrievedChunk chunk : knowledge.search(ctx.tenant(), config.knowledgeBase(), query, config.k(),
            RetrievalMode.HYBRID)) {
            ObjectNode node = chunks.addObject()
                .put("title", chunk.title())
                .put("text", chunk.text())
                .put("score", chunk.score());
            node.set("flags", json.tree(new TreeSet<>(chunk.flags())));
        }
        return output;
    }
}
