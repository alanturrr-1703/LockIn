package com.lockin.scraper.queue;

import com.lockin.scraper.model.ScrapeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * In-process queue that decouples the HTTP request thread from file I/O.
 *
 * Flow:
 *   ScrapeService.process()  →  enqueue()  →  [BlockingQueue]
 *                                                     ↓
 *                                           JsonlFileWriter (daemon thread)
 *                                                     ↓
 *                                             scrapes.jsonl
 *
 * Using an unbounded LinkedBlockingQueue means the HTTP thread never blocks
 * waiting for disk — it just drops the result onto the queue and returns.
 * The writer thread drains it at its own pace.
 */
@Component
public class ScrapeQueue {

    private static final Logger log = LoggerFactory.getLogger(ScrapeQueue.class);

    private final BlockingQueue<ScrapeResult> queue = new LinkedBlockingQueue<>();

    // ── Producer (called by ScrapeService on the HTTP thread) ────────────────

    /**
     * Add a result to the tail of the queue.
     * Never blocks — returns immediately even if the writer is busy.
     *
     * @param result the freshly saved ScrapeResult
     */
    public void enqueue(ScrapeResult result) {
        boolean accepted = queue.offer(result);
        if (accepted) {
            log.debug("[queue] enqueued id={} | queue depth={}", result.getId(), queue.size());
        } else {
            // LinkedBlockingQueue is unbounded so this should never happen,
            // but log a warning just in case.
            log.warn("[queue] offer rejected for id={} (queue full?)", result.getId());
        }
    }

    // ── Consumer (called by JsonlFileWriter on the writer daemon thread) ──────

    /**
     * Block until a result is available, then return it.
     * Throws InterruptedException when the writer thread is being shut down.
     *
     * @return the next ScrapeResult to be written
     * @throws InterruptedException if the calling thread is interrupted
     */
    public ScrapeResult take() throws InterruptedException {
        return queue.take();
    }

    /**
     * Poll for a result with a timeout.
     * Returns null if nothing arrives within the timeout — useful if the
     * consumer wants to perform periodic housekeeping between writes.
     *
     * @param timeout how long to wait
     * @param unit    the time unit for {@code timeout}
     * @return the next result, or null if the timeout elapsed
     * @throws InterruptedException if the calling thread is interrupted
     */
    public ScrapeResult poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    // ── Introspection ─────────────────────────────────────────────────────────

    /** Current number of results waiting to be written. */
    public int size() {
        return queue.size();
    }

    /** True if no results are waiting. */
    public boolean isEmpty() {
        return queue.isEmpty();
    }
}
