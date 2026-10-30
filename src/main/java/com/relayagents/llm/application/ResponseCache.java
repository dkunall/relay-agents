package com.relayagents.llm.application;

import java.time.Duration;
import java.util.Optional;

/**
 * Exact-match cache: the key is a hash of everything that affects the answer. Implementations must fail
 * open (return empty) when the backing store is down; a cache outage should cost money, not availability.
 */
public interface ResponseCache {

    Optional<LlmResponse> get(String key);

    void put(String key, LlmResponse response, Duration ttl);
}
