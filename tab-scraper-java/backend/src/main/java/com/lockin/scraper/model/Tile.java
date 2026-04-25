package com.lockin.scraper.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
    name = "tiles",
    indexes = {
        @Index(name = "idx_tile_dedupe_key",  columnList = "dedupe_key"),
        @Index(name = "idx_tile_scraped_at",  columnList = "scraped_at"),
        @Index(name = "idx_tile_page_url",    columnList = "page_url")
    }
)
public class Tile {

    /** Internal UUID primary key — not exposed in NDJSON output. */
    @Id
    @Column(length = 36)
    private String id;

    /**
     * YouTube video ID (e.g. "dQw4w9WgXcQ").
     * Maps to Python's  t["video_id"]
     */
    @Column(name = "video_id", length = 64)
    private String videoId;

    @Column(length = 1024)
    private String title;

    @Column(length = 512)
    private String channel;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Maps to Python's  t["thumbnail_url"] */
    @Column(name = "thumbnail_url", length = 2048)
    private String thumbnailUrl;

    @Column(length = 2048)
    private String url;

    /**
     * CSS selector that matched this tile, e.g. "ytd-rich-item-renderer".
     * Maps to Python's  t["tile_type"]
     */
    @Column(name = "tile_type", length = 128)
    private String tileType;

    /**
     * The URL of the YouTube page from which this tile was scraped.
     * Maps to Python's  payload["page_url"]
     */
    @Column(name = "page_url", length = 2048)
    private String pageUrl;

    /**
     * ISO-8601 timestamp set by the Chrome extension JS — same field name
     * and format as Python's  t["scraped_at"]  ("2024-11-01T14:22:05Z").
     */
    @Column(name = "scraped_at")
    private Instant scrapedAt;

    /**
     * ISO-8601 timestamp set by this Java receiver when the POST arrives.
     * Mirrors Python receiver's  t["received_at"].
     */
    @Column(name = "received_at")
    private Instant receivedAt;

    /**
     * Deduplication key: videoId when present, otherwise url.
     * Indexed for fast exists-checks before every insert.
     * Not emitted in NDJSON output.
     */
    @Column(name = "dedupe_key", length = 2048)
    private String dedupeKey;

    // ── Constructors ──────────────────────────────────────────────────────────

    public Tile() {}

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getVideoId() { return videoId; }
    public void setVideoId(String videoId) { this.videoId = videoId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getThumbnailUrl() { return thumbnailUrl; }
    public void setThumbnailUrl(String thumbnailUrl) { this.thumbnailUrl = thumbnailUrl; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getTileType() { return tileType; }
    public void setTileType(String tileType) { this.tileType = tileType; }

    public String getPageUrl() { return pageUrl; }
    public void setPageUrl(String pageUrl) { this.pageUrl = pageUrl; }

    public Instant getScrapedAt() { return scrapedAt; }
    public void setScrapedAt(Instant scrapedAt) { this.scrapedAt = scrapedAt; }

    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }

    public String getDedupeKey() { return dedupeKey; }
    public void setDedupeKey(String dedupeKey) { this.dedupeKey = dedupeKey; }

    @Override
    public String toString() {
        return "Tile{id='" + id + "', videoId='" + videoId +
               "', title='" + title + "', channel='" + channel +
               "', url='" + url + "', tileType='" + tileType +
               "', scrapedAt=" + scrapedAt + '}';
    }
}
