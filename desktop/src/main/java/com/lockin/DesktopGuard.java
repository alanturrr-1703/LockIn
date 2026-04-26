package com.lockin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * DesktopGuard
 *
 * Scans ~/Desktop, classifies each file/folder by name against the active
 * profile using the local Ollama model, and quarantines irrelevant items by
 * moving them to ~/.lockin-quarantine/.
 *
 * Quarantine is fully reversible — restoreAll() moves everything back.
 * A JSON manifest at ~/.lockin-quarantine/manifest.json tracks what was moved.
 */
public class DesktopGuard {

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final String OLLAMA_URL = "http://localhost:11434/api/chat";
    private static final String MODEL = "llama3.2:3b";
    private static final int BATCH_SIZE = 10;

    private static final Pattern LABEL_RE = Pattern.compile(
        "^(\\d+)\\s*:\\s*(KEEP|BLOCK)",
        Pattern.CASE_INSENSITIVE
    );

    // ── Paths ─────────────────────────────────────────────────────────────────

    private final Path desktopPath = Path.of(
        System.getProperty("user.home"),
        "Desktop"
    );
    private final Path quarantineDir = Path.of(
        System.getProperty("user.home"),
        ".lockin-quarantine"
    );
    private final Path manifestPath = quarantineDir.resolve("manifest.json");

    // ── Infrastructure ────────────────────────────────────────────────────────

