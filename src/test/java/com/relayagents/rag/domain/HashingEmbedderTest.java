package com.relayagents.rag.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HashingEmbedderTest {

    private final HashingEmbedder embedder = new HashingEmbedder(256);

    @Test
    void isDeterministicAndRanksRelatedTextCloser() {
        float[] query = embedder.embed("refund for a damaged parcel");
        assertThat(embedder.embed("refund for a damaged parcel")).containsExactly(query);

        double related = cosine(query, embedder.embed("Damaged parcels qualify for a full refund."));
        double unrelated = cosine(query, embedder.embed("Office hours are nine to five on weekdays."));
        assertThat(related).isGreaterThan(unrelated);
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / Math.sqrt(na * nb);
    }
}
