package com.lockin.scraper.service;

import com.lockin.scraper.model.Tile;
import com.lockin.scraper.model.TilesPayload;
import com.lockin.scraper.queue.TileQueue;
import com.lockin.scraper.repository.TileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TileService {

    private static final Logger log = LoggerFactory.getLogger(TileService.class);

    private final TileRepository tileRepository;
    private final TileQueue tileQueue;

    public TileService(TileRepository tileRepository, TileQueue tileQueue) {
        this.tileRepository = tileRepository;
        this.tileQueue = tileQueue;
    }

    // ── Result DTO ────────────────────────────────────────────────────────────

    public static class TileAcceptResult {
        private final int received;
        private final int accepted;
        private final int duplicate;

        public TileAcceptResult(int received, int accepted) {
            this.received  = received;
            this.accepted  = accepted;
            this.duplicate = received - accepted;
        }

        public int getReceived()  { return received;  }
        public int getAccepted()  { return accepted;  }
        public int getDuplicate() { return duplicate; }
    }

    // ── Core ──────────────────────────────────────────────────────────────────

    /**
     * Process a tiles payload from the Chrome extension.
     *
     * Mirrors what yt_receiver.py does:
     *   1. For each tile compute a dedupe key: videoId if present, else url.
     *   2. Skip tiles whose key is already in the database.
     *   3. Save new tiles to H2.
     *   4. Enqueue each saved tile so TileJsonlWriter streams it to tiles.jsonl.
     *   5. Return received / accepted counts.
     *
     * @param payload the POST body from the extension
     * @return counts for the HTTP response
     */
    public TileAcceptResult processTiles(TilesPayload payload) {
        List<TilesPayload.TileData> tiles =
                payload.getTiles() != null ? payload.getTiles() : Collections.emptyList();

        int received = tiles.size();
        int accepted = 0;
        Instant now  = Instant.now();

        for (TilesPayload.TileData data : tiles) {
            // ── 1. Compute dedupe key (same logic as Python receiver) ─────────
            String videoId = trimOrEmpty(data.getVideoId());
            String url     = trimOrEmpty(data.getUrl());
            String key     = !videoId.isEmpty() ? videoId : url;

            if (key.isEmpty()) {
                log.debug("[tile-service] skipping tile with no video_id and no url");
                continue;
            }

            // ── 2. Dedup check ────────────────────────────────────────────────
            if (tileRepository.existsByDedupeKey(key)) {
                log.debug("[tile-service] duplicate key={} — skipped", key);
                continue;
            }

            // ── 3. Build entity ───────────────────────────────────────────────
            Tile tile = new Tile();
            tile.setId(UUID.randomUUID().toString());
            tile.setVideoId(videoId);
            tile.setTitle(data.getTitle());
            tile.setChannel(data.getChannel());
            tile.setDescription(data.getDescription());
            tile.setThumbnailUrl(data.getThumbnailUrl());
            tile.setUrl(url);
            tile.setTileType(data.getTileType());
            tile.setPageUrl(trimOrEmpty(payload.getPageUrl()));
            tile.setDedupeKey(key);
            tile.setScrapedAt(parseInstant(data.getScrapedAt(), now));
            tile.setReceivedAt(now);

            // ── 4. Persist → enqueue ──────────────────────────────────────────
            Tile saved = tileRepository.save(tile);
            tileQueue.enqueue(saved);
            accepted++;

            log.debug("[tile-service] accepted video_id={} title=\"{}\"",
                    saved.getVideoId(), saved.getTitle());
        }

        log.info("[tile-service] received={} accepted={} duplicate={}",
                received, accepted, received - accepted);

        return new TileAcceptResult(received, accepted);
    }

    // ── Query helpers ─────────────────────────────────────────────────────────

    /** All tiles, newest-first. */
    public List<Tile> findAll() {
        return tileRepository.findAllByOrderByScrapedAtDesc();
    }

    /** Single tile by UUID. */
    public Optional<Tile> findById(String id) {
        return tileRepository.findById(id);
    }

    /** Total tiles stored. */
    public long count() {
        return tileRepository.count();
    }

    /** Wipe all tiles. */
    public void deleteAll() {
        tileRepository.deleteAll();
    }

    /** Delete single tile by UUID. Returns true if it existed. */
    public boolean deleteById(String id) {
        if (!tileRepository.existsById(id)) return false;
        tileRepository.deleteById(id);
        return true;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static String trimOrEmpty(String s) {
        return s != null ? s.trim() : "";
    }

    /**
     * Parse an ISO-8601 string sent by the extension JS.
     * Falls back to {@code fallback} if the string is null, blank, or unparseable.
     */
    private static Instant parseInstant(String s, Instant fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return fallback;
        }
    }
}
