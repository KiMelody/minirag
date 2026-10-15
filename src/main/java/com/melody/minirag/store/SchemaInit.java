package com.melody.minirag.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;

/** 建表并预热内存索引：目录→表→load 必须按此顺序，统一在此处执行以保证 Bean 初始化顺序确定。 */
@Component
public class SchemaInit {

    private final JdbcTemplate jdbc;
    private final VectorIndex vectorIndex;

    public SchemaInit(JdbcTemplate jdbc, VectorIndex vectorIndex) {
        this.jdbc = jdbc;
        this.vectorIndex = vectorIndex;
    }

    @PostConstruct
    void init() throws Exception {
        Files.createDirectories(Path.of("data"));
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS chunks (
                    id INTEGER PRIMARY KEY,
                    doc_id TEXT NOT NULL,
                    seq INTEGER NOT NULL,
                    text TEXT NOT NULL,
                    embedding BLOB
                )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chunks_doc ON chunks(doc_id)");
        jdbc.execute("CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(text, tokenize='trigram')");
        vectorIndex.load();
    }
}