    private final ControlServer controlServer;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
        r -> {
            Thread t = new Thread(r, "desktop-guard");
            t.setDaemon(true);
            return t;
        }
    );
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    // ── Mutable state ─────────────────────────────────────────────────────────

    private volatile List<DesktopFile> lastScan = List.of();
    private volatile boolean scanning = false;
    private Runnable onScanComplete;

    // ── Result record ─────────────────────────────────────────────────────────

    public record DesktopFile(
        String name,
        boolean isDirectory,
        boolean blocked
    ) {}

    // ── Construction ──────────────────────────────────────────────────────────

    public DesktopGuard(ControlServer controlServer) {
        this.controlServer = controlServer;
        try {
            Files.createDirectories(quarantineDir);
        } catch (IOException e) {
            System.err.println(
                "[DesktopGuard] Could not create quarantine dir: " +
                    e.getMessage()
            );
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public List<DesktopFile> getLastScan() {
        return lastScan;
    }

    public boolean isScanning() {
        return scanning;
    }

    public void setOnScanComplete(Runnable cb) {
        this.onScanComplete = cb;
    }

    /**
     * Scans ~/Desktop asynchronously, classifies every file/folder via Ollama,
     * and moves blocked items to ~/.lockin-quarantine/.
     * Safe to call multiple times — skips if a scan is already in progress.
     */
    public void scanAndApply() {
        if (scanning) return;
        scanning = true;

        CompletableFuture.runAsync(
            () -> {
                try {
                    // ── Always restore first so profile switches get a clean slate ──────
                    // Files blocked under the old profile must come back before we
                    // re-classify them under the new one, otherwise they stay stuck
                    // in quarantine even if the new profile would allow them.
                    restoreAllSilently();

                    String prompt = controlServer.getActiveProfilePrompt();
                    if (prompt.isBlank()) prompt =
                        "general productivity and focus";

                    List<Path> files = listDesktopFiles();
                    System.out.println(
                        "[DesktopGuard] Scanning " +
                            files.size() +
                            " desktop item(s)."
                    );

                    if (files.isEmpty()) {
                        lastScan = List.of();
                        return;
                    }

                    Map<String, Boolean> verdicts = classifyAll(files, prompt);

                    List<Path> toBlock = files
                        .stream()
                        .filter(f ->
                            Boolean.TRUE.equals(
                                verdicts.get(f.getFileName().toString())
                            )
                        )
                        .collect(Collectors.toList());

                    quarantineFiles(toBlock);

                    lastScan = files
                        .stream()
                        .map(f ->
                            new DesktopFile(
                                f.getFileName().toString(),
                                Files.isDirectory(f),
                                Boolean.TRUE.equals(
                                    verdicts.get(f.getFileName().toString())
                                )
                            )
                        )
                        .sorted(
                            Comparator.comparing(DesktopFile::blocked)
                                .reversed()
                                .thenComparing(DesktopFile::name)
                        )
                        .collect(Collectors.toList());

                    System.out.println(
                        "[DesktopGuard] Done — " +
                            toBlock.size() +
                            " item(s) quarantined."
                    );
                } catch (Exception e) {
                    System.err.println(
                        "[DesktopGuard] Scan error: " + e.getMessage()
                    );
                } finally {
                    scanning = false;
                    if (onScanComplete != null) onScanComplete.run();
                }
            },
            executor
        );
    }

    /**
     * Moves every quarantined file/folder back to ~/Desktop and clears the manifest.
     * Called automatically when LockIn is paused from the UI.
     */
    public void restoreAll() {
        restoreAllSilently();

        // Update lastScan: mark everything as unblocked
        lastScan = lastScan
            .stream()
            .map(f -> new DesktopFile(f.name(), f.isDirectory(), false))
            .collect(Collectors.toList());

        if (onScanComplete != null) onScanComplete.run();
    }

    /**
     * Restores all quarantined files to ~/Desktop without firing the UI callback.
     * Called internally at the start of every scanAndApply() so that a profile
     * switch always starts from a clean desktop — nothing stays stuck in
     * quarantine just because a previous profile blocked it.
     */
    private void restoreAllSilently() {
        try {
            if (!Files.exists(manifestPath)) return;

            JsonNode manifest = mapper.readTree(manifestPath.toFile());
            int restored = 0;

            for (JsonNode entry : manifest.path("quarantined")) {
                String name = entry.path("name").asText();
                Path src = quarantineDir.resolve(name);
                Path dest = desktopPath.resolve(name);
                if (Files.exists(src)) {
                    Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("[DesktopGuard] Restored: " + name);
                    restored++;
                }
            }

            Files.writeString(manifestPath, "{\"quarantined\":[]}");
            if (restored > 0) {
                System.out.println(
                    "[DesktopGuard] Clean slate — restored " +
                        restored +
                        " item(s) before re-scan."
                );
            }
        } catch (IOException e) {
            System.err.println(
                "[DesktopGuard] Restore error: " + e.getMessage()
            );
        }
    }

    // ── Desktop listing ───────────────────────────────────────────────────────

    private List<Path> listDesktopFiles() throws IOException {
        if (!Files.exists(desktopPath)) return List.of();
        try (var stream = Files.list(desktopPath)) {
            return stream
                .filter(p -> !p.getFileName().toString().startsWith("."))
                .collect(Collectors.toList());
        }
    }

    // ── Ollama classification ─────────────────────────────────────────────────

    private Map<String, Boolean> classifyAll(
        List<Path> files,
        String profilePrompt
    ) {
        Map<String, Boolean> verdicts = new LinkedHashMap<>();
        for (int i = 0; i < files.size(); i += BATCH_SIZE) {
            List<Path> batch = files.subList(
                i,
                Math.min(i + BATCH_SIZE, files.size())
            );
            verdicts.putAll(classifyBatch(batch, profilePrompt));
        }
        return verdicts;
    }

    private Map<String, Boolean> classifyBatch(
        List<Path> files,
        String profilePrompt
    ) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (Path f : files) result.put(f.getFileName().toString(), false); // default: keep

        try {
            StringBuilder lines = new StringBuilder();
            for (int i = 0; i < files.size(); i++) {
                Path p = files.get(i);
                String type = Files.isDirectory(p) ? "folder" : "file";
                lines
                    .append(i + 1)
                    .append(". \"")
                    .append(p.getFileName())
                    .append("\" (")
                    .append(type)
                    .append(")\n");
            }

            String system =
                "You are a desktop file filter for a focused user. " +
                "Classify each file/folder as KEEP (supports the user's focus) or " +
                "BLOCK (distraction or unrelated). " +
                "Reply ONLY with labels, one per line like \"1:KEEP\". No other text.";

            String user = "User focus: " + profilePrompt + "\n\n" + lines;

            var messages = List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)
            );
            byte[] body = mapper.writeValueAsBytes(
                Map.of("model", MODEL, "stream", false, "messages", messages)
            );

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_URL))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

            HttpResponse<String> resp = http.send(
                req,
                HttpResponse.BodyHandlers.ofString()
            );
            if (resp.statusCode() != 200) return result;

            String content = mapper
                .readTree(resp.body())
                .path("message")
                .path("content")
                .asText("")
                .trim();

            for (String line : content.split("\n")) {
                Matcher m = LABEL_RE.matcher(line.trim());
                if (m.find()) {
                    int idx = Integer.parseInt(m.group(1)) - 1;
                    boolean blocked = "BLOCK".equalsIgnoreCase(m.group(2));
                    if (idx >= 0 && idx < files.size()) {
                        result.put(
                            files.get(idx).getFileName().toString(),
                            blocked
                        );
                    }
                }
            }
        } catch (Exception e) {
            System.err.println(
                "[DesktopGuard] Ollama error: " + e.getMessage()
            );
        }

        return result;
    }

    // ── Quarantine management ─────────────────────────────────────────────────

    private void quarantineFiles(List<Path> toBlock) throws IOException {
        List<Map<String, String>> existing = new ArrayList<>();
        if (Files.exists(manifestPath)) {
            try {
                for (JsonNode e : mapper
                    .readTree(manifestPath.toFile())
                    .path("quarantined")) {
                    Map<String, String> entry = new HashMap<>();
                    entry.put("name", e.path("name").asText());
                    entry.put(
                        "quarantined_at",
                        e.path("quarantined_at").asText()
                    );
                    existing.add(entry);
                }
            } catch (Exception ignored) {}
        }

        Set<String> already = existing
            .stream()
            .map(e -> e.get("name"))
            .collect(Collectors.toSet());

        for (Path src : toBlock) {
            String name = src.getFileName().toString();
            if (already.contains(name)) continue;

            Path dest = quarantineDir.resolve(name);
            // Only move if it's actually on the Desktop (not already quarantined)
            if (src.startsWith(desktopPath)) {
                Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[DesktopGuard] Quarantined: " + name);

                Map<String, String> entry = new HashMap<>();
                entry.put("name", name);
                entry.put("quarantined_at", Instant.now().toString());
                existing.add(entry);
            }
        }

        mapper
            .writerWithDefaultPrettyPrinter()
            .writeValue(manifestPath.toFile(), Map.of("quarantined", existing));
    }
}
