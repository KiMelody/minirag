package com.melody.minirag.chat;

import com.melody.minirag.retrieve.RetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    private final RetrievalService retrieval = mock(RetrievalService.class);
    private final ChatClient chatClient = mock(ChatClient.class);
    private final ChatClient.Builder builder = mock(ChatClient.Builder.class);
    private final ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
    private final ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
    private ChatService service;

    @BeforeEach
    void setUp() {
        when(builder.build()).thenReturn(chatClient);
        service = new ChatService(retrieval, builder);
    }

    private static RetrievalService.Chunk chunk(long id) {
        return new RetrievalService.Chunk(id, "doc-" + id, 0, "第" + id + "段内容，用于测试的正文文本。");
    }

    private void stubStream(Flux<String> tokens) {
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(tokens);
    }

    @Test
    void streamFramesOrderedMetaTokenSourcesDone() {
        stubStream(Flux.just("回答", "内容"));
        when(retrieval.search("q", false)).thenReturn(List.of(chunk(1), chunk(2)));

        List<ChatService.StreamFrame> frames = service.streamAnswer("q", "hybrid").collectList().block();

        assertEquals(5, frames.size());
        assertEquals("meta", frames.get(0).event());
        assertTrue(frames.get(0).data().contains("\"candidates\":2"));
        assertEquals("token", frames.get(1).event());
        assertEquals("回答", frames.get(1).data());
        assertEquals("token", frames.get(2).event());
        assertEquals("sources", frames.get(3).event());
        assertTrue(frames.get(3).data().contains("doc-1"));
        assertTrue(frames.get(3).data().contains("doc-2"));
        assertEquals("done", frames.get(4).event());
    }

    @Test
    void generationContextCappedToFourChunks() {
        stubStream(Flux.just("答"));
        when(retrieval.search("q", false)).thenReturn(
                LongStream.rangeClosed(1, 6).mapToObj(ChatServiceTest::chunk).toList());

        service.streamAnswer("q", "hybrid").collectList().block();

        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(spec).user(user.capture());
        assertTrue(user.getValue().contains("[4]"));
        assertFalse(user.getValue().contains("[5]"));
        assertTrue(user.getValue().contains("问题：q"));
    }

    @Test
    void emptyCorpusReturnsNoticeAndCompletes() {
        when(retrieval.search("q")).thenReturn(List.of());

        List<ChatService.StreamFrame> frames = service.streamAnswer("q", null).collectList().block();

        assertEquals("meta", frames.get(0).event());
        assertTrue(frames.get(0).data().contains("\"candidates\":0"));
        assertEquals("token", frames.get(1).event());
        assertTrue(frames.get(1).data().contains("没有找到"));
        assertEquals("done", frames.get(2).event());
        assertEquals(3, frames.size());
    }

    @Test
    void streamSourcesKeepOnlyCitedChunks() {
        stubStream(Flux.just("费用为380元[2]", "每年。"));
        when(retrieval.search("q", false)).thenReturn(List.of(chunk(1), chunk(2), chunk(3)));

        List<ChatService.StreamFrame> frames = service.streamAnswer("q", "hybrid").collectList().block();

        ChatService.StreamFrame sources = frames.stream()
                .filter(f -> f.event().equals("sources")).findFirst().orElseThrow();
        assertTrue(sources.data().contains("\"n\":2"));
        assertTrue(sources.data().contains("doc-2"));
        assertFalse(sources.data().contains("doc-1"));
        assertFalse(sources.data().contains("doc-3"));
    }

    @Test
    void citedIndicesDedupInFirstAppearanceOrder() {
        assertEquals(List.of(1, 3), ChatService.citedIndices("甲[1]乙[3]，再说一遍[1]"));
        assertEquals(List.of(), ChatService.citedIndices("没有引用的普通回答"));
        assertEquals(List.of(2, 12), ChatService.citedIndices("[2] 和 [12]"));
    }

    @Test
    void sourceFilterFallbackRules() {
        List<RetrievalService.Chunk> chunks = List.of(chunk(1), chunk(2));
        // 引用顺序决定卡片顺序
        assertEquals(List.of("doc-2", "doc-1"),
                ChatService.filterSources("先[2]后[1]", chunks).stream().map(s -> s.docId()).toList());
        // 无引用回退全部
        assertEquals(2, ChatService.filterSources("没有任何引用", chunks).size());
        // 编号全部越界回退全部
        assertEquals(2, ChatService.filterSources("[9]", chunks).size());
        // 部分越界只保留有效编号
        assertEquals(List.of("doc-1"),
                ChatService.filterSources("[1]和[9]", chunks).stream().map(s -> s.docId()).toList());
    }
}
