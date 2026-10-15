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
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 读写分级访问控制：
 * - 读（/api/chat、/api/search）：访客访问码（X-Demo-Key 头或 key 参数）+ 每 IP 每分钟限速；
 * - 写（/api/documents）：管理员密钥（X-Admin-Key 头），与访客码分离，防灌库/配额滥用。
 * 两种密钥均未配置时放行（本地开发）。真实 IP 取 CF-Connecting-IP，缺省 remoteAddr。
 */
@Component
public class DemoKeyFilter extends OncePerRequestFilter {

    private static final List<String> READ_PREFIXES = List.of("/api/chat", "/api/search");
    private static final List<String> WRITE_PREFIXES = List.of("/api/documents");
    private static final int LIMIT_PER_MINUTE = 20;
    private static final long WINDOW_MS = Duration.ofMinutes(1).toMillis();
    /** 兜底防 Map 无限膨胀（伪造不同来源），演示规模直接整体重置。 */
    private static final int MAX_TRACKED_IPS = 10_000;

    private final String demoKey;
    private final String adminKey;
    private final Map<String, Deque<Long>> recentHits = new ConcurrentHashMap<>();

    public DemoKeyFilter(MiniRagProperties props) {
        this.demoKey = props.demoKey();
        this.adminKey = props.adminKey();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return (noneMatch(path, READ_PREFIXES) && noneMatch(path, WRITE_PREFIXES))
                || (isBlank(demoKey) && isBlank(adminKey));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!noneMatch(path, WRITE_PREFIXES)) {
            // 已部署（任一密钥配置）但缺 ADMIN_KEY 时对写入整体拒绝，避免裸奔
            if (isBlank(adminKey) || !adminKey.equals(request.getHeader("X-Admin-Key"))) {
                reject(response, 401, "入库接口需要管理员密钥（X-Admin-Key 头）");
                return;
            }
        } else {
            if (isBlank(demoKey)) {
                chain.doFilter(request, response);
                return;
            }
            String provided = request.getHeader("X-Demo-Key");
            if (isBlank(provided)) {
                provided = request.getParameter("key");
            }
            if (!demoKey.equals(provided)) {
                reject(response, 401, "需要访问码（X-Demo-Key 头或 key 参数）");
                return;
            }
            if (!allow(clientIp(request))) {
                reject(response, 429, "请求过于频繁，请稍后再试");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean allow(String ip) {
        if (recentHits.size() >= MAX_TRACKED_IPS) {
            recentHits.clear();
        }
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
            return true;
        }
    }

    private String clientIp(HttpServletRequest request) {
        String cf = request.getHeader("CF-Connecting-IP");
        return isBlank(cf) ? request.getRemoteAddr() : cf;
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
