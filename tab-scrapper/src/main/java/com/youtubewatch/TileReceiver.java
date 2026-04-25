package com.youtubewatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * TileReceiver
 *
 * A lightweight HTTP server (built on the JDK's bundled com.sun.net.httpserver)
 * that acts as the local backend for the "YT Tile Scraper" Chrome extension.
 *
 * Endpoints
 * ─────────
 *   POST http://127.0.0.1:8765/tiles   ← extension POSTs tile batches here
 *   GET  http://127.0.0.1:8765/health  ← liveness / stats check
 *   OPTIONS *                          ← CORS preflight (handled automatically)
 *
 * Each accepted scrape batch is written to:
 *   logs/tiles_<epoch-millis>.json
 *
 * Run via Gradle:
 *   ./gradlew runReceiver
 *
 * Override port:
 *   ./gradlew runReceiver -Pport=9000
 */
public class TileReceiver {

    // ── Defaults ──────────────────────────────────────────────────────────────

    public static final int DEFAULT_PORT = 8765;
    public static final String TILES_PATH = "/tiles";
    public static final String HEALTH_PATH = "/health";

    // ── Instance state ────────────────────────────────────────────────────────

    private final int port;
    private final File logsDir;
    private final ObjectMapper mapper;

    /**
     * In-memory deduplication set.  Keys are video IDs (preferred) or full
     * URLs (fallback).  Prevents the same video tile from being written to disk
     * more than once across repeated Watch-mode scrapes of the same page.
     */
    private final Set<String> seenKeys;

    private final AtomicInteger totalReceived = new AtomicInteger(0);
    private final AtomicInteger totalAccepted = new AtomicInteger(0);

    /** Fetches channel name + description for tiles silently in the background. */
    private final VideoEnricher enricher;

    /** The underlying JDK HTTP server — null until {@link #start()} is called. */
    private HttpServer server;

    // ── Construction ──────────────────────────────────────────────────────────

    public TileReceiver(int port) {
        this.port = port;
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.seenKeys = Collections.synchronizedSet(new LinkedHashSet<>());
        this.enricher = new VideoEnricher();

        this.logsDir = Paths.get("logs").toFile();
        if (this.logsDir.mkdirs()) {
            System.out.println(
                "[TileReceiver] Created logs directory : " +
                    logsDir.getAbsolutePath()
            );
        } else {
            System.out.println(
                "[TileReceiver] Using logs directory   : " +
                    logsDir.getAbsolutePath()
            );
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Binds the server socket and starts accepting connections.
     * Returns immediately — the server runs on its own thread pool.
     *
     * @throws IOException if the port is already in use or the socket cannot bind.
     */
    public void start() throws IOException {
        server = HttpServer.create(
            new InetSocketAddress("127.0.0.1", port),
            /*backlog*/ 32
        );

        server.createContext(TILES_PATH, this::handleTiles);
        server.createContext(HEALTH_PATH, this::handleHealth);
        // Root path also serves the health response for easy browser checks
        server.createContext("/", this::handleHealth);

        // Use a small fixed thread-pool so concurrent extension requests don't block each other
        server.setExecutor(
            Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "tile-receiver-worker");
                t.setDaemon(true);
                return t;
            })
        );

        server.start();

        System.out.println(
            "[TileReceiver] ✔  Listening on http://127.0.0.1:" +
                port +
                TILES_PATH
        );
        System.out.println(
            "[TileReceiver]    Health check : http://127.0.0.1:" +
                port +
                HEALTH_PATH
        );
        System.out.println(
            "[TileReceiver]    Logs folder  : " + logsDir.getAbsolutePath()
        );
        System.out.println("[TileReceiver]    Press Ctrl+C to stop.\n");
    }

    /** Gracefully shuts down the HTTP server with a 1-second drain window. */
    public void stop() {
        if (server != null) {
            server.stop(1);
            System.out.println("[TileReceiver] Server stopped.");
        }
    }

    // ── Request handlers ──────────────────────────────────────────────────────

    /**
     * Handles {@code POST /tiles}.
     *
     * Expected request body:
     * <pre>
     * {
     *   "tiles"    : [ { "video_id": "...", "title": "...", ... }, ... ],
     *   "page_url" : "https://www.youtube.com/..."
     * }
     * </pre>
     *
     * Response body:
     * <pre>
     * { "ok": true, "received": 12, "accepted": 5 }
     * </pre>
     */
    private void handleTiles(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);

        // ── CORS preflight ─────────────────────────────────────────────────
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }

        // ── Method guard ───────────────────────────────────────────────────
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(
                exchange,
                405,
                buildError("Only POST is accepted on /tiles")
            );
            return;
        }

        // ── Read body ──────────────────────────────────────────────────────
        String rawBody;
        try (InputStream is = exchange.getRequestBody()) {
            rawBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        if (rawBody == null || rawBody.isBlank()) {
            sendJson(exchange, 400, buildError("Request body is empty"));
            return;
        }

        // ── Parse JSON ─────────────────────────────────────────────────────
        JsonNode root;
        try {
            root = mapper.readTree(rawBody);
        } catch (Exception e) {
            sendJson(
                exchange,
                400,
                buildError("Malformed JSON: " + e.getMessage())
            );
            return;
        }

        JsonNode tilesNode = root.get("tiles");
        if (tilesNode == null || !tilesNode.isArray()) {
            sendJson(
                exchange,
                400,
                buildError("Missing or non-array 'tiles' field")
            );
            return;
        }

        String pageUrl = root.has("page_url")
            ? root.get("page_url").asText("")
            : "";
        String receivedAt = Instant.now().toString();
        int inCount = tilesNode.size();

        totalReceived.addAndGet(inCount);

        // ── Deduplicate and enrich ─────────────────────────────────────────
        List<ObjectNode> accepted = new ArrayList<>();

        for (JsonNode tileNode : tilesNode) {
            if (!tileNode.isObject()) continue;

            ObjectNode tile = (ObjectNode) tileNode;

            // Determine the dedup key: prefer video_id, fall back to url
            String videoId = tile.has("video_id")
                ? tile.get("video_id").asText("").trim()
                : "";
            String url = tile.has("url")
                ? tile.get("url").asText("").trim()
                : "";
            String key = !videoId.isEmpty() ? videoId : url;

            // Skip if we've already logged this video in a previous scrape batch
            if (!key.isEmpty() && seenKeys.contains(key)) {
                continue;
            }
            if (!key.isEmpty()) {
                seenKeys.add(key);
            }

            // Stamp the tile with the server-side received time
            tile.put("received_at", receivedAt);

            // Back-fill page_url onto the tile if the extension didn't include it at tile level
            if (
                !tile.has("page_url") ||
                tile.get("page_url").asText("").isBlank()
            ) {
                tile.put("page_url", pageUrl);
            }

            accepted.add(tile);
        }

        int acceptedCount = accepted.size();
        totalAccepted.addAndGet(acceptedCount);

        System.out.printf(
            "[TileReceiver] received=%-4d  accepted=%-4d  total_unique=%d%n",
            inCount,
            acceptedCount,
            seenKeys.size()
        );

        // ── Respond immediately so the extension popup updates at once ─────
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("received", inCount);
        resp.put("accepted", acceptedCount);
        sendJson(exchange, 200, resp);

        // ── Enrich + save in the background ───────────────────────────────
        // For every accepted tile that is missing a channel name or description,
        // fetch youtube.com/watch?v={id} silently and fill in the blanks.
        // The log file is written only after enrichment completes (or times out).
        if (!accepted.isEmpty()) {
            final List<ObjectNode> toSave = accepted;
            final String finalUrl = pageUrl;
            final String finalTs = receivedAt;

            List<CompletableFuture<Void>> futures = toSave
                .parallelStream()
                .filter(tile -> needsEnrichment(tile))
                .map(tile -> {
                    String vid = tile.path("video_id").asText("").trim();
                    return enricher
                        .enrichAsync(vid)
                        .thenAccept(data -> applyEnrichment(tile, data));
                })
                .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(20, TimeUnit.SECONDS)
                .whenComplete((v, err) -> {
                    if (err != null) {
                        System.err.println(
                            "[TileReceiver] Enrichment timed out — saving partial data."
                        );
                    }
                    saveBatch(toSave, finalUrl, finalTs);
                });
        }
    }

    // ── Enrichment helpers ────────────────────────────────────────────────────

    /**
     * Returns true when a tile has a video ID but is still missing either its
     * channel name or description — meaning we should fetch the watch page.
     */
    private boolean needsEnrichment(ObjectNode tile) {
        String vid = tile.path("video_id").asText("").trim();
        String channel = tile.path("channel").asText("").trim();
        String desc = tile.path("description").asText("").trim();
        return !vid.isEmpty() && (channel.isEmpty() || desc.isEmpty());
    }

    /**
     * Writes enriched channel / description data back onto the tile node,
     * only overwriting fields that were previously blank.
     */
    private void applyEnrichment(
        ObjectNode tile,
        VideoEnricher.EnrichedData data
    ) {
        if (
            !data.channel().isBlank() &&
            tile.path("channel").asText("").isBlank()
        ) {
            tile.put("channel", data.channel());
        }
        if (
            !data.description().isBlank() &&
            tile.path("description").asText("").isBlank()
        ) {
            tile.put("description", data.description());
        }
    }

    /**
     * Handles {@code GET /health} and {@code GET /}.
     * Returns a simple JSON stats object — useful for quick sanity checks
     * (e.g. {@code curl http://127.0.0.1:8765/health}).
     */
    private void handleHealth(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("ok", true);
        stats.put("status", "running");
        stats.put("port", port);
        stats.put("received", totalReceived.get());
        stats.put("accepted", totalAccepted.get());
        stats.put("unique", seenKeys.size());
        stats.put("logs_dir", logsDir.getAbsolutePath());
        stats.put("server_time", Instant.now().toString());

        sendJson(exchange, 200, stats);
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    /**
     * Saves the accepted tiles from one scrape batch to a timestamped JSON file.
     *
     * File format:
     * <pre>
     * {
     *   "page_url"    : "https://www.youtube.com/",
     *   "received_at" : "2024-07-15T10:30:00.123Z",
     *   "count"       : 5,
     *   "tiles"       : [ { ... }, ... ]
     * }
     * </pre>
     *
     * @param tiles       Non-empty list of accepted, enriched tile objects.
     * @param pageUrl     The YouTube page URL the extension scraped.
     * @param receivedAt  ISO-8601 timestamp of when the POST arrived.
     */
    private void saveBatch(
        List<ObjectNode> tiles,
        String pageUrl,
        String receivedAt
    ) {
        String filename = "tiles_" + System.currentTimeMillis() + ".json";
        File outputFile = new File(logsDir, filename);

        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("page_url", pageUrl);
        batch.put("received_at", receivedAt);
        batch.put("count", tiles.size());
        batch.put("tiles", tiles);

        try {
            mapper.writeValue(outputFile, batch);
            System.out.println(
                "[TileReceiver] Saved  ➜  " + outputFile.getAbsolutePath()
            );
        } catch (IOException e) {
            System.err.println(
                "[TileReceiver] ERROR writing batch file: " + e.getMessage()
            );
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    /**
     * Serialises {@code body} to JSON bytes and writes them back to the client.
     *
     * @param exchange The active HTTP exchange.
     * @param status   HTTP status code (200, 400, 405, …).
     * @param body     Any Jackson-serialisable object (Map, POJO, …).
     */
    private void sendJson(HttpExchange exchange, int status, Object body)
        throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange
            .getResponseHeaders()
            .set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
        exchange.close();
    }

    /**
     * Adds CORS headers required for the Chrome extension's fetch() calls to
     * succeed.  Extensions are treated as cross-origin callers by Chrome even
     * when the target is localhost.
     */
    private void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange
            .getResponseHeaders()
            .set("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
        exchange
            .getResponseHeaders()
            .set("Access-Control-Allow-Headers", "Content-Type");
    }

    /** Builds a simple {@code {"ok": false, "error": "..."}} error map. */
    private Map<String, Object> buildError(String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("ok", false);
        err.put("error", message);
        return err;
    }

    // ── main ──────────────────────────────────────────────────────────────────

    /**
     * Entry point used by {@code ./gradlew runReceiver}.
     *
     * <p>Accepts an optional {@code --port <number>} argument.  When launched
     * via Gradle with {@code -Pport=9000}, the build script translates it into
     * a JVM system property and this method reads {@code System.getProperty("receiver.port")}
     * as the primary source, then falls back to the CLI arg, then to
     * {@link #DEFAULT_PORT}.</p>
     */
    public static void main(String[] args) {
        int port = resolvePort(args);

        TileReceiver receiver = new TileReceiver(port);

        // Shutdown hook — fires on Ctrl+C or SIGTERM
        Runtime.getRuntime().addShutdownHook(
            new Thread(
                () -> {
                    System.out.println(
                        "\n[TileReceiver] Shutdown signal received."
                    );
                    receiver.stop();
                },
                "receiver-shutdown-hook"
            )
        );

        try {
            receiver.start();
            // Park the main thread indefinitely; the server runs on daemon threads
            Thread.currentThread().join();
        } catch (IOException e) {
            System.err.println(
                "[TileReceiver] FATAL — could not bind port " +
                    port +
                    ": " +
                    e.getMessage()
            );
            System.err.println(
                "[TileReceiver] Is another process already using that port?"
            );
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Port resolution priority:
     * <ol>
     *   <li>{@code -Dreceiver.port=N} JVM system property (set by Gradle {@code -Pport=N})</li>
     *   <li>{@code --port N} command-line argument</li>
     *   <li>{@link #DEFAULT_PORT} (8765)</li>
     * </ol>
     */
    private static int resolvePort(String[] args) {
        // 1. JVM system property (Gradle -Pport= sets this via jvmArgs in build.gradle)
        String sysProp = System.getProperty("receiver.port");
        if (sysProp != null && !sysProp.isBlank()) {
            try {
                return Integer.parseInt(sysProp.trim());
            } catch (NumberFormatException ignored) {}
        }
        // 2. CLI --port argument
        for (int i = 0; i < args.length - 1; i++) {
            if ("--port".equals(args[i])) {
                try {
                    return Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException ignored) {}
            }
        }
        // 3. Default
        return DEFAULT_PORT;
    }
}
