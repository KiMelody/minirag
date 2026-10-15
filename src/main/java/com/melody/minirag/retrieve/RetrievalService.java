package com.melody.minirag.retrieve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melody.minirag.config.MiniRagProperties;
import com.melody.minirag.embed.EmbeddingClient;
import com.melody.minirag.store.VectorIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 混合检索：内存余弦 topK + FTS5 BM25 topK → RRF 融合 → 可选 LLM 重排。 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final EmbeddingClient embeddings;
    private final VectorIndex index;
    private final JdbcTemplate jdbc;
    private final ChatClient chatClient;
    private final MiniRagProperties.Retrieval cfg;
    private final ObjectMapper mapper = new ObjectMapper();

    public RetrievalService(EmbeddingClient embeddings, VectorIndex index, JdbcTemplate jdbc,
                            ChatClient.Builder chatClientBuilder, MiniRagProperties props) {
        this.embeddings = embeddings;
        this.index = index;
        this.jdbc = jdbc;
        this.chatClient = chatClientBuilder.build();
        this.cfg = props.retrieval();
    }

    public record Chunk(long id, String docId, int seq, String text) {
    }

    /** 按 RRF 融合排名，rerank 开关由调用方显式控制（eval 需要）。 */
    public List<Chunk> search(String query, boolean rerank) {
        List<Long> vectorIds = vectorIds(query);
        List<Long> keywordIds = keywordIds(query);
        List<Long> fused = rrf(vectorIds, keywordIds, cfg.rrfK()).stream()
                .limit(cfg.contextTopK()).toList();
        if (fused.isEmpty()) {
            return List.of();
        }
        Map<Long, Chunk> byId = loadChunks(fused);
        List<Chunk> chunks = fused.stream().map(byId::get).collect(Collectors.toCollection(ArrayList::new));
        return rerank ? rerank(query, chunks) : chunks;
    }

    public List<Chunk> search(String query) {
        return search(query, cfg.rerank());
    }

    /** 仅向量检索（eval 基线）。 */
    public List<Chunk> searchVector(String query) {
        List<Long> ids = vectorIds(query).stream().limit(cfg.contextTopK()).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Chunk> byId = loadChunks(ids);
        return ids.stream().map(byId::get).toList();
    }

    private List<Long> vectorIds(String query) {
        float[] q = embeddings.embedOne(query);
        return index.topK(q, cfg.vectorTopK()).stream().map(h -> h.entry().id()).toList();
    }

    private List<Long> keywordIds(String query) {
        String match = sanitizeFts(query);
        if (match.isEmpty()) {
            return List.of();
        }
        try {
            return jdbc.queryForList(
                    "SELECT c.id FROM chunks_fts f JOIN chunks c ON c.id = f.rowid " +
                            "WHERE chunks_fts MATCH ? ORDER BY bm25(chunks_fts) LIMIT ?",
                    Long.class, match, cfg.keywordTopK());
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("FTS 查询失败，回退纯向量检索: {}", e.getMessage());
            return List.of();
        }
    }

    /** 把查询净化成 FTS5 短语匹配：去掉引号，按空白切词后逐词加引号（trigram 对中文按字组命中）。 */
    static String sanitizeFts(String query) {
        String[] tokens = query.replace("\"", "").trim().split("\\s+");
        List<String> quoted = new ArrayList<>();
        for (String t : tokens) {
            if (!t.isBlank()) {
                quoted.add("\"" + t + "\"");
            }
        }
        return String.join(" ", quoted);
    }

    /** 倒数排名融合：score = Σ 1/(k + rank)，两侧都命中的切片得分叠加。 */
    static List<Long> rrf(List<Long> primary, List<Long> secondary, int k) {
        Map<Long, Double> scores = new HashMap<>();
        for (int i = 0; i < primary.size(); i++) {
            scores.merge(primary.get(i), 1.0 / (k + i + 1), Double::sum);
        }
        for (int i = 0; i < secondary.size(); i++) {
            scores.merge(secondary.get(i), 1.0 / (k + i + 1), Double::sum);
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
    }

    private Map<Long, Chunk> loadChunks(List<Long> ids) {
        String placeholders = ids.stream().map(i -> "?").collect(Collectors.joining(","));
        List<Chunk> chunks = jdbc.query(
                "SELECT id, doc_id, seq, text FROM chunks WHERE id IN (" + placeholders + ")",
                (rs, i) -> new Chunk(rs.getLong("id"), rs.getString("doc_id"), rs.getInt("seq"), rs.getString("text")),
                ids.toArray());
        return chunks.stream().collect(Collectors.toMap(Chunk::id, c -> c));
    }

    /** LLM 相关性重排：解析失败或超时回退 RRF 顺序。 */
    private List<Chunk> rerank(String query, List<Chunk> chunks) {
        StringBuilder sb = new StringBuilder("对查询与每个候选段落的相关性打 0-10 的整数分，只输出 JSON 数组如 [{\"id\":1,\"score\":8}]，不要输出其他内容。\n")
                .append("查询: ").append(query).append("\n候选:\n");
        for (int i = 0; i < chunks.size(); i++) {
            String text = chunks.get(i).text();
            sb.append("[").append(i + 1).append("] id=").append(chunks.get(i).id()).append(" ")
                    .append(text, 0, Math.min(text.length(), 300)).append("\n");
        }
        try {
            String resp = chatClient.prompt().user(sb.toString()).call().content();
            JsonNode arr = mapper.readTree(resp);
            Map<Long, Integer> scores = new HashMap<>();
            for (JsonNode n : arr) {
                scores.put(n.path("id").asLong(), n.path("score").asInt(0));
            }
            List<Chunk> ordered = chunks.stream()
                    .sorted((a, b) -> Integer.compare(scores.getOrDefault(b.id(), 0), scores.getOrDefault(a.id(), 0)))
                    .toList();
            log.debug("rerank 前后顺序: {} -> {}",
                    chunks.stream().map(Chunk::id).toList(), ordered.stream().map(Chunk::id).toList());
            return ordered;
        } catch (Exception e) {
            log.warn("LLM 重排失败，保留 RRF 顺序: {}", e.getMessage());
            return chunks;
        }
    }
}
