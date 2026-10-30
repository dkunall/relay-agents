package com.relayagents.run.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relayagents.llm.application.LlmClient;
import com.relayagents.llm.application.LlmPricing;
import com.relayagents.llm.application.LlmRequest;
import com.relayagents.llm.application.LlmResponse;
import com.relayagents.llm.application.ResponseCache;
import com.relayagents.llm.domain.ModelPrice;
import com.relayagents.llm.domain.TokenEstimate;
import com.relayagents.run.domain.BudgetKind;
import com.relayagents.run.domain.RunEventType;
import com.relayagents.run.domain.RunMode;
import com.relayagents.shared.crypto.Sha256;
import com.relayagents.shared.json.Json;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The only way a step talks to a model. In order: reuse this step's own journal, serve a replay from the
 * source run, try the exact-match cache, then reserve budget, call, and settle what was really spent.
 */
@Component
public class ModelGateway {

    private final RunEngine engine;
    private final RunRepository runs;
    private final LlmClient llm;
    private final ResponseCache cache;
    private final LlmPricing pricing;
    private final Json json;
    private final Duration cacheTtl;

    public ModelGateway(RunEngine engine, RunRepository runs, LlmClient llm, ResponseCache cache, LlmPricing pricing,
                        Json json, @Value("${relay.llm.cache-ttl:PT1H}") Duration cacheTtl) {
        this.engine = engine;
        this.runs = runs;
        this.llm = llm;
        this.cache = cache;
        this.pricing = pricing;
        this.json = json;
        this.cacheTtl = cacheTtl;
    }

    public LlmResponse call(StepContext ctx, int iteration, LlmRequest request) {
        String key = JournalKeys.llm(iteration);
        String hash = requestHash(request);

        var own = ctx.journal().get(key).filter(e -> hash.equals(e.path("requestHash").asText()));
        if (own.isPresent()) {
            return response(own.get());
        }

        if (ctx.mode() == RunMode.REPLAY_RECORDED) {
            JsonNode recorded = ctx.source().get(key)
                .orElseThrow(() -> new ReplayDivergedException("source run has no model call " + key
                    + " in step '" + ctx.step().id() + "'"));
            LlmResponse response = response(recorded);
            boolean diverged = !hash.equals(recorded.path("requestHash").asText());
            ObjectNode payload = payload(key, iteration, hash, request.model(), response, 0, 0, 0);
            payload.put("replayed", true).put("diverged", diverged);
            engine.journal(ctx.fence(), RunEventType.LLM_CALLED, payload);
            return response;
        }

        boolean cacheable = ctx.step().cache() && request.temperature() == 0.0 && ctx.mode() == RunMode.LIVE;
        String cacheKey = ctx.tenant() + ":" + hash;
        if (cacheable) {
            var hit = cache.get(cacheKey);
            if (hit.isPresent()) {
                ObjectNode payload = payload(key, iteration, hash, request.model(), hit.get(), 0, 0, 0);
                payload.put("cached", true);
                engine.journal(ctx.fence(), RunEventType.LLM_CALLED, payload);
                return hit.get();
            }
        }

        Duration timeout = ctx.callTimeout();
        ModelPrice price = pricing.priceOf(request.model());
        long promptEstimate = TokenEstimate.ofChars(request.promptChars());
        long reservedTokens = promptEstimate + request.maxTokens();
        long reservedCost = price.costMicros(promptEstimate, request.maxTokens());
        if (!runs.reserve(ctx.run().id(), reservedTokens, reservedCost)) {
            throw overBudget(ctx, reservedTokens, reservedCost);
        }

        long started = System.nanoTime();
        LlmResponse response;
        try {
            response = llm.complete(new LlmRequest(request.model(), request.messages(), request.tools(),
                request.temperature(), request.maxTokens(), request.params(), timeout));
        } catch (RuntimeException e) {
            runs.settle(ctx.run().id(), reservedTokens, reservedCost, 0, 0);
            throw e;
        }
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        long tokens = response.totalTokens();
        long cost = price.costMicros(response.promptTokens(), response.completionTokens());

        ObjectNode payload = payload(key, iteration, hash, request.model(), response, tokens, cost, latencyMs);
        if (!engine.settleModelCall(ctx.fence(), reservedTokens, reservedCost, tokens, cost, payload)) {
            throw new FencedOutException(ctx.fence());
        }
        if (cacheable) {
            cache.put(cacheKey, response, cacheTtl);
        }
        return response;
    }

    String requestHash(LlmRequest request) {
        ObjectNode canonical = json.object()
            .put("model", request.model())
            .put("temperature", request.temperature())
            .put("maxTokens", request.maxTokens());
        canonical.set("messages", json.tree(request.messages()));
        canonical.set("tools", json.tree(request.tools()));
        canonical.set("params", request.params());
        return Sha256.hex(json.canonical(canonical));
    }

    private BudgetExceededException overBudget(StepContext ctx, long tokens, long cost) {
        RunRecord now = runs.findById(ctx.run().id()).orElseThrow();
        boolean tokenCap = now.spentTokens() + now.reservedTokens() + tokens > now.maxTokens();
        BudgetKind kind = tokenCap ? BudgetKind.TOKENS : BudgetKind.COST;
        String detail = tokenCap
            ? "model call needs up to " + tokens + " tokens, " + (now.maxTokens() - now.spentTokens()
                - now.reservedTokens()) + " left"
            : "model call may cost " + cost + " micros, " + (now.maxCostMicros() - now.spentCostMicros()
                - now.reservedCostMicros()) + " left";
        return new BudgetExceededException(kind, detail);
    }

    private ObjectNode payload(String key, int iteration, String hash, String model, LlmResponse response,
                               long tokens, long cost, long latencyMs) {
        ObjectNode payload = json.object()
            .put("key", key)
            .put("iteration", iteration)
            .put("requestHash", hash)
            .put("model", model);
        payload.set("response", json.tree(response));
        payload.put("tokens", tokens).put("costMicros", cost).put("latencyMs", latencyMs)
            .put("cached", false).put("replayed", false).put("diverged", false);
        return payload;
    }

    private LlmResponse response(JsonNode entry) {
        return json.convert(entry.path("response"), LlmResponse.class);
    }
}
