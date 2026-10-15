package com.melody.minirag.text;

import com.melody.minirag.config.MiniRagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 定长字符窗口切片（带重叠）。中英文混排下简单可靠，万级切片规模足够。 */
@Component
public class TextSplitter {

    private final int size;
    private final int overlap;

    public TextSplitter(MiniRagProperties props) {
        this.size = props.chunkSize();
        this.overlap = props.chunkOverlap();
    }

    public List<String> split(String raw) {
        String text = raw == null ? "" : raw.replace("\r\n", "\n").strip();
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        int step = Math.max(1, size - overlap);
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(text.length(), start + size);
            String piece = text.substring(start, end).strip();
            if (!piece.isEmpty()) {
                chunks.add(piece);
            }
            if (end >= text.length()) {
                break;
            }
        }
        return chunks;
    }
}
