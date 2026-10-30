package com.relayagents.llm.application;

import com.relayagents.llm.domain.ModelPrice;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Prices keyed by full model name ({@code openai/gpt-4o-mini}) or by provider wildcard ({@code scripted/*}).
 */
@ConfigurationProperties("relay.llm")
public record LlmPricing(Map<String, ModelPrice> pricing) {

    public LlmPricing {
        pricing = pricing == null ? Map.of() : Map.copyOf(pricing);
    }

    public ModelPrice priceOf(String model) {
        ModelPrice exact = pricing.get(model);
        if (exact != null) {
            return exact;
        }
        int slash = model.indexOf('/');
        if (slash > 0) {
            ModelPrice wildcard = pricing.get(model.substring(0, slash) + "/*");
            if (wildcard != null) {
                return wildcard;
            }
        }
        return pricing.getOrDefault("default", ModelPrice.FREE);
    }
}
