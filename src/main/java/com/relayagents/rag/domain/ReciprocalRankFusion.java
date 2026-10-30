package com.relayagents.rag.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges ranked lists by summing 1 / (k + rank). It only looks at ranks, so it needs no score calibration
 * between full-text and vector search, which have incomparable scales. k = 60 is the value from the
 * original paper (Cormack et al., 2009).
 */
public final class ReciprocalRankFusion {

    public static final int DEFAULT_K = 60;

    private ReciprocalRankFusion() {
    }

    public record Fused<T>(T item, double score) {
    }

    public static <T> List<Fused<T>> fuse(List<List<T>> rankings, int k, int limit) {
        Map<T, Double> scores = new LinkedHashMap<>();
        for (List<T> ranking : rankings) {
            for (int rank = 0; rank < ranking.size(); rank++) {
                scores.merge(ranking.get(rank), 1.0 / (k + rank + 1), Double::sum);
            }
        }
        List<Fused<T>> fused = new ArrayList<>();
        scores.forEach((item, score) -> fused.add(new Fused<>(item, score)));
        // Stable sort: ties keep first-seen order, which favours the first ranking passed in.
        fused.sort(Comparator.comparingDouble((Fused<T> f) -> f.score()).reversed());
        return fused.subList(0, Math.min(limit, fused.size()));
    }
}
