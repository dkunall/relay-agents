package com.relayagents.llm.domain;

/**
 * Price per 1,000 tokens in micro-dollars (1 USD = 1,000,000). Integers so budgets add up exactly.
 */
public record ModelPrice(long inputPer1k, long outputPer1k) {

    public static final ModelPrice FREE = new ModelPrice(0, 0);

    public ModelPrice {
        if (inputPer1k < 0 || outputPer1k < 0) {
            throw new IllegalArgumentException("Prices cannot be negative");
        }
    }

    /**
     * Rounds up, so a budget is never undercharged by rounding.
     */
    public long costMicros(long inputTokens, long outputTokens) {
        return ceilDiv(Math.multiplyExact(inputTokens, inputPer1k), 1000)
            + ceilDiv(Math.multiplyExact(outputTokens, outputPer1k), 1000);
    }

    private static long ceilDiv(long value, long divisor) {
        return Math.ceilDiv(value, divisor);
    }
}
