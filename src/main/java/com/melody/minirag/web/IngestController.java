package com.melody.minirag.web;

import com.melody.minirag.ingest.IngestionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
public class IngestController {

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
        String name = file.getOriginalFilename();
        String docId = name != null ? name : "doc-" + UUID.randomUUID().toString().substring(0, 8);
        String text = new String(file.getBytes(), StandardCharsets.UTF_8);
        return ingestion.ingest(docId, text);
    }
}
