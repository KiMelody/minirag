package com.melody.minirag.web;

import com.melody.minirag.chat.ChatService;
import com.melody.minirag.config.WecomProperties;
import com.melody.minirag.wecom.WecomClient;
import com.melody.minirag.wecom.WecomCrypto;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 企微回调全流程：时效/防重放→验签→解密→异步问答→回复下发。ChatService/WecomClient 用 Mockito mock。 */
class WecomCallbackFlowTest {

    private static final String AES_KEY = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";

    private final WecomCrypto crypto = new WecomCrypto("test-token", AES_KEY, "wwtest123");
    private final ChatService chatService = mock(ChatService.class);
    private final WecomClient wecomClient = mock(WecomClient.class);
    private final WecomController controller = new WecomController(
            new WecomProperties("wwtest123", null, 1000002, "test-token", AES_KEY),
            chatService, wecomClient);

    private static String ts() {
        return String.valueOf(System.currentTimeMillis() / 1000);
    }

    @Test
    void urlVerifyEchoesDecryptedEchostrOnValidSignature() {
        String echoEnc = crypto.encrypt("echo-plain-42");
        String ts = ts();
        var resp = controller.verify(crypto.signature(ts, "verify-1", echoEnc), ts, "verify-1", echoEnc);
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("echo-plain-42", resp.getBody());
    }

    @Test
    void urlVerifyRejectedWithStaleTimestamp() {
        String echoEnc = crypto.encrypt("echo-plain-42");
        String old = "1409659813";
        var resp = controller.verify(crypto.signature(old, "stale-1", echoEnc), old, "stale-1", echoEnc);
        assertEquals(403, resp.getStatusCode().value());
    }

    @Test
    void urlVerifyRejectedOnBadSignature() {
        String echoEnc = crypto.encrypt("echo-plain-42");
        String ts = ts();
        var resp = controller.verify("bad0000000000000000000000000000000000000", ts, "badsig-1", echoEnc);
        assertEquals(403, resp.getStatusCode().value());
    }

    @Test
    void textMessageAnswersAsyncAndReplies() throws Exception {
        String question = "年假有几天";
        String answer = "入职满1年5天，满5年10天，满10年15天[1]";
        CountDownLatch replied = new CountDownLatch(1);
        when(chatService.answer(question)).thenReturn(new ChatService.Answer(answer, List.of(), "hybrid", 1, 5L));
        doAnswer(inv -> {
            replied.countDown();
            return null;
        }).when(wecomClient).sendText("zhangsan", answer);

        var resp = post(question, ts(), "msg-1");
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("", resp.getBody(), "企微要求立即返回空串避免重试");

        assertTrue(replied.await(5, TimeUnit.SECONDS), "5 秒内应完成异步问答并调用企微回复");
    }

    @Test
    void sameTimestampAndNonceRejectedAsReplay() {
        String question = "重放测试";
        when(chatService.answer(question)).thenReturn(
                new ChatService.Answer("ans[1]", List.of(), "hybrid", 1, 5L));
        String ts = ts();
        assertEquals(200, post(question, ts, "replay-1").getStatusCode().value());
        // 同一 (timestamp, nonce) 的原样重放：403，且不再触发第二次问答
        assertEquals(403, post(question, ts, "replay-1").getStatusCode().value());
        verify(chatService, times(1)).answer(question);
    }

    @Test
    void nonTextMessageSkipsAnswering() {
        String plain = "<xml><FromUserName><![CDATA[zhangsan]]></FromUserName>"
                + "<MsgType><![CDATA[event]]></MsgType><Event><![CDATA[subscribe]]></Event></xml>";
        String enc = crypto.encrypt(plain);
        String ts = ts();
        controller.onMessage(crypto.signature(ts, "event-1", enc), ts, "event-1",
                "<xml><Encrypt><![CDATA[" + enc + "]]></Encrypt></xml>");
        verify(chatService, never()).answer(any());
    }

    private ResponseEntity<String> post(String question, String ts, String nonce) {
        String plain = "<xml><ToUserName><![CDATA[wwtest123]]></ToUserName>"
                + "<FromUserName><![CDATA[zhangsan]]></FromUserName>"
                + "<MsgType><![CDATA[text]]></MsgType>"
                + "<Content><![CDATA[" + question + "]]></Content></xml>";
        String enc = crypto.encrypt(plain);
        return controller.onMessage(crypto.signature(ts, nonce, enc), ts, nonce,
                "<xml><Encrypt><![CDATA[" + enc + "]]></Encrypt></xml>");
    }
}
