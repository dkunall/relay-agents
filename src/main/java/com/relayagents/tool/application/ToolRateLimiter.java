package com.relayagents.tool.application;

public interface ToolRateLimiter {

    boolean tryAcquire(String tenant, String tool);
}
