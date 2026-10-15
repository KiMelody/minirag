package com.melody.minirag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 企微自建应用回调配置（P1 企微 AI 客服形态）。全部来自企业微信管理后台「接收消息」设置。 */
@ConfigurationProperties(prefix = "minirag.wecom")
public record WecomProperties(String corpId, String corpSecret, int agentId, String token, String aesKey) {
}
