package com.relayagents.tool.adapter.redis;

import com.relayagents.tool.application.ToolRateLimiter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Token bucket shared by every instance, one bucket per tenant and tool, same arithmetic as
 * {@link com.relayagents.tool.domain.TokenBucket}. Uses Redis server time so instance clocks do not matter.
 * Fails open: a Redis outage should not stop every agent.
 */
@Component
class RedisToolRateLimiter implements ToolRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisToolRateLimiter.class);

    private static final DefaultRedisScript<Long> TAKE = new DefaultRedisScript<>("""
        local capacity = tonumber(ARGV[1])
        local refill_per_ms = tonumber(ARGV[2])
        local t = redis.call('TIME')
        local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
        local state = redis.call('HMGET', KEYS[1], 'tokens', 'at')
        local tokens = tonumber(state[1]) or capacity
        local at = tonumber(state[2]) or now
        tokens = math.min(capacity, tokens + math.max(0, now - at) * refill_per_ms)
        local allowed = 0
        if tokens >= 1 then
          tokens = tokens - 1
          allowed = 1
        end
        redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'at', tostring(now))
        redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / refill_per_ms) + 1000)
        return allowed
        """, Long.class);

    private final StringRedisTemplate redis;
    private final long capacity;
    private final double refillPerSecond;

    RedisToolRateLimiter(StringRedisTemplate redis,
                         @Value("${relay.tools.rate-limit.capacity:20}") long capacity,
                         @Value("${relay.tools.rate-limit.refill-per-second:10}") double refillPerSecond) {
        this.redis = redis;
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
    }

    @Override
    public boolean tryAcquire(String tenant, String tool) {
        try {
            Long allowed = redis.execute(TAKE, List.of("relay:rl:" + tenant + ":" + tool),
                Long.toString(capacity), Double.toString(refillPerSecond / 1000.0));
            return allowed == null || allowed == 1L;
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable, allowing call: {}", e.toString());
            return true;
        }
    }
}
