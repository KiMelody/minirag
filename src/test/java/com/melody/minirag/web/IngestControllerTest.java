package com.melody.minirag.web;

import com.melody.minirag.ingest.IngestionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestControllerTest {

    private final IngestionService ingestion = mock(IngestionService.class);
    private final IngestController controller = new IngestController(ingestion);

    @Test
    void docxFileExtractsParagraphText() throws Exception {
        when(ingestion.ingest(anyString(), any())).thenReturn(new IngestionService.Result("d", 1));

        String xml = "<w:document><w:body>"
                + "<w:p><w:r><w:t>年假按工龄划分：</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t>满1年</w:t></w:r><w:r><w:t>5天</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t>a &amp; b</w:t></w:r></w:p>"
                + "</w:body></w:document>";
        MockMultipartFile file = new MockMultipartFile("file", "制度.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docxBytes(xml));

        controller.ingestFile(file);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(ingestion).ingest(anyString(), text.capture());
        assertEquals("年假按工龄划分：\n满1年5天\na & b", text.getValue());
    }

    @Test
    void plainTextFileIngestedAsIs() throws Exception {
        when(ingestion.ingest(anyString(), any())).thenReturn(new IngestionService.Result("d", 1));
        MockMultipartFile file = new MockMultipartFile("file", "faq.txt", "text/plain",
                "普通文本".getBytes(StandardCharsets.UTF_8));

        controller.ingestFile(file);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(ingestion).ingest(anyString(), text.capture());
        assertEquals("普通文本", text.getValue());
    }

    @Test
    void uploadFilenameSanitized() throws Exception {
        when(ingestion.ingest(anyString(), any())).thenReturn(new IngestionService.Result("d", 1));
        MockMultipartFile file = new MockMultipartFile("file", "..\\..\\evil/手册<>.docx",
                "application/octet-stream", docxBytes("<w:document><w:body><w:p><w:r><w:t>x</w:t></w:r></w:p></w:body></w:document>"));

        controller.ingestFile(file);

        ArgumentCaptor<String> docId = ArgumentCaptor.forClass(String.class);
        verify(ingestion).ingest(docId.capture(), any());
        assertTrue(docId.getValue().endsWith(".docx"));
        assertTrue(docId.getValue().indexOf('/') < 0 && docId.getValue().indexOf('\\') < 0,
                "docId 不得含路径分隔符，实际: " + docId.getValue());
    }

    /** 构造最小 docx：zip 容器 + word/document.xml。 */
    private static byte[] docxBytes(String documentXml) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(documentXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
