package com.lockin.tabstream.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servlet filter that enforces a sliding-window rate limit per client IP address.
 *
 * <p>Only requests to {@code /api/**} paths are subject to limiting.
 * WebSocket upgrade requests ({@code /ws/**}) and the H2 console
 * ({@code /h2-console/**}) are passed through unconditionally.
 *
 * <p>When a client exceeds the configured threshold the filter short-circuits
 * the request with HTTP 429 and a JSON error body — no downstream handler is
 * invoked.
 *
 * <p>Implementation notes:
 * <ul>
 *   <li>The sliding window is maintained as a {@link Deque} of epoch-millisecond
 *       timestamps. On each request the deque is first pruned of entries older
 *       than {@code windowSeconds}, then its size is checked against
 *       {@code maxRequests}.</li>
 *   <li>{@link ConcurrentHashMap} is used for the per-IP map so that concurrent
 *       requests from different IPs never contend on the same lock. Access to
 *       an individual IP's deque is synchronised on that deque to prevent races
 *       between threads handling requests from the same IP at the same time.</li>
 * </ul>
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** Maximum number of requests allowed within the time window. */
    @Value("${scraper.rate-limit.max-requests:30}")
    private int maxRequests;

    /** Duration of the sliding window in seconds. */
    @Value("${scraper.rate-limit.window-seconds:60}")
    private int windowSeconds;

    /**
     * Per-IP sliding window: maps a client IP string to a deque of request
     * timestamps (epoch milliseconds, newest at the tail).
     *
     * <p>Entries are never explicitly evicted — the deque for an IP naturally
     * drains itself as old timestamps fall outside the window. For a long-lived
     * service with many unique IPs a periodic cleanup task could be added, but
     * for this use case (local/LAN traffic from a browser extension) the memory
     * footprint remains negligible.
     */
    private final ConcurrentHashMap<String, Deque<Long>> requestLog = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Filter logic
    // -------------------------------------------------------------------------

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // Only rate-limit /api/** — let WebSocket upgrades and H2 console through
        if (!path.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = getClientIp(request);

        if (isRateLimited(clientIp)) {
            log.warn("Rate limit exceeded for IP='{}' path='{}' limit={}/{} s",
                    clientIp, path, maxRequests, windowSeconds);

            response.setStatus(429);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"ok\":false,\"error\":\"Rate limit exceeded. Try again later.\"}");
            return; // do NOT call filterChain — request is fully handled here
        }

        log.debug("Rate-limit check passed for IP='{}' path='{}'", clientIp, path);
        filterChain.doFilter(request, response);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Resolve the real client IP address.
     *
     * <p>When the application sits behind a reverse proxy the actual client IP
     * is forwarded in the {@code X-Forwarded-For} header. The first value in
     * the (potentially comma-separated) list is the original client; subsequent
     * values are intermediate proxies.
     *
     * <p>Falls back to {@link HttpServletRequest#getRemoteAddr()} when the header
     * is absent or blank.
     *
     * @param request the current HTTP request
     * @return a non-null IP string (may be {@code "unknown"} in edge cases)
     */
    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // Take only the first (original client) address
            String firstIp = forwarded.split(",")[0].trim();
            if (!firstIp.isEmpty()) {
                return firstIp;
            }
        }

        String remoteAddr = request.getRemoteAddr();
        return remoteAddr != null ? remoteAddr : "unknown";
    }

    /**
     * Determine whether the given IP has exceeded the rate limit.
     *
     * <p>This method is synchronised on the per-IP deque to make the
     * prune → size-check → record sequence atomic for a single IP, while
     * allowing full concurrency across different IPs.
     *
     * @param ip the resolved client IP address
     * @return {@code true} if the client should be blocked, {@code false} otherwise
     */
    private boolean isRateLimited(String ip) {
        long nowMs         = System.currentTimeMillis();
        long windowMs      = (long) windowSeconds * 1_000L;
        long cutoffMs      = nowMs - windowMs;

        // computeIfAbsent ensures a deque exists for this IP atomically
        Deque<Long> timestamps = requestLog.computeIfAbsent(ip, k -> new ArrayDeque<>());

        synchronized (timestamps) {
            // Prune timestamps that have fallen outside the current window
            while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoffMs) {
                timestamps.pollFirst();
            }

            if (timestamps.size() >= maxRequests) {
                // Do NOT record this request — it is being rejected
                return true;
            }

            // Record this request's timestamp
            timestamps.addLast(nowMs);
            return false;
        }
    }
}
