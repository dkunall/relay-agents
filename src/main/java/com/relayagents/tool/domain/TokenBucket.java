package com.relayagents.tool.domain;

/**
 * Reference implementation of the per-tenant, per-tool rate limit. The Redis adapter runs the same
 * arithmetic in a Lua script so that concurrent instances share one bucket; this class exists to pin the
 * behaviour down in unit tests.
 */
public final class TokenBucket {

    private final long capacity;
    private final double refillPerMilli;
    private double tokens;
    private long updatedAtMillis;

    public TokenBucket(long capacity, double refillPerSecond, long nowMillis) {
        if (capacity <= 0 || refillPerSecond <= 0) {
            throw new IllegalArgumentException("capacity and refill rate must be positive");
        }
        this.capacity = capacity;
        this.refillPerMilli = refillPerSecond / 1000.0;
        this.tokens = capacity;
        this.updatedAtMillis = nowMillis;
    }

    public boolean tryTake(long nowMillis) {
        refill(nowMillis);
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    public double available(long nowMillis) {
        refill(nowMillis);
        return tokens;
    }

    private void refill(long nowMillis) {
        long elapsed = Math.max(0, nowMillis - updatedAtMillis);
        tokens = Math.min(capacity, tokens + elapsed * refillPerMilli);
        updatedAtMillis = Math.max(updatedAtMillis, nowMillis);
    }
}
