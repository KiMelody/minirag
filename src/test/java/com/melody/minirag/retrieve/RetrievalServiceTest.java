package com.melody.minirag.retrieve;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalServiceTest {

    @Test
    void rrfRanksDualRouteHitsFirst() {
        List<Long> fused = RetrievalService.rrf(
                List.of(1L, 2L, 3L),
                List.of(4L, 1L),
                60);
        assertEquals(1L, fused.get(0)); // 1 在两路都有，得分叠加最高
        assertTrue(fused.containsAll(List.of(2L, 3L, 4L)) && fused.size() == 4);
    }

    @Test
    void rrfSortsByOtherRouteWhenOneEmpty() {
        List<Long> fused = RetrievalService.rrf(List.of(5L, 6L), List.of(), 60);
        assertEquals(List.of(5L, 6L), fused);
    }

    @Test
    void sanitizeFtsQuotesWhitespaceSplitTokens() {
        assertEquals("\"守卫者\" \"平台\" \"收费\"", RetrievalService.sanitizeFts("  守卫者 平台\t收费 "));
    }

    @Test
    void sanitizeFtsStripsQuotesFromInput() {
        assertEquals("\"说你好\"", RetrievalService.sanitizeFts("说\"你好\""));
        assertTrue(RetrievalService.sanitizeFts("  \"  ").isEmpty());
    }
}
