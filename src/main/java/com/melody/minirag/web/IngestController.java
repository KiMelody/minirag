package com.melody.minirag.web;

import com.melody.minirag.ingest.IngestionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@RestController
@RequestMapping("/api/documents")
public class IngestController {

    /** docx 正文文本节点（OOXML word/document.xml）。 */
    private static final Pattern WT = Pattern.compile("<w:t[^>]*>(.*?)</w:t>", Pattern.DOTALL);

    private final IngestionService ingestion;

    public IngestController(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    public record TextIngest(String docId, String text) {
    }

    @PostMapping("/text")
    public IngestionService.Result ingestText(@RequestBody TextIngest req) {
        String docId = req.docId() != null && !req.docId().isBlank()
                ? req.docId() : "doc-" + UUID.randomUUID().toString().substring(0, 8);
        return ingestion.ingest(docId, req.text());
    }

    @PostMapping
    public IngestionService.Result ingestFile(@RequestParam("file") MultipartFile file) throws IOException {
        String docId = sanitizeDocId(file.getOriginalFilename());
        byte[] bytes = file.getBytes();
        String text;
        if (docId.endsWith(".docx")) {
            text = extractDocxText(bytes);
        } else {
            text = new String(bytes, StandardCharsets.UTF_8);
        }
        return ingestion.ingest(docId, text);
    }

    /** 上传文件名直接作为 docId 对外展示，去掉路径/控制字符等不可控内容并限长。 */
    static String sanitizeDocId(String name) {
        if (name == null || name.isBlank()) {
            return "doc-" + UUID.randomUUID().toString().substring(0, 8);
        }
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
        if (cleaned.length() > 80) {
            cleaned = cleaned.substring(cleaned.length() - 80);
        }
        return cleaned;
    }

    /**
     * 从 docx（zip 容器）提取 word/document.xml 中的正文文本。
     * 返回 null 表示不是有效 docx（缺少该条目），由调用方按普通文本处理或报错。
     */
    static String extractDocxText(byte[] bytes) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    return extractTextFromDocumentXml(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return null;
    }

    /** 按 </w:p> 切段落，段落内拼接全部 w:t 节点文本。 */
    static String extractTextFromDocumentXml(String xml) {
        StringBuilder out = new StringBuilder();
        for (String para : xml.split("</w:p>")) {
            Matcher m = WT.matcher(para);
            boolean any = false;
            while (m.find()) {
                out.append(unescapeXml(m.group(1)));
                any = true;
            }
            if (any) {
                out.append('\n');
            }
        }
        return out.toString().strip();
    }

    private static String unescapeXml(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }
}
