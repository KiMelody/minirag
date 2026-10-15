package com.melody.minirag.embed;

import com.melody.minirag.config.MiniRagProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** OpenAI 兼容 /embeddings 客户端（默认硅基流动 BAAI/bge-m3，大陆直连）。 */
@Component
public class EmbeddingClient {

    private static final int BATCH = 16;

    private final MiniRagProperties.Embedding cfg;
    private final RestClient http;

    public EmbeddingClient(MiniRagProperties props) {
        this.cfg = props.embedding();
        this.http = RestClient.builder()
                .baseUrl(cfg.baseUrl())
                .defaultHeader("Authorization", "Bearer " + cfg.apiKey())
                .build();
    }

    public float[][] embed(List<String> texts) {
        if (cfg.apiKey() == null || cfg.apiKey().isBlank()) {
            throw new IllegalStateException("EMBEDDING_API_KEY 未配置（export EMBEDDING_API_KEY=sk-...）");
        }
        float[][] all = new float[texts.size()][];
        for (int from = 0; from < texts.size(); from += BATCH) {
            List<String> batch = texts.subList(from, Math.min(texts.size(), from + BATCH));
            Map<String, Object> body = Map.of("model", cfg.model(), "input", batch);
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = http.post()
                    .uri("/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            List<Map<String, Object>> data = (List<Map<String, Object>>) resp.get("data");
            for (Map<String, Object> item : data) {
                int index = ((Number) item.get("index")).intValue();
                List<Number> vec = (List<Number>) item.get("embedding");
                float[] f = new float[vec.size()];
                for (int i = 0; i < vec.size(); i++) {
                    f[i] = vec.get(i).floatValue();
                }
                all[from + index] = f;
            }
        }
        return all;
    }

    public float[] embedOne(String text) {
        return embed(List.of(text))[0];
    }
}
