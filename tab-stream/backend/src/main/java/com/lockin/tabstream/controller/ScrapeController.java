package com.lockin.tabstream.controller;

import com.lockin.tabstream.dto.ApiResponse;
import com.lockin.tabstream.dto.PagePayloadDTO;
import com.lockin.tabstream.dto.PageSnapshotSummaryDTO;
import com.lockin.tabstream.dto.ParsedPageDTO;
import com.lockin.tabstream.service.PageProcessingService;
import jakarta.validation.Valid;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller exposing the core scrape, history, and health endpoints.
 *
 * <p>All responses are wrapped in {@link ApiResponse} so that every client
 * receives a consistent envelope regardless of whether the call succeeded or
 * produced a domain-level "not found" result.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/scrape}       — parse and store a page payload</li>
 *   <li>{@code GET  /api/history}      — list the 50 most recent snapshots</li>
 *   <li>{@code GET  /api/history/{id}} — retrieve one snapshot by UUID</li>
 *   <li>{@code DELETE /api/history}    — wipe all stored snapshots</li>
 *   <li>{@code GET  /api/health}       — liveness / readiness probe</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class ScrapeController {

    private static final Logger log = LoggerFactory.getLogger(
        ScrapeController.class
    );

    private final PageProcessingService pageProcessingService;

    public ScrapeController(PageProcessingService pageProcessingService) {
        this.pageProcessingService = pageProcessingService;
    }

    // -------------------------------------------------------------------------
    // POST /api/scrape
    // -------------------------------------------------------------------------

    /**
     * Accept a raw page payload from the Chrome extension, run it through the
     * JSoup parsing pipeline, persist the result, and return the full analysis.
     *
     * @param payload validated request body from the extension
     * @return 200 OK with the parsed page DTO wrapped in {@link ApiResponse}
     */
    @PostMapping("/scrape")
    public ResponseEntity<ApiResponse<ParsedPageDTO>> scrape(
        @Valid @RequestBody PagePayloadDTO payload
    ) {
        log.info("POST /api/scrape — url='{}'", payload.getUrl());

        ParsedPageDTO result = pageProcessingService.process(payload, "rest");

        log.debug(
            "POST /api/scrape — completed id='{}' wordCount={}",
            result.getId(),
            result.getWordCount()
        );
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // -------------------------------------------------------------------------
    // GET /api/history
    // -------------------------------------------------------------------------

    /**
     * Return summary DTOs for the 50 most recent page snapshots, newest first.
     *
     * @return 200 OK with the list (may be empty) wrapped in {@link ApiResponse}
     */
    @GetMapping("/history")
    public ResponseEntity<
        ApiResponse<List<PageSnapshotSummaryDTO>>
    > getHistory() {
        log.debug("GET /api/history");

        List<PageSnapshotSummaryDTO> history =
            pageProcessingService.getHistory();

        log.debug("GET /api/history — returning {} entries", history.size());
        return ResponseEntity.ok(ApiResponse.ok(history));
    }

    // -------------------------------------------------------------------------
    // GET /api/history/{id}
    // -------------------------------------------------------------------------

    /**
     * Retrieve a single fully-parsed snapshot by its UUID.
     *
     * @param id the snapshot UUID path variable
     * @return 200 OK with the DTO if found; 404 Not Found otherwise
     */
    @GetMapping("/history/{id}")
    public ResponseEntity<ApiResponse<ParsedPageDTO>> getHistoryById(
        @PathVariable String id
    ) {
        log.debug("GET /api/history/{}", id);

        Optional<ParsedPageDTO> result = pageProcessingService.getById(id);

        if (result.isPresent()) {
            log.debug("GET /api/history/{} — found", id);
            return ResponseEntity.ok(ApiResponse.ok(result.get()));
        }

        log.warn("GET /api/history/{} — not found", id);
        return ResponseEntity.status(404).body(
            ApiResponse.error("Snapshot not found: " + id)
        );
    }

    // -------------------------------------------------------------------------
    // DELETE /api/history
    // -------------------------------------------------------------------------

    /**
     * Delete all stored page snapshots.
     *
     * @return 200 OK with a null data payload confirming the operation
     */
    @DeleteMapping("/history")
    public ResponseEntity<ApiResponse<Void>> deleteHistory() {
        log.info("DELETE /api/history — clearing all snapshots");

        pageProcessingService.clearHistory();

        log.info("DELETE /api/history — all snapshots deleted");
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    // -------------------------------------------------------------------------
    // GET /api/health
    // -------------------------------------------------------------------------

    /**
     * Lightweight health probe used by monitoring tools and the extension's
     * connection check.
     *
     * <p>Returns basic runtime information without performing any database I/O,
     * so this endpoint remains responsive even if the persistence layer is
     * degraded.
     *
     * @return 200 OK with a map of health indicators
     */
    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, Object>>> health() {
        log.debug("GET /api/health");

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("status", "UP");
        info.put("service", "tab-stream");
        info.put("timestamp", Instant.now().toString());
        info.put("javaVersion", System.getProperty("java.version"));
        info.put("uptime", ManagementFactory.getRuntimeMXBean().getUptime());

        return ResponseEntity.ok(ApiResponse.ok(info));
    }
}
