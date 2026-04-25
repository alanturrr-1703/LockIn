package com.youtubewatch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * TileRecord
 *
 * Represents a single video tile scraped by the YT Tile Scraper Chrome extension.
 * Maps directly to the JSON object the extension POSTs inside the {@code tiles} array.
 *
 * Unknown fields from future extension updates are silently ignored via
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} so deserialization never breaks.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({
        "video_id", "title", "channel", "description",
        "thumbnail_url", "url", "tile_type",
        "scraped_at", "page_url", "received_at"
})
public class TileRecord {

    // ── Fields ────────────────────────────────────────────────────────────────

    @JsonProperty("video_id")
    private String videoId;

    @JsonProperty("title")
    private String title;

    @JsonProperty("channel")
    private String channel;

    @JsonProperty("description")
    private String description;

    @JsonProperty("thumbnail_url")
    private String thumbnailUrl;

    @JsonProperty("url")
    private String url;

    /**
     * The CSS selector name the extension used to locate this tile, e.g.
     * {@code "ytd-rich-item-renderer"} or {@code "fallback:link"}.
     */
    @JsonProperty("tile_type")
    private String tileType;

    /** ISO-8601 timestamp set by the extension at the moment of scraping. */
    @JsonProperty("scraped_at")
    private String scrapedAt;

    /** The full YouTube page URL that was open when this tile was scraped. */
    @JsonProperty("page_url")
    private String pageUrl;

    /** ISO-8601 timestamp set by the Java receiver when the POST arrived. */
    @JsonProperty("received_at")
    private String receivedAt;

    // ── Constructors ──────────────────────────────────────────────────────────

    /** No-arg constructor required by Jackson for deserialization. */
    public TileRecord() {}

    /** Full constructor — used when building a record programmatically. */
    public TileRecord(String videoId,
                      String title,
                      String channel,
                      String description,
                      String thumbnailUrl,
                      String url,
                      String tileType,
                      String scrapedAt,
                      String pageUrl,
                      String receivedAt) {
        this.videoId      = nonNull(videoId);
        this.title        = nonNull(title);
        this.channel      = nonNull(channel);
        this.description  = nonNull(description);
        this.thumbnailUrl = nonNull(thumbnailUrl);
        this.url          = nonNull(url);
        this.tileType     = nonNull(tileType);
        this.scrapedAt    = nonNull(scrapedAt);
        this.pageUrl      = nonNull(pageUrl);
        this.receivedAt   = nonNull(receivedAt);
    }

    // ── Deduplication key ─────────────────────────────────────────────────────

    /**
     * Returns the canonical deduplication key for this tile.
     *
     * <p>Preference order:
     * <ol>
     *   <li>{@code video_id} — set for all standard watch-page and Shorts tiles</li>
     *   <li>{@code url}      — fallback for tiles where the extension couldn't
     *                          extract a clean video ID</li>
     *   <li>empty string     — tile has no identity; caller should skip dedup</li>
     * </ol>
     *
     * @return A non-null, possibly-empty string uniquely identifying this tile.
     */
    public String deduplicationKey() {
        if (videoId != null && !videoId.isBlank()) return videoId.trim();
        if (url     != null && !url.isBlank())     return url.trim();
        return "";
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String getVideoId()      { return videoId; }
    public String getTitle()        { return title; }
    public String getChannel()      { return channel; }
    public String getDescription()  { return description; }
    public String getThumbnailUrl() { return thumbnailUrl; }
    public String getUrl()          { return url; }
    public String getTileType()     { return tileType; }
    public String getScrapedAt()    { return scrapedAt; }
    public String getPageUrl()      { return pageUrl; }
    public String getReceivedAt()   { return receivedAt; }

    // ── Setters (needed by Jackson for deserialization) ───────────────────────

    public void setVideoId(String videoId)           { this.videoId      = nonNull(videoId); }
    public void setTitle(String title)               { this.title        = nonNull(title); }
    public void setChannel(String channel)           { this.channel      = nonNull(channel); }
    public void setDescription(String description)   { this.description  = nonNull(description); }
    public void setThumbnailUrl(String thumbnailUrl) { this.thumbnailUrl = nonNull(thumbnailUrl); }
    public void setUrl(String url)                   { this.url          = nonNull(url); }
    public void setTileType(String tileType)         { this.tileType     = nonNull(tileType); }
    public void setScrapedAt(String scrapedAt)       { this.scrapedAt    = nonNull(scrapedAt); }
    public void setPageUrl(String pageUrl)           { this.pageUrl      = nonNull(pageUrl); }
    public void setReceivedAt(String receivedAt)     { this.receivedAt   = nonNull(receivedAt); }

    // ── Object overrides ──────────────────────────────────────────────────────

    @Override
    public String toString() {
        return "TileRecord{" +
               "videoId='"  + videoId  + '\'' +
               ", title='"  + title    + '\'' +
               ", channel='" + channel + '\'' +
               ", tileType='" + tileType + '\'' +
               '}';
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /** Converts {@code null} to an empty string so fields are never null. */
    private static String nonNull(String s) {
        return s != null ? s : "";
    }
}
