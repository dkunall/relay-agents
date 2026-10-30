package com.relayagents.rag.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReciprocalRankFusionTest {

    @Test
    void itemsRankedWellByBothListsWin() {
        var fused = ReciprocalRankFusion.fuse(List.of(List.of("a", "b", "c"), List.of("b", "c", "d")), 60, 3);

        assertThat(fused).extracting(ReciprocalRankFusion.Fused::item).containsExactly("b", "c", "a");
        assertThat(fused.getFirst().score()).isEqualTo(1.0 / 62 + 1.0 / 61);
    }
}
