package com.relayagents.rag.domain;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A feature-hashing embedder: word unigrams plus character trigrams, hashed into a fixed number of signed
 * buckets and L2-normalised. It is deterministic, needs no model download and handles typos and word
 * forms reasonably well, but it is lexical, not semantic: "refund" and "money back" are unrelated to it.
 * The {@link Embedder} seam is where a real embedding model would go.
 */
public final class HashingEmbedder implements Embedder {

    public static final String MODEL = "hashing-v1-256";
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final double TRIGRAM_WEIGHT = 0.5;

    private final int dimensions;

    public HashingEmbedder(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public String model() {
        return MODEL;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public float[] embed(String text) {
        double[] vector = new double[dimensions];
        Matcher words = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (words.find()) {
            String word = words.group();
            add(vector, "w:" + word, 1.0);
            String padded = "#" + word + "#";
            for (int i = 0; i + 3 <= padded.length(); i++) {
                add(vector, "t:" + padded.substring(i, i + 3), TRIGRAM_WEIGHT);
            }
        }
        double norm = 0;
        for (double v : vector) {
            norm += v * v;
        }
        float[] out = new float[dimensions];
        if (norm == 0) {
            return out;
        }
        double scale = 1.0 / Math.sqrt(norm);
        for (int i = 0; i < dimensions; i++) {
            out[i] = (float) (vector[i] * scale);
        }
        return out;
    }

    private void add(double[] vector, String feature, double weight) {
        int hash = fnv1a(feature);
        int bucket = Math.floorMod(hash, dimensions);
        // A second, independent bit decides the sign, so collisions cancel out on average instead of piling up.
        double sign = (Integer.rotateLeft(hash, 13) & 1) == 0 ? 1.0 : -1.0;
        vector[bucket] += sign * weight;
    }

    private static int fnv1a(String text) {
        int hash = 0x811c9dc5;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xff;
            hash *= 0x01000193;
        }
        return hash;
    }
}
