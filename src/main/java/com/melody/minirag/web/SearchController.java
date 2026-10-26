package com.melody.minirag.web;

import com.melody.minirag.retrieve.RetrievalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 检索调试/评估端点：mode = vector（纯余弦）| hybrid（+BM25+RRF）| rerank（+LLM 重排）。 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private static final int MAX_QUERY_CHARS = 2_000;

    private final RetrievalService retrieval;

    public SearchController(RetrievalService retrieval) {
        this.retrieval = retrieval;
    }

    public record ResultItem(long chunkId, String docId, int seq, String snippet) {
    }

    public record SearchResult(String mode, List<ResultItem> results) {
    }

    @GetMapping
    public SearchResult search(@RequestParam String q, @RequestParam(defaultValue = "hybrid") String mode) {
        if (q.length() > MAX_QUERY_CHARS) {
            throw new IllegalArgumentException("查询过长");
        }
        List<RetrievalService.Chunk> chunks = switch (mode) {
            case "vector" -> retrieval.searchVector(q);
            case "hybrid" -> retrieval.search(q, false);
            case "rerank" -> retrieval.search(q, true);
            default -> throw new IllegalArgumentException("mode 仅支持 vector/hybrid/rerank");
        };
        return new SearchResult(mode, chunks.stream()
                .map(c -> new ResultItem(c.id(), c.docId(), c.seq(),
                        c.text().substring(0, Math.min(c.text().length(), 60))))
                .toList());
    }
}
