package com.relayagents.rag.domain;

public interface Embedder {

    String model();

    int dimensions();

    /**
     * @return an L2-normalised vector, or all zeros when the text has no words
     */
    float[] embed(String text);
}
