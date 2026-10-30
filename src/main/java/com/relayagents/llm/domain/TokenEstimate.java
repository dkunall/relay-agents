package com.relayagents.llm.domain;

/**
 * Rough pre-call estimate used to reserve budget: about four characters per token for English text.
 * Providers report the real count afterwards and the reservation is settled against it.
 */
public final class TokenEstimate {

    private TokenEstimate() {
    }

    public static long ofChars(long chars) {
        return Math.ceilDiv(chars, 4) + 4;
    }
}
