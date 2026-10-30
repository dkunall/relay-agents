package com.relayagents.llm.application;

/**
 * One backend. Models are addressed as {@code <provider>/<model>}, for example {@code scripted/triage} or
 * {@code openai/gpt-4o-mini}; the provider receives the part after the slash.
 */
public interface LlmProvider extends LlmClient {

    String id();
}
