package com.melody.minirag.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 常驻内存的暴力余弦索引：万级切片毫秒级，重启时从 SQLite 重载。 */
@Component
public class VectorIndex {

    public record Entry(long id, String docId, int seq, String text, float[] vector) {
    }

    public record Hit(Entry entry, double score) {
    }

    private final JdbcTemplate jdbc;
    private final List<Entry> entries = new ArrayList<>();

    public VectorIndex(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 由 SchemaInit 在建表后调用。 */
    void load() {
        jdbc.query("SELECT id, doc_id, seq, text, embedding FROM chunks WHERE embedding IS NOT NULL", rs -> {
            byte[] blob = rs.getBytes("embedding");
            float[] v = toFloats(blob);
            entries.add(new Entry(rs.getLong("id"), rs.getString("doc_id"), rs.getInt("seq"), rs.getString("text"), v));
        });
    }

    public synchronized void add(long id, String docId, int seq, String text, float[] vector) {
        entries.add(new Entry(id, docId, seq, text, vector));
    }

    public synchronized List<Hit> topK(float[] query, int k) {
        double qNorm = Math.sqrt(dot(query, query));
        List<Hit> hits = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            double denom = qNorm * Math.sqrt(dot(e.vector(), e.vector()));
            double score = denom == 0 ? 0 : dot(query, e.vector()) / denom;
            hits.add(new Hit(e, score));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.size() > k ? hits.subList(0, k) : hits;
    }

    private static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    public static byte[] toBytes(float[] v) {
        ByteBuffer buf = ByteBuffer.allocate(4 * v.length).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) {
            buf.putFloat(f);
        }
        return buf.array();
    }

    public static float[] toFloats(byte[] b) {
        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        float[] v = new float[b.length / 4];
        for (int i = 0; i < v.length; i++) {
            v[i] = buf.getFloat();
        }
        return v;
    }
}
