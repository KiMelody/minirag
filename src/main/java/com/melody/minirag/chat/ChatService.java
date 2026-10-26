package com.melody.minirag.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melody.minirag.retrieve.RetrievalService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 问答：检索结果编号后作为资料喂给模型，要求带 [n] 引用回答。answer() 阻塞式（MCP/企微），streamAnswer() SSE 流式（网页）。 */
@Service
public class ChatService {

    private static final String SYSTEM_PROMPT = """
            你是企业知识库问答助手。只依据给出的资料回答问题；资料不足以回答时明确说明不知道，不要编造。
            回答中引用资料的地方用 [编号] 标注，例如：平台按点位收费，每点位每年380元[1]。""";

    /** 生成上下文只喂前 4 段：prefill 是生成延迟的大头，RRF 前几名通常已命中。 */
    private static final int CHAT_CONTEXT_LIMIT = 4;
    /** 防超长输入浪费 FTS/embedding/生成开销。 */
    private static final int MAX_QUESTION_CHARS = 2_000;

    private static final Pattern CITE = Pattern.compile("\\[(\\d+)]");

    private final RetrievalService retrieval;
    private final ChatClient chatClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChatService(RetrievalService retrieval, ChatClient.Builder chatClientBuilder) {
        this.retrieval = retrieval;
        this.chatClient = chatClientBuilder.build();
    }

    /** n = 上下文里的引用编号（回答中的 [n] 与来源卡据此对应，过滤后也不错位）。 */
    public record Source(int n, long chunkId, String docId, int seq, String snippet) {
    }

    public record Answer(String answer, List<Source> sources, String mode, int candidates, long retrievalMs) {
    }

    /** SSE 帧：event = meta（检索耗时/模式）| token（增量文本）| sources | done | error。 */
    public record StreamFrame(String event, String data) {
    }

    private record Prepared(String mode, long retrievalMs, List<RetrievalService.Chunk> chunks) {
    }

    public Answer answer(String question) {
        return answer(question, null);
    }

    /** mode: vector（纯余弦）| hybrid（混合检索）| rerank（混合+LLM 重排）；null 走配置默认。 */
    public Answer answer(String question, String mode) {
        Prepared p = prepare(question, mode);
        if (p.chunks().isEmpty()) {
            return new Answer("知识库中没有找到与问题相关的资料。", List.of(), p.mode(), 0, p.retrievalMs());
        }
        String answer = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(buildContext(p.chunks()) + "问题：" + question)
                .call()
                .content();
        return new Answer(answer, filterSources(answer, p.chunks()), p.mode(), p.chunks().size(), p.retrievalMs());
    }

    public Flux<StreamFrame> streamAnswer(String question, String mode) {
        return Flux.defer(() -> {
            Prepared p;
            try {
                p = prepare(question, mode);
            } catch (IllegalArgumentException e) {
                return Flux.just(new StreamFrame("error", toJson(Map.of("error", e.getMessage()))));
            }
            if (p.chunks().isEmpty()) {
                return Flux.just(
                        new StreamFrame("meta", metaJson(p)),
                        new StreamFrame("token", "知识库中没有找到与问题相关的资料。"),
                        new StreamFrame("done", "{}"));
            }
            Flux<String> tokens = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(buildContext(p.chunks()) + "问题：" + question)
                    .stream()
                    .content();
            return assemble(tokens, p).onErrorResume(e -> Flux.just(
                    new StreamFrame("error", toJson(Map.of("error", "生成失败：" + e.getMessage())))));
        });
    }

    /** 组帧顺序：meta → 增量 token → sources（只含被引用的来源）→ done。 */
    Flux<StreamFrame> assemble(Flux<String> tokens, Prepared p) {
        StringBuilder full = new StringBuilder();
        return Flux.concat(
                Flux.just(new StreamFrame("meta", metaJson(p))),
                tokens.map(t -> {
                    full.append(t);
                    return new StreamFrame("token", t);
                }),
                Flux.defer(() -> Flux.just(
                        new StreamFrame("sources", toJson(filterSources(full.toString(), p.chunks()))),
                        new StreamFrame("done", "{}"))));
    }

    private Prepared prepare(String question, String mode) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        if (question.length() > MAX_QUESTION_CHARS) {
            throw new IllegalArgumentException("问题过长，请精简后重试");
        }
        long t0 = System.currentTimeMillis();
        List<RetrievalService.Chunk> found = switch (mode == null ? "" : mode) {
            case "vector" -> retrieval.searchVector(question);
            case "hybrid" -> retrieval.search(question, false);
            case "rerank" -> retrieval.search(question, true);
            default -> retrieval.search(question);
        };
        long retrievalMs = System.currentTimeMillis() - t0;
        String usedMode = switch (mode == null ? "" : mode) {
            case "vector", "hybrid", "rerank" -> mode;
            default -> "default";
        };
        List<RetrievalService.Chunk> chunks = found.size() > CHAT_CONTEXT_LIMIT
                ? found.subList(0, CHAT_CONTEXT_LIMIT) : found;
        return new Prepared(usedMode, retrievalMs, chunks);
    }

    private static String buildContext(List<RetrievalService.Chunk> chunks) {
        StringBuilder ctx = new StringBuilder("资料：\n");
        for (int i = 0; i < chunks.size(); i++) {
            ctx.append("[").append(i + 1).append("] (来源 ").append(chunks.get(i).docId())
                    .append(" 第").append(chunks.get(i).seq() + 1).append("段)\n")
                    .append(chunks.get(i).text()).append("\n\n");
        }
        return ctx.toString();
    }

    /** 回答正文中出现的引用编号，去重并按首次出现排序。 */
    static List<Integer> citedIndices(String answer) {
        LinkedHashSet<Integer> cited = new LinkedHashSet<>();
        Matcher m = CITE.matcher(answer);
        while (m.find()) {
            cited.add(Integer.parseInt(m.group(1)));
        }
        return List.copyOf(cited);
    }

    /** 来源只保留回答实际引用的段（按引用顺序）；无引用或编号全部越界时回退显示全部命中。 */
    static List<Source> filterSources(String answer, List<RetrievalService.Chunk> chunks) {
        List<Source> all = sources(chunks);
        List<Integer> cited = citedIndices(answer);
        if (cited.isEmpty()) {
            return all;
        }
        List<Source> out = new ArrayList<>();
        for (int n : cited) {
            if (n >= 1 && n <= all.size()) {
                out.add(all.get(n - 1));
            }
        }
        return out.isEmpty() ? all : out;
    }

    private static List<Source> sources(List<RetrievalService.Chunk> chunks) {
        List<Source> out = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            RetrievalService.Chunk c = chunks.get(i);
            out.add(new Source(i + 1, c.id(), c.docId(), c.seq(),
                    c.text().substring(0, Math.min(c.text().length(), 80))));
        }
        return out;
    }

    private String metaJson(Prepared p) {
        return toJson(Map.of("mode", p.mode(), "candidates", p.chunks().size(), "retrievalMs", p.retrievalMs()));
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
