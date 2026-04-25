package com.lockin.scraper.controller;

import com.lockin.scraper.model.Tile;
import com.lockin.scraper.model.TilesPayload;
import com.lockin.scraper.queue.TileQueue;
import com.lockin.scraper.service.TileService;
import com.lockin.scraper.writer.TileJsonlWriter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@CrossOrigin(origins = "*")
public class TilesController {

    private final TileService tileService;
    private final TileJsonlWriter tileJsonlWriter;
    private final TileQueue tileQueue;

    public TilesController(
            TileService tileService,
            TileJsonlWriter tileJsonlWriter,
            TileQueue tileQueue) {
        this.tileService      = tileService;
        this.tileJsonlWriter  = tileJsonlWriter;
        this.tileQueue        = tileQueue;
    }

    // ── POST /tiles ───────────────────────────────────────────────────────────
    //
    // Drop-in replacement for yt_receiver.py's POST /tiles endpoint.
    //
    // Expected body (identical to what scrapper/extension sends to Python):
    // {
    //   "tiles": [
    //     {
    //       "video_id":      "dQw4w9WgXcQ",
    //       "title":         "Never Gonna Give You Up",
    //       "channel":       "Rick Astley",
    //       "description":   "",
    //       "thumbnail_url": "https://...",
    //       "url":           "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
    //       "tile_type":     "ytd-rich-item-renderer",
    //       "scraped_at":    "2024-11-01T14:22:05Z"
    //     },
    //     ...
    //   ],
    //   "page_url": "https://www.youtube.com"
    // }
    //
    // Response (identical to yt_receiver.py):
    // { "ok": true, "received": 5, "accepted": 3 }

    @PostMapping("/tiles")
    public ResponseEntity<Map<String, Object>> receiveTiles(
            @RequestBody TilesPayload payload) {

        if (payload == null) {
            return ResponseEntity.badRequest().body(
                Map.of("ok", false, "error", "Empty payload.")
            );
        }

        List<TilesPayload.TileData> tiles = payload.getTiles();
        if (tiles == null || tiles.isEmpty()) {
            return ResponseEntity.ok(
                Map.of("ok", true, "received", 0, "accepted", 0)
            );
        }

        TileService.TileAcceptResult result = tileService.processTiles(payload);

        System.out.printf(
            "[tiles] page_url=%s  received=%d  accepted=%d  duplicate=%d%n",
            payload.getPageUrl(),
            result.getReceived(),
            result.getAccepted(),
            result.getDuplicate()
        );

        // Response format matches yt_receiver.py exactly
        return ResponseEntity.ok(Map.of(
            "ok",       true,
            "received", result.getReceived(),
            "accepted", result.getAccepted()
        ));
    }

    // ── GET /tiles ────────────────────────────────────────────────────────────

    /**
     * GET /tiles
     * Returns all stored tiles, newest-first.
     */
    @GetMapping("/tiles")
    public ResponseEntity<Map<String, Object>> getAllTiles() {
        List<Tile> tiles = tileService.findAll();
        return ResponseEntity.ok(Map.of(
            "ok",    true,
            "count", tiles.size(),
            "tiles", tiles
        ));
    }

    /**
     * GET /tiles/{id}
     * Returns a single tile by its internal UUID.
     */
    @GetMapping("/tiles/{id}")
    public ResponseEntity<?> getTileById(@PathVariable String id) {
        Optional<Tile> opt = tileService.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(404).body(
                Map.of("ok", false, "error", "Tile not found: " + id)
            );
        }
        return ResponseEntity.ok(Map.of("ok", true, "tile", opt.get()));
    }

    // ── DELETE /tiles ─────────────────────────────────────────────────────────

    /**
     * DELETE /tiles
     * Wipes all stored tiles from H2.
     * Note: already-written NDJSON lines are NOT removed from tiles.jsonl —
     * delete the file manually if you want a clean slate there too.
     */
    @DeleteMapping("/tiles")
    public ResponseEntity<Map<String, Object>> deleteAllTiles() {
        long before = tileService.count();
        tileService.deleteAll();
        return ResponseEntity.ok(Map.of(
            "ok",      true,
            "deleted", before
        ));
    }

    /**
     * DELETE /tiles/{id}
     * Removes a single tile by its internal UUID.
     */
    @DeleteMapping("/tiles/{id}")
    public ResponseEntity<Map<String, Object>> deleteTileById(@PathVariable String id) {
        boolean removed = tileService.deleteById(id);
        if (!removed) {
            return ResponseEntity.status(404).body(
                Map.of("ok", false, "error", "Tile not found: " + id)
            );
        }
        return ResponseEntity.ok(Map.of("ok", true, "deleted", id));
    }

    // ── GET /tiles/export ─────────────────────────────────────────────────────

    /**
     * GET /tiles/export
     * Returns the current status of the NDJSON streaming file —
     * path, size on disk, lines written, and queue depth.
     *
     * Mirrors /export for the general scrape results.
     */
    @GetMapping("/tiles/export")
    public ResponseEntity<Map<String, Object>> exportStatus() {
        return ResponseEntity.ok(Map.of(
            "ok",            true,
            "file",          tileJsonlWriter.getOutputFile().toAbsolutePath().toString(),
            "fileSizeBytes", tileJsonlWriter.fileSizeBytes(),
            "linesWritten",  tileJsonlWriter.lineCount(),
            "queueDepth",    tileQueue.size(),
            "h2Stored",      tileService.count(),
            "timestamp",     Instant.now().toString()
        ));
    }

    /**
     * POST /tiles/export/all
     * Re-enqueues every tile currently in H2 so they are all (re-)written
     * to tiles.jsonl. Useful after a fresh start when the file was deleted.
     *
     * Already-written lines are NOT deduplicated in the file — it's append-only.
     * Delete tiles.jsonl first if you want a clean re-export.
     */
    @PostMapping("/tiles/export/all")
    public ResponseEntity<Map<String, Object>> exportAll() {
        List<Tile> all = tileService.findAll();
        all.forEach(tileQueue::enqueue);
        return ResponseEntity.ok(Map.of(
            "ok",      true,
            "enqueued", all.size(),
            "message", all.size() + " tile(s) enqueued — will appear in tiles.jsonl shortly."
        ));
    }
}
