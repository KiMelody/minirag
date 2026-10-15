package com.melody.minirag.web;

import com.melody.minirag.chat.ChatService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    public record ChatRequest(String question, String mode) {
    }

    /** 阻塞式 JSON（机器调用）；网页用 /api/chat/stream 流式。 */
    @PostMapping("/api/chat")
    public ChatService.Answer chat(@RequestBody ChatRequest req) {
        return chatService.answer(req.question(), req.mode());
    }

    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest req) {
        return chatService.streamAnswer(req.question(), req.mode())
                .map(f -> ServerSentEvent.builder(f.data()).event(f.event()).build());
    }
}
