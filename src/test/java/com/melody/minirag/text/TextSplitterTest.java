package com.melody.minirag.text;

import com.melody.minirag.config.MiniRagProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextSplitterTest {

    private TextSplitter splitter(int size, int overlap) {
        return new TextSplitter(new MiniRagProperties(size, overlap, null, null, null, null));
    }

    @Test
    void shortTextSingleChunk() {
        List<String> chunks = splitter(500, 80).split("你好，世界");
        assertEquals(List.of("你好，世界"), chunks);
    }

    @Test
    void longTextSplitsWithOverlap() {
        String text = "字".repeat(1200);
        List<String> chunks = splitter(500, 80).split(text);
        assertTrue(chunks.size() >= 3, "1200 字 / 500 窗口 / 80 重叠应至少 3 片，实际 " + chunks.size());
        assertTrue(chunks.get(0).length() <= 500);
        // 重叠保证：第 2 片开头与第 1 片结尾有共同内容
        String tail = chunks.get(0).substring(chunks.get(0).length() - 80);
        assertTrue(chunks.get(1).startsWith(tail));
    }

    @Test
    void blankTextEmpty() {
        assertTrue(splitter(500, 80).split("   \r\n  ").isEmpty());
    }
}
