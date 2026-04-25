package com.lockin.tabstream.config;

import com.lockin.tabstream.websocket.PageStreamHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket configuration that:
 * <ul>
 *   <li>Registers {@link PageStreamHandler} at {@code /ws/stream}</li>
 *   <li>Allows connections from any origin (including chrome-extension://)</li>
 *   <li>Schedules a heartbeat PING broadcast every 30 seconds so that
 *       idle connections are kept alive through proxies and NAT gateways</li>
 * </ul>
 *
 * <p>{@code @EnableScheduling} is placed here (rather than on the main
 * application class) to keep scheduling concerns co-located with the only
 * scheduled task in this service.
 */
@Configuration
@EnableWebSocket
@EnableScheduling
public class WebSocketConfig implements WebSocketConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    private final PageStreamHandler pageStreamHandler;

    public WebSocketConfig(PageStreamHandler pageStreamHandler) {
        this.pageStreamHandler = pageStreamHandler;
    }

    // -------------------------------------------------------------------------
    // WebSocketConfigurer
    // -------------------------------------------------------------------------

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(pageStreamHandler, "/ws/stream")
                // Permit any origin so that the Chrome extension (chrome-extension://<id>)
                // and local development clients can connect without CORS rejection.
                .setAllowedOriginPatterns("*");

        log.info("WebSocket handler registered at ws://localhost:8081/ws/stream");
    }

    // -------------------------------------------------------------------------
    // Scheduled heartbeat
    // -------------------------------------------------------------------------

    /**
     * Broadcast a PING frame to every active WebSocket session every 30 seconds.
     * Keeping the connection alive prevents proxies and load balancers with short
     * idle-timeout settings from silently dropping established sessions.
     */
    @Scheduled(fixedDelay = 30_000)
    public void sendHeartbeat() {
        log.debug("Scheduler firing heartbeat PING to all active WebSocket sessions");
        pageStreamHandler.broadcastPing();
    }
}
