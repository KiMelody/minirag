package com.melody.minirag.web;

import com.melody.minirag.chat.ChatService;
import com.melody.minirag.config.WecomProperties;
import com.melody.minirag.wecom.WecomClient;
import com.melody.minirag.wecom.WecomCrypto;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 企微自建应用回调：配好 minirag.wecom.* 后，在管理后台「接收消息」填
 * https://&lt;域名&gt;/api/webhook/wecom 完成 URL 验证，即可把应用内用户消息接入知识库问答。
 * 回调必须 5 秒内响应，问答走异步线程，回复经企微 message/send 主动下发。
 */
@RestController
@RequestMapping("/api/webhook/wecom")
@ConditionalOnProperty(prefix = "minirag.wecom", name = "aes-key")
public class WecomController {

    private static final Logger log = LoggerFactory.getLogger(WecomController.class);
    /** 回调时间戳允许的时钟偏移，超出视为过期重放。 */
    private static final long MAX_SKEW_MS = 5 * 60 * 1000;
    private static final int QUEUE_CAPACITY = 64;

    private final WecomCrypto crypto;
    private final ChatService chatService;
    private final WecomClient wecom;
    /** 已见 (timestamp:nonce) 组合，防短窗口内重放；定期淘汰过期项防膨胀。 */
    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();
    private final ExecutorService executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(QUEUE_CAPACITY),
            r -> {
                Thread t = new Thread(r, "wecom-answer");
                t.setDaemon(true);
                return t;
            },
            (r, pool) -> log.warn("企微问答队列已满（{}），任务被丢弃", QUEUE_CAPACITY));

    public WecomController(WecomProperties props, ChatService chatService, WecomClient wecom) {
        this.crypto = new WecomCrypto(props.token(), props.aesKey(), props.corpId());
        this.chatService = chatService;
        this.wecom = wecom;
    }

    /** 管理后台保存回调 URL 时的验证请求：验签通过则回显解密后的 echostr。 */
    @GetMapping
    public ResponseEntity<String> verify(@RequestParam("msg_signature") String signature,
                                         @RequestParam String timestamp, @RequestParam String nonce,
                                         @RequestParam String echostr) {
        if (!fresh(timestamp, nonce) || !crypto.verify(signature, timestamp, nonce, echostr)) {
            return ResponseEntity.status(403).body("signature mismatch");
        }
        return ResponseEntity.ok(crypto.decrypt(echostr));
    }

    @PostMapping
    public ResponseEntity<String> onMessage(@RequestParam("msg_signature") String signature,
                                            @RequestParam String timestamp, @RequestParam String nonce,
                                            @RequestBody String encryptedXml) {
        if (!fresh(timestamp, nonce)) {
            return ResponseEntity.status(403).body("stale or replayed request");
        }
        String encrypt = tag(encryptedXml, "Encrypt");
        if (encrypt == null || !crypto.verify(signature, timestamp, nonce, encrypt)) {
            return ResponseEntity.status(403).body("signature mismatch");
        }
        String plain = crypto.decrypt(encrypt);
        if ("text".equals(tag(plain, "MsgType"))) {
            String content = tag(plain, "Content");
            String from = tag(plain, "FromUserName");
            if (content != null && from != null) {
                executor.submit(() -> {
                    try {
                        String answer = chatService.answer(content).answer();
                        wecom.sendText(from, answer);
                    } catch (Exception e) {
                        log.error("企微问答处理失败（from={}）: {}", from, e.getMessage());
                    }
                });
            }
        }
        // 企微规范：立即返回空串表示已受理，避免 5 秒超时重试
        return ResponseEntity.ok("");
    }

    /** 时间戳未过期且 (timestamp:nonce) 首次出现，否则视为重放。 */
    private boolean fresh(String timestamp, String nonce) {
        long ts;
        try {
            ts = Long.parseLong(timestamp) * 1000;
        } catch (NumberFormatException e) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (Math.abs(now - ts) > MAX_SKEW_MS) {
            return false;
        }
        if (seenNonces.putIfAbsent(timestamp + ":" + nonce, now) != null) {
            return false;
        }
        seenNonces.entrySet().removeIf(e -> now - e.getValue() > 2 * MAX_SKEW_MS);
        return true;
    }

    static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + ">(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</" + name + ">", Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
