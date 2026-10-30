package com.relayagents.run.application;

/**
 * Keys that identify a journal entry within a step, so a re-run or replay finds the same call again.
 */
public final class JournalKeys {

    public static final String RETRIEVE = "retrieve";

    private JournalKeys() {
    }

    public static String llm(int iteration) {
        return "llm:" + iteration;
    }

    public static String tool(int callIndex) {
        return "tool:" + callIndex;
    }

    public static String approval(int iteration) {
        return "approval:" + iteration;
    }
}
