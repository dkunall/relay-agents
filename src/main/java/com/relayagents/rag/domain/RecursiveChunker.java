package com.relayagents.rag.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits on the coarsest separator that gets pieces under the target size (paragraphs, then lines, then
 * sentences, then words), then packs pieces back together up to the target with a little overlap, so a
 * sentence cut at a boundary still appears whole in one of the two chunks.
 */
public final class RecursiveChunker {

    private static final String[] SEPARATORS = {"\n\n", "\n", ". ", " "};

    private final int size;
    private final int overlap;

    public RecursiveChunker(int size, int overlap) {
        if (size < 50 || overlap < 0 || overlap >= size / 2) {
            throw new IllegalArgumentException("size must be >= 50 and overlap < size / 2");
        }
        this.size = size;
        this.overlap = overlap;
    }

    public List<String> chunk(String text) {
        String normalised = text.replace("\r\n", "\n").strip();
        if (normalised.isEmpty()) {
            return List.of();
        }
        List<String> pieces = new ArrayList<>();
        split(normalised, 0, pieces);
        return pack(pieces);
    }

    private void split(String text, int level, List<String> out) {
        if (text.length() <= size) {
            out.add(text);
            return;
        }
        if (level == SEPARATORS.length) {
            for (int i = 0; i < text.length(); i += size) {
                out.add(text.substring(i, Math.min(text.length(), i + size)));
            }
            return;
        }
        String separator = SEPARATORS[level];
        int from = 0;
        while (from < text.length()) {
            int at = text.indexOf(separator, from);
            int end = at < 0 ? text.length() : at + separator.length();
            String part = text.substring(from, end);
            if (!part.isBlank()) {
                split(part, level + 1, out);
            }
            from = end;
        }
    }

    private List<String> pack(List<String> pieces) {
        List<String> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int length = 0;
        for (String piece : pieces) {
            if (length + piece.length() > size && !current.isEmpty()) {
                chunks.add(String.join("", current).strip());
                List<String> carried = new ArrayList<>();
                int carriedLength = 0;
                for (int i = current.size() - 1; i >= 0; i--) {
                    String previous = current.get(i);
                    if (carriedLength + previous.length() > overlap || carriedLength + previous.length() + piece.length() > size) {
                        break;
                    }
                    carried.addFirst(previous);
                    carriedLength += previous.length();
                }
                current = carried;
                length = carriedLength;
            }
            current.add(piece);
            length += piece.length();
        }
        if (!current.isEmpty()) {
            chunks.add(String.join("", current).strip());
        }
        return chunks.stream().filter(c -> !c.isEmpty()).toList();
    }
}
