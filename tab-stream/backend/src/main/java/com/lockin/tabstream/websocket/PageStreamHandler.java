package com.lockin.tabstream.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lockin.tabstream.dto.PagePayloadDTO;
import com.lockin.tabstream.dto.ParsedPageDTO;
import com.lockin.tabstream.service.PageProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket handler for the /ws/stream endpoint.
 *
 * <p>Protocol summary:
 * <ul>
 *   <li>Client → server: {@code {"type":"PAGE_DATA","payload":{title,url,html,text,timestamp}}}</li>
 *   <li>Server → client: {@code {"type":"ANALYSIS","payload":{...ParsedPageDTO},"timestamp":"..."}}</li>
 *   <li>Server → client: {@code {"type":"ERROR","message":"...","timestamp":"..."}}</li>
 *   <li>Server → client: {@code {"type":"PING","timestamp":"..."}} (heartbeat every 30 s)</li>
 * </ul>
 *
 * <p>Session lifecycle:
 * <ol>
 *   <li>{@link #afterConnectionEstablished} — registers session in the active-sessions map</li>
 *   <li>{@link #handleTextMessage} — processes incoming PAGE_DATA frames</li>
 *   <li>{@link #afterConnectionClosed} — removes session from the map</li>
 * </ol>
 *
 * <p>Thread safety: {@link ConcurrentHashMap} is used for the sessions map so that
 * the scheduled heartbeat and concurrent message handlers never race on the map itself.
 * Individual {@link WebSocketSession#sendMessage} calls are synchronised on the session
 * object to prevent interleaved writes from the heartbeat and a message-response path.
 */
@Component
public class PageStreamHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(PageStreamHandler.class);

    /** All currently open WebSocket sessions keyed by their unique session id. */
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    private final PageProcessingService pageProcessingService;
    private final ObjectMapper objectMapper;

    public PageStreamHandler(PageProcessingService pageProcessingService,
                             ObjectMapper objectMapper) {
        this.pageProcessingService = pageProcessingService;
        this.objectMapper          = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Lifecycle callbacks
    // -------------------------------------------------------------------------

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        log.info("WebSocket connection established: sessionId='{}' remoteAddress='{}'",
                session.getId(), session.getRemoteAddress());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("WebSocket connection closed: sessionId='{}' status='{}'",
                session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("Transport error on sessionId='{}': {}", session.getId(), exception.getMessage(), exception);
        sessions.remove(session.getId());
        // Attempt a graceful close; ignore any secondary IO failure
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.SERVER_ERROR);
            }
        } catch (IOException ignored) {
            // Nothing useful we can do at this point
        }
    }

    // -------------------------------------------------------------------------
    // Message handling
    // -------------------------------------------------------------------------

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String sessionId = session.getId();
        log.debug("Received message from sessionId='{}' payloadLength={}", sessionId, message.getPayloadLength());

        JsonNode root;
        try {
            root = objectMapper.readTree(message.getPayload());
        } catch (Exception e) {
            log.warn("Malformed JSON received from sessionId='{}': {}", sessionId, e.getMessage());
            sendError(session, "Malformed JSON: " + e.getMessage());
            return;
        }

        JsonNode typeNode = root.get("type");
        if (typeNode == null || typeNode.asText().isBlank()) {
            log.warn("Message from sessionId='{}' missing 'type' field", sessionId);
            sendError(session, "Missing required field: 'type'");
            return;
        }

        String type = typeNode.asText();

        switch (type) {
            case "PAGE_DATA" -> handlePageData(session, root);
            default -> {
                log.warn("Unknown message type '{}' from sessionId='{}'", type, sessionId);
                sendError(session, "Unknown message type: '" + type + "'");
            }
        }
    }

    // -------------------------------------------------------------------------
    // PAGE_DATA processing
    // -------------------------------------------------------------------------

    /**
     * Extract the {@code payload} node, deserialise it to a {@link PagePayloadDTO},
     * run it through the processing pipeline, and send back an ANALYSIS frame.
     */
    private void handlePageData(WebSocketSession session, JsonNode root) {
        String sessionId = session.getId();

        JsonNode payloadNode = root.get("payload");
        if (payloadNode == null || payloadNode.isNull()) {
            log.warn("PAGE_DATA message from sessionId='{}' has no 'payload' field", sessionId);
            sendError(session, "PAGE_DATA message must include a 'payload' object");
            return;
        }

        PagePayloadDTO payload;
        try {
            payload = objectMapper.treeToValue(payloadNode, PagePayloadDTO.class);
        } catch (Exception e) {
            log.warn("Failed to deserialise PAGE_DATA payload from sessionId='{}': {}", sessionId, e.getMessage());
            sendError(session, "Invalid PAGE_DATA payload: " + e.getMessage());
            return;
        }

        // Basic null-guard; full Bean Validation is not applied over WebSocket,
        // so we perform a lightweight check here instead.
        if (payload.getUrl() == null || payload.getUrl().isBlank()) {
            sendError(session, "PAGE_DATA payload must contain a non-blank 'url'");
            return;
        }

        log.info("Processing PAGE_DATA from sessionId='{}' url='{}'", sessionId, payload.getUrl());

        ParsedPageDTO result;
        try {
            result = pageProcessingService.process(payload, "websocket");
        } catch (Exception e) {
            log.error("Processing failed for sessionId='{}' url='{}': {}", sessionId, payload.getUrl(), e.getMessage(), e);
            sendError(session, "Processing error: " + e.getMessage());
            return;
        }

        sendAnalysis(session, result);
    }

    // -------------------------------------------------------------------------
    // Outbound message helpers
    // -------------------------------------------------------------------------

    /**
     * Send an ANALYSIS frame carrying the full {@link ParsedPageDTO} as its payload.
     */
    private void sendAnalysis(WebSocketSession session, ParsedPageDTO dto) {
        try {
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("type", "ANALYSIS");
            envelope.set("payload", objectMapper.valueToTree(dto));
            envelope.put("timestamp", Instant.now().toString());

            sendText(session, objectMapper.writeValueAsString(envelope));
            log.debug("Sent ANALYSIS frame to sessionId='{}' dtoId='{}'", session.getId(), dto.getId());
        } catch (Exception e) {
            log.error("Failed to serialise/send ANALYSIS frame to sessionId='{}': {}",
                    session.getId(), e.getMessage(), e);
        }
    }

    /**
     * Send an ERROR frame with a human-readable message.
     */
    private void sendError(WebSocketSession session, String message) {
        try {
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("type", "ERROR");
            envelope.put("message", message);
            envelope.put("timestamp", Instant.now().toString());

            sendText(session, objectMapper.writeValueAsString(envelope));
            log.debug("Sent ERROR frame to sessionId='{}': {}", session.getId(), message);
        } catch (Exception e) {
            log.error("Failed to send ERROR frame to sessionId='{}': {}", session.getId(), e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Public heartbeat (called by scheduled task in WebSocketConfig)
    // -------------------------------------------------------------------------

    /**
     * Broadcast a PING frame to every currently open session.
     * Called by the {@code @Scheduled} method in {@code WebSocketConfig} every 30 s.
     *
     * <p>Sessions that are no longer open are removed from the map opportunistically
     * so the collection stays clean without a separate reaper thread.
     */
    public void broadcastPing() {
        if (sessions.isEmpty()) {
            log.debug("broadcastPing: no active sessions — skipping");
            return;
        }

        String pingJson;
        try {
            ObjectNode ping = objectMapper.createObjectNode();
            ping.put("type", "PING");
            ping.put("timestamp", Instant.now().toString());
            pingJson = objectMapper.writeValueAsString(ping);
        } catch (Exception e) {
            log.error("Failed to construct PING frame: {}", e.getMessage(), e);
            return;
        }

        log.debug("Broadcasting PING to {} active session(s)", sessions.size());

        sessions.forEach((id, session) -> {
            if (!session.isOpen()) {
                log.debug("Removing stale closed session '{}' during PING broadcast", id);
                sessions.remove(id);
                return;
            }
            try {
                sendText(session, pingJson);
            } catch (Exception e) {
                log.warn("Failed to send PING to sessionId='{}': {}", id, e.getMessage());
                sessions.remove(id);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Low-level send (synchronised per session to prevent interleaved writes)
    // -------------------------------------------------------------------------

    /**
     * Send a text frame to a single session.
     *
     * <p>Synchronising on the session object prevents the scheduler thread (PING)
     * and the message-handler thread from writing to the same session simultaneously,
     * which would cause a {@code IllegalStateException} from the underlying WebSocket
     * implementation.
     */
    private void sendText(WebSocketSession session, String text) throws IOException {
        synchronized (session) {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(text));
            } else {
                log.debug("Attempted to send to closed session '{}' — skipped", session.getId());
            }
        }
    }
}
