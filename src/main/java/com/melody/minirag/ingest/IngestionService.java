package com.melody.minirag.ingest;

import com.melody.minirag.embed.EmbeddingClient;
import com.melody.minirag.store.VectorIndex;
import com.melody.minirag.text.TextSplitter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Service
public class IngestionService {

    private final TextSplitter splitter;
    private final EmbeddingClient embeddings;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final VectorIndex index;

    public IngestionService(TextSplitter splitter, EmbeddingClient embeddings,
                            JdbcTemplate jdbc, TransactionTemplate tx, VectorIndex index) {
        this.splitter = splitter;
        this.embeddings = embeddings;
        this.jdbc = jdbc;
        this.tx = tx;
        this.index = index;
    }

    public record Result(String docId, int chunks) {
    }

    public Result ingest(String docId, String text) {
        List<String> pieces = splitter.split(text);
        if (pieces.isEmpty()) {
            throw new IllegalArgumentException("文档内容为空");
        }
        float[][] vectors = embeddings.embed(pieces);
        Integer count = tx.execute(status -> {
            for (int i = 0; i < pieces.size(); i++) {
                final int seq = i;
                Long id = jdbc.queryForObject(
                        "INSERT INTO chunks(doc_id, seq, text, embedding) VALUES (?, ?, ?, ?) RETURNING id",
                        Long.class, docId, seq, pieces.get(i), VectorIndex.toBytes(vectors[i]));
                jdbc.update("INSERT INTO chunks_fts(rowid, text) VALUES (?, ?)", id, pieces.get(i));
                index.add(id, docId, seq, pieces.get(i), vectors[i]);
            }
            return pieces.size();
        });
        return new Result(docId, count);
    }
}
