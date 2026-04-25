package com.lockin.scraper.writer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.lockin.scraper.model.ScrapeResult;
import com.lockin.scraper.queue.ScrapeQueue;
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
 * Drains {@link ScrapeQueue} on a dedicated daemon thread and appends
 * one JSON object per line to a .jsonl file.
 *
 * Flow:
 *   ScrapeService.process()
 *       └─► ScrapeQueue.enqueue(result)          (non-blocking, returns immediately)
 *               └─► [this daemon thread wakes up]
 *                       └─► appends line to scrapes.jsonl
 *
 * The HTTP response is never blocked by file I/O.
 * The full result (including rawContent) stays in H2; the JSONL file
 * stores a trimmed record — suitable for inspection, grep, jq, etc.
 */
@Component
public class JsonlFileWriter {

    private static final Logger log = LoggerFactory.getLogger(JsonlFileWriter.class);

    /** Max chars of cleaned text written to the JSONL preview field. */
    private static final int TEXT_PREVIEW_LIMIT = 3_000;

    private final ScrapeQueue scrapeQueue;
    private final ObjectMapper mapper;
    private final Path outputFile;

    private volatile Thread workerThread;

    public JsonlFileWriter(
            ScrapeQueue scrapeQueue,
            ObjectMapper mapper,
            @Value("${scraper.jsonl.path:./data/scrapes.jsonl}") String path) {

        this.scrapeQueue = scrapeQueue;
        // Compact (non-indented) output — one full JSON object per line
        this.mapper = mapper.copy()
                .disable(SerializationFeature.INDENT_OUTPUT);
        this.outputFile = Path.of(path);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @PostConstruct
    public void start() throws IOException {
        Files.createDirectories(outputFile.getParent());
        workerThread = new Thread(this::consume, "jsonl-writer");
        workerThread.setDaemon(true);   // won't prevent JVM shutdown
        workerThread.start();
        log.info("[jsonl-writer] streaming results to {}", outputFile.toAbsolutePath());
    }

    @PreDestroy
    public void stop() {
        if (workerThread != null) {
            workerThread.interrupt();
            log.info("[jsonl-writer] shutdown signal sent.");
        }
    }

    // ── Consumer loop ─────────────────────────────────────────────────────────

    /**
     * Blocks on {@link ScrapeQueue#take()} until a result arrives, then
     * serialises it and appends the line.  Runs until interrupted.
     */
    private void consume() {
        log.info("[jsonl-writer] consumer started, waiting for results…");

        while (!Thread.currentThread().isInterrupted()) {
            try {
                ScrapeResult result = scrapeQueue.take();   // blocks until available
                appendLine(result);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();         // restore flag and exit
            }
        }

        log.info("[jsonl-writer] consumer stopped.");
    }

    // ── File I/O ──────────────────────────────────────────────────────────────

    private void appendLine(ScrapeResult result) {
        try {
            String line = mapper.writeValueAsString(buildRecord(result))
                    + System.lineSeparator();

            Files.writeString(
                    outputFile,
                    line,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND   // never overwrites — pure stream
            );

            log.info("[jsonl-writer] wrote id={} title=\"{}\" url={}",
                    result.getId(), result.getTitle(), result.getUrl());

        } catch (Exception e) {
            log.error("[jsonl-writer] failed to write result id={}: {}",
                    result.getId(), e.getMessage());
        }
    }

    /**
     * Builds a compact, human-friendly record for the JSONL file.
     *
     * rawContent is intentionally excluded — it's already in H2 and can
     * be megabytes long.  cleanedText is included but capped at
     * {@value #TEXT_PREVIEW_LIMIT} characters so the file stays scannable.
     */
    private Map<String, Object> buildRecord(ScrapeResult r) {
        Map<String, Object> m = new LinkedHashMap<>();

        m.put("id",              r.getId());
        m.put("title",           r.getTitle());
        m.put("url",             r.getUrl());
        m.put("scrapedAt",       r.getScrapedAt() != null ? r.getScrapedAt().toString() : null);
        m.put("wordCount",       r.getWordCount());
        m.put("charCount",       r.getCharCount());
        m.put("metaDescription", r.getMetaDescription());
        m.put("headings",        r.getHeadings());
        m.put("links",           r.getLinks());

        // Trimmed preview of cleaned text — full text lives in H2
        String text = r.getCleanedText();
        if (text != null && text.length() > TEXT_PREVIEW_LIMIT) {
            m.put("textPreview", text.substring(0, TEXT_PREVIEW_LIMIT) + "…");
            m.put("textTrimmed", true);
        } else {
            m.put("textPreview", text);
            m.put("textTrimmed", false);
        }

        return m;
    }

    // ── Accessors (used by controller for status endpoint) ───────────────────

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
            log.warn("[jsonl-writer] could not count lines: {}", e.getMessage());
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
