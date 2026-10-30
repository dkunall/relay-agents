package com.relayagents.llm.adapter.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayagents.llm.application.LlmResponse;
import com.relayagents.llm.application.ResponseCache;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
class RedisResponseCache implements ResponseCache {

    private static final Logger log = LoggerFactory.getLogger(RedisResponseCache.class);
    private static final String PREFIX = "relay:llm:";

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    RedisResponseCache(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    @Override
    public Optional<LlmResponse> get(String key) {
        try {
            String value = redis.opsForValue().get(PREFIX + key);
            return value == null ? Optional.empty() : Optional.of(json.readValue(value, LlmResponse.class));
        } catch (Exception e) {
            log.warn("LLM cache read failed, calling the model instead: {}", e.toString());
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, LlmResponse response, Duration ttl) {
        try {
            redis.opsForValue().set(PREFIX + key, json.writeValueAsString(response), ttl);
        } catch (Exception e) {
            log.warn("LLM cache write failed: {}", e.toString());
        }
    }
}
