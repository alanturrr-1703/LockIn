package com.lockin.scraper.writer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.lockin.scraper.model.Tile;
import com.lockin.scraper.queue.TileQueue;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Drains {@link TileQueue} on a dedicated daemon thread and appends one JSON
 * object per line to a .jsonl file.
 *
 * Output format is byte-for-byte identical to what yt_receiver.py writes:
 *
 *   {"video_id":"abc","title":"...","channel":"...","description":"...",
 *    "thumbnail_url":"...","url":"...","tile_type":"...","scraped_at":"...",
 *    "page_url":"...","received_at":"..."}
 *
 * Field order is preserved via LinkedHashMap — same order as yt_scraper.py.
 */
@Component
public class TileJsonlWriter {

    private static final Logger log = LoggerFactory.getLogger(TileJsonlWriter.class);

    private final TileQueue tileQueue;
    private final ObjectMapper mapper;
    private final Path outputFile;

    private volatile Thread workerThread;

    public TileJsonlWriter(
            TileQueue tileQueue,
            ObjectMapper mapper,
            @Value("${scraper.tiles.jsonl.path:./data/tiles.jsonl}") String path) {

        this.tileQueue  = tileQueue;
        // Compact single-line output — never indented, one object per line
        this.mapper     = mapper.copy().disable(SerializationFeature.INDENT_OUTPUT);
        this.outputFile = Path.of(path);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @PostConstruct
    public void start() throws IOException {
        Files.createDirectories(outputFile.getParent());
        workerThread = new Thread(this::consume, "tile-jsonl-writer");
        workerThread.setDaemon(true);
        workerThread.start();
        log.info("[tile-writer] streaming tiles to {}", outputFile.toAbsolutePath());
    }

    @PreDestroy
    public void stop() {
        if (workerThread != null) {
            workerThread.interrupt();
            log.info("[tile-writer] shutdown signal sent.");
        }
    }

    // ── Consumer loop ─────────────────────────────────────────────────────────

    private void consume() {
        log.info("[tile-writer] consumer started, waiting for tiles…");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Tile tile = tileQueue.take();   // blocks until a tile arrives
                appendLine(tile);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("[tile-writer] consumer stopped.");
    }

    // ── File I/O ──────────────────────────────────────────────────────────────

    private void appendLine(Tile tile) {
        try {
            String line = mapper.writeValueAsString(buildRecord(tile))
                    + System.lineSeparator();

            Files.writeString(
                    outputFile,
                    line,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );

            log.info("[tile-writer] wrote video_id={} title=\"{}\" page={}",
                    tile.getVideoId(), tile.getTitle(), tile.getPageUrl());

        } catch (Exception e) {
            log.error("[tile-writer] failed to write tile video_id={}: {}",
                    tile.getVideoId(), e.getMessage());
        }
    }

    /**
     * Builds the output record in the exact same field order as yt_scraper.py:
     *
     *   video_id · title · channel · description · thumbnail_url
     *   url · tile_type · scraped_at · page_url · received_at
     *
     * Using LinkedHashMap guarantees insertion-order serialization.
     */
    private Map<String, Object> buildRecord(Tile tile) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("video_id",      nvl(tile.getVideoId()));
        m.put("title",         nvl(tile.getTitle()));
        m.put("channel",       nvl(tile.getChannel()));
        m.put("description",   nvl(tile.getDescription()));
        m.put("thumbnail_url", nvl(tile.getThumbnailUrl()));
        m.put("url",           nvl(tile.getUrl()));
        m.put("tile_type",     nvl(tile.getTileType()));
        m.put("scraped_at",    isoOrEmpty(tile.getScrapedAt()));
        m.put("page_url",      nvl(tile.getPageUrl()));
        m.put("received_at",   isoOrEmpty(tile.getReceivedAt()));
        return m;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Return the value, or empty string if null — same as Python's  t.get("x") or ""  */
    private static String nvl(String value) {
        return value != null ? value : "";
    }

    /**
     * Format an Instant as "2024-11-01T14:22:05Z" — exactly the format Python
     * produces with  datetime.now(timezone.utc).isoformat(timespec="seconds")
     * .replace("+00:00", "Z")
     */
    private static String isoOrEmpty(java.time.Instant instant) {
        if (instant == null) return "";
        // Instant.toString() already produces ISO-8601 with 'Z' suffix,
        // but may include sub-second precision. Truncate to seconds to match Python.
        return instant.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    }

    // ── Accessors (used by TilesController for status endpoint) ───────────────

    public Path getOutputFile() {
        return outputFile;
    }

    public long lineCount() {
        try {
            if (!Files.exists(outputFile)) return 0;
            try (var lines = Files.lines(outputFile)) {
                return lines.filter(l -> !l.isBlank()).count();
            }
        } catch (IOException e) {
            log.warn("[tile-writer] could not count lines: {}", e.getMessage());
            return -1;
        }
    }

    public long fileSizeBytes() {
        try {
            return Files.exists(outputFile) ? Files.size(outputFile) : 0;
        } catch (IOException e) {
            return -1;
        }
    }
}
