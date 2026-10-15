package com.melody.minirag.wecom;

import com.melody.minirag.config.WecomProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** 企微服务端 API：access_token 缓存 + 应用消息发送。 */
@Component
public class WecomClient {

    private static final Logger log = LoggerFactory.getLogger(WecomClient.class);
    private static final String BASE = "https://qyapi.weixin.qq.com";

    private final WecomProperties cfg;
    private final RestClient http;
    private volatile String cachedToken;
    private volatile long tokenExpiresAt;

    public WecomClient(WecomProperties cfg) {
        this.cfg = cfg;
        this.http = RestClient.builder().baseUrl(BASE).build();
    }

    /** 企微文本消息 UTF-8 上限 2048 字节，按 600 字符截断保守处理。 */
    public void sendText(String toUser, String content) {
        if (cfg.corpSecret() == null || cfg.corpSecret().isBlank()) {
            log.warn("企微 corpSecret 未配置，跳过回复（toUser={}）", toUser);
            return;
        }
        String text = content.length() > 600 ? content.substring(0, 600) + "…（已截断）" : content;
        try {
            Map<String, Object> body = Map.of(
                    "touser", toUser,
                    "msgtype", "text",
                    "agentid", cfg.agentId(),
                    "text", Map.of("content", text));
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = http.post()
                    .uri("/cgi-bin/message/send?access_token=" + accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (resp == null || !Integer.valueOf(0).equals(resp.get("errcode"))) {
                log.warn("企微消息发送未成功: {}", resp);
            }
        } catch (Exception e) {
            log.error("企微消息发送失败（toUser={}）: {}", toUser, e.getMessage());
        }
    }

    private synchronized String accessToken() {
        if (cachedToken != null && System.currentTimeMillis() < tokenExpiresAt) {
            return cachedToken;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = http.get()
                .uri("/cgi-bin/gettoken?corpid={corp}&corpsecret={secret}", cfg.corpId(), cfg.corpSecret())
                .retrieve()
                .body(Map.class);
        if (resp == null || !Integer.valueOf(0).equals(resp.get("errcode"))) {
            throw new IllegalStateException("获取企微 access_token 失败: " + resp);
        }
        cachedToken = (String) resp.get("access_token");
        int expiresIn = ((Number) resp.get("expires_in")).intValue();
        tokenExpiresAt = System.currentTimeMillis() + (expiresIn - 300) * 1000L;
        return cachedToken;
    }
}
