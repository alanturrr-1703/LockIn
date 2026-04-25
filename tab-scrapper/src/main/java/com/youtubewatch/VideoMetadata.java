package com.youtubewatch;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * Immutable data carrier representing a single YouTube video visit.
 * Jackson will serialize this directly into a pretty-printed JSON file.
 *
 * Field order in the output JSON is fixed by @JsonPropertyOrder so every
 * log file has the same column layout, making them easy to diff or ingest.
 */
@JsonPropertyOrder({"title", "url", "tags", "blocked", "timestamp"})
public class VideoMetadata {

    // ── Fields ────────────────────────────────────────────────────────────────

    @JsonProperty("title")
    private final String title;

    @JsonProperty("url")
    private final String url;

    /**
     * Raw keywords extracted from {@code <meta name="keywords">}, lowercased
     * and split on commas.  Empty list when YouTube provides no keywords.
     */
    @JsonProperty("tags")
    private final List<String> tags;

    /**
     * {@code true}  – at least one tag matched the forbidden list and the
     *                 blackout overlay was injected.<br>
     * {@code false} – video was allowed to play normally.
     */
    @JsonProperty("blocked")
    private final boolean blocked;

    /**
     * ISO-8601 UTC instant captured immediately after the scrape completes,
     * e.g. {@code "2024-07-15T10:30:00.123456Z"}.
     */
    @JsonProperty("timestamp")
    private final String timestamp;

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Creates a fully-populated, immutable {@code VideoMetadata} instance.
     *
     * @param title     Human-readable video title (never {@code null}).
     * @param url       Full YouTube watch URL including the {@code v=} param.
     * @param tags      Keyword list scraped from the page meta tag.
     * @param blocked   Whether the blackout overlay was triggered.
     * @param timestamp ISO-8601 UTC string, typically from
     *                  {@link java.time.Instant#toString()}.
     */
    public VideoMetadata(String title,
                         String url,
                         List<String> tags,
                         boolean blocked,
                         String timestamp) {
        this.title     = title     != null ? title     : "Unknown Title";
        this.url       = url       != null ? url       : "";
        this.tags      = tags      != null ? tags      : List.of();
        this.blocked   = blocked;
        this.timestamp = timestamp != null ? timestamp : "";
    }

    /**
     * No-arg constructor required by Jackson for deserialization
     * (useful if you later want to read existing log files back in).
     */
    public VideoMetadata() {
        this("Unknown Title", "", List.of(), false, "");
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String getTitle() {
        return title;
    }

    public String getUrl() {
        return url;
    }

    public List<String> getTags() {
        return tags;
    }

    public boolean isBlocked() {
        return blocked;
    }

    public String getTimestamp() {
        return timestamp;
    }

    // ── Object overrides ──────────────────────────────────────────────────────

    @Override
    public String toString() {
        return "VideoMetadata{" +
               "title='"    + title    + '\'' +
               ", url='"    + url      + '\'' +
               ", tags="    + tags     +
               ", blocked=" + blocked  +
               ", timestamp='" + timestamp + '\'' +
               '}';
    }
}
