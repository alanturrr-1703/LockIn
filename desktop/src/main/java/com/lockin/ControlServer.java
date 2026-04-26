package com.lockin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ControlServer
 *
 * Lightweight HTTP server (port 7432) that acts as the bridge between the
 * LockIn JavaFX desktop app and the Chrome extension.
 *
 * Endpoints
 * ─────────
 *   GET  /status    ← extension polls this before every scrape cycle
 *   POST /stats     ← extension reports blocked tile count after each scrape
 *   POST /toggle    ← desktop UI calls this to flip enabled state
 *   GET  /health    ← liveness check
 *   POST /profiles  ← extension syncs profiles; desktop pushes pending changes
 */
public class ControlServer {

    // ── Constants ─────────────────────────────────────────────────────────────

    public static final int PORT = 7432;

    // ── State ─────────────────────────────────────────────────────────────────

    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final AtomicInteger totalBlocked = new AtomicInteger(0);
    private final AtomicInteger totalScrapes = new AtomicInteger(0);
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile String lastProfile = "—";
    private volatile String lastScrapeAt = "—";

    // ── Profile state ─────────────────────────────────────────────────────────

    private volatile List<Map<String, Object>> profiles = new ArrayList<>();
    private volatile String activeProfileId = "";
    private volatile boolean profilesDirty = false;
    private volatile List<Map<String, Object>> pendingProfiles = null;
    private volatile String pendingActiveProfileId = null;
    private Runnable onProfilesUpdated; // callback for JavaFX UI refresh

