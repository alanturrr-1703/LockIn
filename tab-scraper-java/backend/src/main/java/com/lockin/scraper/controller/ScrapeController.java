package com.lockin.scraper.controller;

import com.lockin.scraper.model.ScrapePayload;
import com.lockin.scraper.model.ScrapeResult;
import com.lockin.scraper.queue.ScrapeQueue;
import com.lockin.scraper.service.ScrapeService;
import com.lockin.scraper.writer.JsonlFileWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = "*")
public class ScrapeController {

    private final ScrapeService scrapeService;
    private final JsonlFileWriter jsonlFileWriter;
    private final ScrapeQueue scrapeQueue;

    public ScrapeController(
        ScrapeService scrapeService,
        JsonlFileWriter jsonlFileWriter,
        ScrapeQueue scrapeQueue
    ) {
        this.scrapeService = scrapeService;
        this.jsonlFileWriter = jsonlFileWriter;
        this.scrapeQueue = scrapeQueue;
    }

    // ── Health ────────────────────────────────────────────────────────────────

    /**
     * GET /health
     * Quick liveness check — the extension can ping this to confirm the
     * backend is running before it tries to POST scrape data.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(
            Map.of(
                "ok",
                true,
                "service",
                "tab-scraper-java",
                "stored",
                scrapeService.count(),
                "timestamp",
                Instant.now().toString()
            )
        );
    }

    // ── Scrape ────────────────────────────────────────────────────────────────

    /**
     * POST /scrape
     * Receives the raw payload from the Chrome extension, processes it
     * with JSoup, stores the result in-memory and returns the enriched data.
     *
     * Expected JSON body:
     * {
     *   "title":   "Page title",
     *   "url":     "https://example.com",
     *   "content": "document.body.innerText ...",
     *   "html":    "<!DOCTYPE html>..."   // optional but recommended
     * }
     */
    @PostMapping("/scrape")
    public ResponseEntity<Map<String, Object>> receiveScrape(
        @RequestBody ScrapePayload payload
    ) {
        if (payload == null) {
            return ResponseEntity.badRequest().body(
                Map.of("ok", false, "error", "Empty payload.")
            );
        }

        System.out.println("──────────────────────────────────────────");
        System.out.println("[scrape] Title  : " + payload.getTitle());
        System.out.println("[scrape] URL    : " + payload.getUrl());
        System.out.println(
            "[scrape] Content: " +
                (payload.getContent() != null
                    ? payload.getContent().length() + " chars"
                    : "null")
        );
        System.out.println(
            "[scrape] HTML   : " +
                (payload.getHtml() != null
                    ? payload.getHtml().length() + " chars"
                    : "not sent")
        );

        ScrapeResult result = scrapeService.process(payload);

        System.out.println(
            "[scrape] Stored id=" +
                result.getId() +
                "  words=" +
                result.getWordCount() +
                "  links=" +
                result.getLinks().size() +
                "  headings=" +
                result.getHeadings().size()
        );
        System.out.println("──────────────────────────────────────────");

        return ResponseEntity.ok(
            Map.of(
                "ok",
                true,
                "id",
                result.getId(),
                "wordCount",
                result.getWordCount(),
                "charCount",
                result.getCharCount(),
                "headings",
                result.getHeadings().size(),
                "links",
                result.getLinks().size(),
                "scrapedAt",
                result.getScrapedAt().toString()
            )
        );
    }

    // ── Results ───────────────────────────────────────────────────────────────

    /**
     * GET /results
     * Returns all stored scrape results (newest first).
     * Each item is the full ScrapeResult object including cleaned text,
     * extracted headings, and link list.
     */
    @GetMapping("/results")
    public ResponseEntity<Map<String, Object>> getAllResults() {
        List<ScrapeResult> results = scrapeService.findAll();
        return ResponseEntity.ok(
            Map.of("ok", true, "count", results.size(), "results", results)
        );
    }

    /**
     * GET /results/{id}
     * Returns a single stored scrape result by its UUID.
     * Returns 404 if the id is not found.
     */
    @GetMapping("/results/{id}")
    public ResponseEntity<?> getResultById(@PathVariable String id) {
        Optional<ScrapeResult> opt = scrapeService.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(404).body(
                Map.of("ok", false, "error", "Result not found: " + id)
            );
        }
        return ResponseEntity.ok(Map.of("ok", true, "result", opt.get()));
    }

    /**
     * DELETE /results/{id}
     * Removes a single stored result by id.
     */
    @DeleteMapping("/results/{id}")
    public ResponseEntity<Map<String, Object>> deleteResultById(
        @PathVariable String id
    ) {
        boolean removed = scrapeService.deleteById(id);
        if (!removed) {
            return ResponseEntity.status(404).body(
                Map.of("ok", false, "error", "Result not found: " + id)
            );
        }
        return ResponseEntity.ok(Map.of("ok", true, "deleted", id));
    }

    /**
     * DELETE /results
     * Wipes the entire in-memory store.
     */
    @DeleteMapping("/results")
    public ResponseEntity<Map<String, Object>> deleteAllResults() {
        int before = scrapeService.count();
        scrapeService.deleteAll();
        return ResponseEntity.ok(Map.of("ok", true, "deleted", before));
    }

    // ── JSONL Export ──────────────────────────────────────────────────────────

    /**
     * GET /export
     * Returns the current status of the JSONL streaming file:
     * path, size on disk, number of lines written, and how many results
     * are still waiting in the queue to be flushed.
     */
    @GetMapping("/export")
    public ResponseEntity<Map<String, Object>> exportStatus() {
        return ResponseEntity.ok(
            Map.of(
                "ok",
                true,
                "file",
                jsonlFileWriter.getOutputFile().toAbsolutePath().toString(),
                "fileSizeBytes",
                jsonlFileWriter.fileSizeBytes(),
                "linesWritten",
                jsonlFileWriter.lineCount(),
                "queueDepth",
                scrapeQueue.size(),
                "h2Stored",
                scrapeService.count()
            )
        );
    }

    /**
     * POST /export/all
     * Re-enqueues every result currently stored in H2 so they are all
     * (re-)written to the JSONL file.  Useful for the first run after
     * adding the queue, or after manually editing the database.
     *
     * Already-written lines are NOT deduplicated — append only.
     * If you want a clean file, delete scrapes.jsonl first.
     */
    @PostMapping("/export/all")
    public ResponseEntity<Map<String, Object>> exportAll() {
        List<ScrapeResult> all = scrapeService.findAll();
        all.forEach(scrapeQueue::enqueue);
        return ResponseEntity.ok(
            Map.of(
                "ok",
                true,
                "enqueued",
                all.size(),
                "message",
                "All " +
                    all.size() +
                    " result(s) enqueued — " +
                    "they will appear in the JSONL file shortly."
            )
        );
    }
}
