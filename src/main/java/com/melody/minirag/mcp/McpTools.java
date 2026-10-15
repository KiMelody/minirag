package com.melody.minirag.mcp;

import com.melody.minirag.chat.ChatService;
import com.melody.minirag.config.MiniRagProperties;
import com.melody.minirag.ingest.IngestionService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** MCP 工具集：让 Claude Desktop 等客户端直接读写本知识库（海外 gig 的可体验信任状）。 */
@Component
public class McpTools {

    private final IngestionService ingestion;
    private final ChatService chat;
    private final String adminKey;

    public McpTools(IngestionService ingestion, @Lazy ChatService chat, MiniRagProperties props) {
        this.ingestion = ingestion;
        this.chat = chat;
        this.adminKey = props.adminKey();
    }

    @Tool(description = "向企业知识库写入一篇文档（需管理员密钥，自动切片与向量化），返回切片数")
    public String ingest_text(
            @ToolParam(description = "文档唯一 ID，如 handbook-v2") String docId,
            @ToolParam(description = "文档全文（纯文本）") String text,
            @ToolParam(description = "管理员密钥（与服务端 ADMIN_KEY 一致，访客访问码无效）") String adminKey) {
        boolean guardOn = this.adminKey != null && !this.adminKey.isBlank();
        if (guardOn && (adminKey == null || !this.adminKey.equals(adminKey))) {
            return "拒绝：入库需要管理员密钥（adminKey 参数须与服务端 ADMIN_KEY 一致）";
        }
        return ingestion.ingest(docId, text).toString();
    }

    @Tool(description = "向企业知识库提问，返回带 [n] 引用标注的回答")
    public String query(@ToolParam(description = "用户问题") String question) {
        return chat.answer(question).answer();
    }
}
