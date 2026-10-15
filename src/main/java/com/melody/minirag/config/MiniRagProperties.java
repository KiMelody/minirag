package com.melody.minirag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 全局配置。demoKey=访客访问码（只读：问答/检索）；adminKey=入库密钥（写入语料，仅服务器持有）。 */
@ConfigurationProperties(prefix = "minirag")
public record MiniRagProperties(
        int chunkSize,
        int chunkOverlap,
        Embedding embedding,
        Retrieval retrieval,
        String demoKey,
        String adminKey) {

    public record Embedding(String baseUrl, String apiKey, String model) {
    }

    public record Retrieval(int vectorTopK, int keywordTopK, int rrfK, int contextTopK, boolean rerank) {
    }
}