    private HttpServer server;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void start() throws IOException {
        server = HttpServer.create(
            new InetSocketAddress("127.0.0.1", PORT),
            32
        );

        server.createContext("/status", this::handleStatus);
        server.createContext("/stats", this::handleStats);
        server.createContext("/toggle", this::handleToggle);
        server.createContext("/health", this::handleHealth);
        server.createContext("/profiles", this::handleProfiles);
        server.createContext("/", this::handleHealth);

        server.setExecutor(
            Executors.newFixedThreadPool(3, r -> {
                Thread t = new Thread(r, "lockin-control");
                t.setDaemon(true);
                return t;
            })
        );

        server.start();
        System.out.println(
            "[LockIn Desktop] Control server → http://127.0.0.1:" + PORT
        );
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            System.out.println("[LockIn Desktop] Control server stopped.");
        }
    }

    // ── Public API (for JavaFX UI) ────────────────────────────────────────────

    public boolean isEnabled() {
        return enabled.get();
    }

    public int getTotalBlocked() {
        return totalBlocked.get();
    }

    public int getTotalScrapes() {
        return totalScrapes.get();
    }

    public String getLastProfile() {
        return lastProfile;
    }

    public String getLastScrapeAt() {
        return lastScrapeAt;
    }

    public void setEnabled(boolean value) {
        enabled.set(value);
        System.out.println(
            "[LockIn Desktop] Extension " + (value ? "enabled" : "paused")
        );
    }

    public List<Map<String, Object>> getProfiles() {
        return Collections.unmodifiableList(profiles);
    }

    public String getActiveProfileId() {
        return activeProfileId;
    }

    public String getActiveProfilePrompt() {
        String aid = activeProfileId;
        return profiles
            .stream()
            .filter(p -> aid.equals(String.valueOf(p.getOrDefault("id", ""))))
            .findFirst()
            .map(p -> String.valueOf(p.getOrDefault("prompt", "")))
            .orElse("");
    }

    public void setOnProfilesUpdated(Runnable cb) {
        this.onProfilesUpdated = cb;
    }

    /**
     * Called by the UI when the user creates a new profile.
     * Stages the updated list and marks the server dirty so the extension
     * receives the change on its next /profiles poll.
     */
    public void addPendingProfile(Map<String, Object> profile) {
        List<Map<String, Object>> next = new ArrayList<>(profiles);
        next.add(profile);
        profiles = next; // immediate UI update
        activeProfileId = (String) profile.get("id"); // immediate UI update
        pendingProfiles = next;
        pendingActiveProfileId = activeProfileId;
        profilesDirty = true;
    }

    /**
     * Called by the UI when the user activates a profile.
     * Stages the activation and marks the server dirty so the extension
     * receives the change on its next /profiles poll.
     */
    public void setPendingActiveProfile(String profileId) {
        activeProfileId = profileId; // immediate UI update
        pendingProfiles = new ArrayList<>(profiles);
        pendingActiveProfileId = profileId;
        profilesDirty = true;
    }

    // ── Handlers ──────────────────────────────────────────────────────────────

    /**
     * GET /status
     * Called by the extension before every scrape.
     * Response: { "enabled": true|false }
     */
    private void handleStatus(HttpExchange ex) throws IOException {
        addCors(ex);
        if (preflight(ex)) return;
        sendJson(ex, 200, Map.of("enabled", enabled.get()));
    }

    /**
     * POST /stats
     * Extension reports results after each scrape cycle.
     * Body: { "blocked": N, "sent": N, "profile": "...", "scrape_at": "..." }
     */
    private void handleStats(HttpExchange ex) throws IOException {
        addCors(ex);
        if (preflight(ex)) return;

        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            try {
                var body = mapper.readTree(ex.getRequestBody());
                int blocked = body.path("blocked").asInt(0);
                totalBlocked.addAndGet(blocked);
                totalScrapes.incrementAndGet();

                String profile = body.path("profile").asText("");
                if (!profile.isBlank()) lastProfile = profile;

                String at = body.path("scrape_at").asText("");
                if (!at.isBlank()) lastScrapeAt = at;
            } catch (Exception ignored) {}
        }

        sendJson(
            ex,
            200,
            Map.of(
                "ok",
                true,
                "total_blocked",
                totalBlocked.get(),
                "total_scrapes",
                totalScrapes.get()
            )
        );
    }

    /**
     * POST /toggle
     * Flips the enabled state and returns the new state.
     * Used as an alternative to the JavaFX UI toggle (e.g. from a script).
     */
    private void handleToggle(HttpExchange ex) throws IOException {
        addCors(ex);
        if (preflight(ex)) return;
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            enabled.set(!enabled.get());
        }
        sendJson(ex, 200, Map.of("enabled", enabled.get()));
    }

    /**
     * GET /health
     */
    private void handleHealth(HttpExchange ex) throws IOException {
        addCors(ex);
        if (preflight(ex)) return;
        sendJson(
            ex,
            200,
            Map.of(
                "ok",
                true,
                "enabled",
                enabled.get(),
                "total_blocked",
                totalBlocked.get(),
                "total_scrapes",
                totalScrapes.get(),
                "last_profile",
                lastProfile,
                "last_scrape_at",
                lastScrapeAt
            )
        );
    }

    /**
     * POST /profiles
     * Extension syncs its current profiles here on every scrape cycle.
     * If the desktop has pending changes (profilesDirty), respond with those
     * changes so the extension can apply them to chrome.storage.local.
     * Response when dirty:   { "dirty": true,  "profiles": [...], "activeProfileId": "..." }
     * Response when clean:   { "dirty": false }
     */
    private void handleProfiles(HttpExchange ex) throws IOException {
        addCors(ex);
        if (preflight(ex)) return;
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "POST only"));
            return;
        }
        try {
            var body = mapper.readTree(ex.getRequestBody());
            // Always update our store from what the extension sent (source of truth)
            var arr = body.path("profiles");
            if (arr.isArray()) {
                List<Map<String, Object>> incoming = new ArrayList<>();
                for (var node : arr) {
                    incoming.add(mapper.convertValue(node, Map.class));
                }
                if (!profilesDirty) {
                    // Only update our store if we're not about to overwrite with pending changes
                    profiles = incoming;
                    activeProfileId = body.path("activeProfileId").asText("");
                    if (onProfilesUpdated != null) onProfilesUpdated.run();
                }
            }
        } catch (Exception ignored) {}

        if (profilesDirty) {
            var resp = Map.of(
                "dirty",
                true,
                "profiles",
                pendingProfiles,
                "activeProfileId",
                pendingActiveProfileId != null ? pendingActiveProfileId : ""
            );
            profilesDirty = false;
            pendingProfiles = null;
            pendingActiveProfileId = null;
            sendJson(ex, 200, resp);
        } else {
            sendJson(ex, 200, Map.of("dirty", false));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void addCors(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.add("Access-Control-Allow-Origin", "*");
        h.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        h.add("Access-Control-Allow-Headers", "Content-Type");
    }

    /** Returns true if this was a CORS preflight (caller should return). */
    private boolean preflight(HttpExchange ex) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    private void sendJson(HttpExchange ex, int status, Object body)
        throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
