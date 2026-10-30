package com.relayagents.llm.application;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class LlmRouter implements LlmClient {

    private final Map<String, LlmProvider> providers;

    public LlmRouter(List<LlmProvider> providers) {
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(LlmProvider::id, Function.identity()));
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        LlmProvider provider = providers.get(request.provider());
        if (provider == null) {
            throw new LlmException("No provider '" + request.provider() + "' for model " + request.model(), false);
        }
        return provider.complete(request.withModel(request.providerModel()));
    }

    public boolean supports(String model) {
        int slash = model.indexOf('/');
        return slash > 0 && slash < model.length() - 1 && providers.containsKey(model.substring(0, slash));
    }

    public Set<String> providerIds() {
        return providers.keySet();
    }
}
