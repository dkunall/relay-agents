package com.relayagents.llm.application;

public interface LlmClient {

    LlmResponse complete(LlmRequest request);
}
