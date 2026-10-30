package com.relayagents.rag.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RecursiveChunkerTest {

    private final RecursiveChunker chunker = new RecursiveChunker(120, 30);

    @Test
    void shortTextIsOneChunk() {
        assertThat(chunker.chunk("  Refunds take five days.  ")).containsExactly("Refunds take five days.");
        assertThat(chunker.chunk("   ")).isEmpty();
    }

    @Test
    void chunksRespectTheSizeAndKeepEverySentence() {
        String text = IntStream.range(0, 30).mapToObj(i -> "Sentence number " + i + " is here.")
            .collect(Collectors.joining(" "));

        List<String> chunks = chunker.chunk(text);

        assertThat(chunks).hasSizeGreaterThan(3).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(120));
        for (int i = 0; i < 30; i++) {
            String sentence = "Sentence number " + i + " is here";
            assertThat(chunks).anySatisfy(c -> assertThat(c).contains(sentence));
        }
    }

    @Test
    void neighbouringChunksOverlap() {
        String text = IntStream.range(0, 12).mapToObj(i -> "Line " + i + " ok.").collect(Collectors.joining("\n"));
        List<String> chunks = new RecursiveChunker(60, 20).chunk(text);
        for (int i = 1; i < chunks.size(); i++) {
            String previousLast = chunks.get(i - 1).lines().reduce((a, b) -> b).orElseThrow();
            assertThat(chunks.get(i)).startsWith(previousLast);
        }
    }

    @Test
    void rejectsOverlapThatWouldNeverMakeProgress() {
        assertThatThrownBy(() -> new RecursiveChunker(100, 60)).isInstanceOf(IllegalArgumentException.class);
    }
}
