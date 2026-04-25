package com.lockin.scraper.queue;

import com.lockin.scraper.model.Tile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * In-process queue that decouples the HTTP request thread from NDJSON file I/O.
 *
 * Flow:
 *   TileService.processTiles()  →  enqueue()  →  [BlockingQueue]
 *                                                       ↓
 *                                           TileJsonlWriter (daemon thread)
 *                                                       ↓
 *                                               tiles.jsonl
 */
@Component
public class TileQueue {

    private static final Logger log = LoggerFactory.getLogger(TileQueue.class);

    private final BlockingQueue<Tile> queue = new LinkedBlockingQueue<>();

    // ── Producer ──────────────────────────────────────────────────────────────

    /**
     * Add a tile to the queue. Never blocks — returns immediately.
     *
     * @param tile the saved Tile to be streamed to the NDJSON file
     */
    public void enqueue(Tile tile) {
        boolean accepted = queue.offer(tile);
        if (accepted) {
            log.debug("[tile-queue] enqueued video_id={} | depth={}", tile.getVideoId(), queue.size());
        } else {
            log.warn("[tile-queue] offer rejected for video_id={} (queue full?)", tile.getVideoId());
        }
    }

    // ── Consumer ──────────────────────────────────────────────────────────────

    /**
     * Block until a tile is available, then return it.
     *
     * @return the next Tile to be written
     * @throws InterruptedException if the calling thread is interrupted
     */
    public Tile take() throws InterruptedException {
        return queue.take();
    }

    /**
     * Poll for a tile with a timeout.
     *
     * @param timeout how long to wait
     * @param unit    the time unit for {@code timeout}
     * @return the next tile, or null if the timeout elapsed
     * @throws InterruptedException if the calling thread is interrupted
     */
    public Tile poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    // ── Introspection ─────────────────────────────────────────────────────────

    public int size()      { return queue.size();    }
    public boolean isEmpty() { return queue.isEmpty(); }
}
