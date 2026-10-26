package com.melody.minirag.web;

import com.melody.minirag.config.MiniRagProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 读写分级访问控制：
 * - 读（/api/chat、/api/search）：访客访问码（X-Demo-Key 头）+ 每 IP 每分钟限速，校验失败同样占用配额；
 * - 写（/api/documents）：管理员密钥（X-Admin-Key 头），与访客码分离，同样限速；
 * - 全部 /api/*：拒绝路径编码变体与超限请求体。
 * 两种密钥均未配置时放行（本地开发）。真实 IP 取 CF-Connecting-IP（源站仅对 Cloudflare 开放，
 * 该头经 CF 回源时被强制覆盖，直连源站已被防火墙拒绝），缺省 remoteAddr。
 */
@Component
public class DemoKeyFilter extends OncePerRequestFilter {

    private static final List<String> READ_PREFIXES = List.of("/api/chat", "/api/search");
    private static final List<String> WRITE_PREFIXES = List.of("/api/documents");
    private static final int LIMIT_PER_MINUTE = 20;
    private static final long WINDOW_MS = Duration.ofMinutes(1).toMillis();
    /** 兜底防 Map 无限膨胀（大量独立来源），先淘汰过期条目，仍超限才整体重置。 */
    private static final int MAX_TRACKED_IPS = 10_000;
    private static final long MAX_BODY_BYTES = 256 * 1024;
    private static final long MAX_WEBHOOK_BODY_BYTES = 64 * 1024;

    private final String demoKey;
    private final String adminKey;
    private final Map<String, Deque<Long>> recentHits = new ConcurrentHashMap<>();

    public DemoKeyFilter(MiniRagProperties props) {
        this.demoKey = props.demoKey();
        this.adminKey = props.adminKey();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !effectivePath(request).startsWith("/api")
                || (isBlank(demoKey) && isBlank(adminKey));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 路径匹配用解码后的 servletPath，与 Spring MVC 一致；原始 URI 含编码字符或路径参数
        // （如 /api/cha%74、/api/%63hat）时二者可能出现规范化差异，直接拒绝——正常客户端从不发送。
        String raw = request.getRequestURI();
        if (raw.indexOf('%') >= 0 || raw.indexOf(';') >= 0) {
            reject(response, 403, "非法的请求路径");
            return;
        }
        long contentLength = request.getContentLengthLong();
        if (contentLength > (effectivePath(request).startsWith("/api/webhook") ? MAX_WEBHOOK_BODY_BYTES : MAX_BODY_BYTES)) {
            reject(response, 413, "请求体过大");
            return;
        }

        String path = effectivePath(request);
        if (!noneMatch(path, WRITE_PREFIXES)) {
            // 已部署（任一密钥配置）但缺 ADMIN_KEY 时对写入整体拒绝，避免裸奔；失败同样占用限速配额
            if (isBlank(adminKey) || !keyMatches(adminKey, request.getHeader("X-Admin-Key"))) {
                if (allow(clientIp(request))) {
                    reject(response, 401, "入库接口需要管理员密钥（X-Admin-Key 头）");
                } else {
                    reject(response, 429, "请求过于频繁，请稍后再试");
                }
                return;
            }
        } else if (!noneMatch(path, READ_PREFIXES) && !isBlank(demoKey)) {
            if (!keyMatches(demoKey, request.getHeader("X-Demo-Key"))) {
                if (allow(clientIp(request))) {
                    reject(response, 401, "需要访问码（X-Demo-Key 头）");
                } else {
                    reject(response, 429, "请求过于频繁，请稍后再试");
                }
                return;
            }
            if (!allow(clientIp(request))) {
                reject(response, 429, "请求过于频繁，请稍后再试");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** 与 MVC 一致的解码路径；servletPath 为空（部分 mock/嵌入式场景）时回退原始 URI。 */
    private static String effectivePath(HttpServletRequest request) {
        String path = request.getServletPath();
        return (path == null || path.isEmpty()) ? request.getRequestURI() : path;
    }

    /** 密钥失败与成功一样占用配额，爆破尝试被压到与正常使用相同的速率。 */
    private boolean allow(String ip) {
        Deque<Long> hits = recentHits.computeIfAbsent(ip, k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        synchronized (hits) {
            while (!hits.isEmpty() && now - hits.peekFirst() >= WINDOW_MS) {
                hits.pollFirst();
            }
            if (hits.size() >= LIMIT_PER_MINUTE) {
                return false;
            }
            hits.addLast(now);
        }
        evictIfNeeded(now);
        return true;
    }

    private void evictIfNeeded(long now) {
        if (recentHits.size() < MAX_TRACKED_IPS) {
            return;
        }
        recentHits.entrySet().removeIf(e -> {
            Deque<Long> d = e.getValue();
            synchronized (d) {
                return d.isEmpty() || now - d.peekLast() >= WINDOW_MS;
            }
        });
        if (recentHits.size() >= MAX_TRACKED_IPS) {
            recentHits.clear();
        }
    }

    private String clientIp(HttpServletRequest request) {
        String cf = request.getHeader("CF-Connecting-IP");
        return isBlank(cf) ? request.getRemoteAddr() : cf;
    }

    /** 恒定时间比较，避免逐字节短路泄露前缀。 */
    private static boolean keyMatches(String expected, String provided) {
        if (isBlank(provided)) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean noneMatch(String path, List<String> prefixes) {
        return prefixes.stream().noneMatch(path::startsWith);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
