package com.youtubewatch;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VideoEnricher
 *
 * Silently fetches a YouTube watch page using Java's built-in HttpClient
 * and extracts the channel name and full description from the JSON blob
 * YouTube embeds inside every page as {@code ytInitialPlayerResponse}.
 *
 * No extra dependencies — only java.net.http (available since Java 11).
 * No browser tabs are opened.
 *
 * Usage:
 * <pre>
 *   VideoEnricher enricher = new VideoEnricher();
 *   enricher.enrichAsync("dQw4w9WgXcQ")
 *           .thenAccept(data -> {
 *               System.out.println(data.channel());
 *               System.out.println(data.description());
 *           });
 * </pre>
 */
public class VideoEnricher {

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final String WATCH_BASE = "https://www.youtube.com/watch?v=";

    /**
     * A realistic desktop Chrome User-Agent string.
     * YouTube serves a stripped-down page to unrecognised clients which may
     * omit the embedded JSON entirely, so we must present a known UA.
     */
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Safari/537.36";

    /**
     * Matches the channel name embedded in YouTube's page JSON.
     * Example: {@code "ownerChannelName":"Rick Astley"}
     */
    private static final Pattern CHANNEL_RE = Pattern.compile(
        "\"ownerChannelName\":\"([^\"]+)\""
    );

    /**
     * Matches the short (full) description in YouTube's page JSON.
     * The value may contain JSON-escaped sequences (\\n, \\", \\/  …).
     * The inner alternation {@code (?:[^"\\]|\\.)*} handles escaped chars.
     *
     * Example: {@code "shortDescription":"Never gonna give you up\\n..."}
     */
    private static final Pattern DESC_RE = Pattern.compile(
        "\"shortDescription\":\"((?:[^\"\\\\]|\\\\.)*)\""
    );

    /**
     * Matches the channel name from the microformat section — used as a
     * secondary source when {@link #CHANNEL_RE} finds nothing (e.g. for
     * some YouTube Shorts pages).
     */
    private static final Pattern CHANNEL_ALT_RE = Pattern.compile(
        "\"externalChannelId\":\"[^\"]+\"[^}]+\"ownerProfileUrl\":[^}]+\"title\":\"([^\"]+)\""
    );

    /**
     * Fallback: channel name sometimes also appears in og:title meta or
     * in the author meta tag which is simpler to parse.
     * {@code <link itemprop="name" content="Rick Astley">}
     */
    private static final Pattern CHANNEL_META_RE = Pattern.compile(
        "<link\\s+itemprop=\"name\"\\s+content=\"([^\"]+)\""
    );

    /**
     * Fallback description from the {@code <meta name="description">} tag.
     * Shorter than shortDescription but always present.
     */
    private static final Pattern DESC_META_RE = Pattern.compile(
        "<meta\\s+name=\"description\"\\s+content=\"([^\"]+)\""
    );

    // ── Result record ─────────────────────────────────────────────────────────

    /**
     * Immutable carrier for the two fields we enrich.
     * Both fields are non-null; empty string means "not found".
     */
    public record EnrichedData(String channel, String description) {
        /** Sentinel returned when a fetch fails or is skipped. */
        public static final EnrichedData EMPTY = new EnrichedData("", "");

        /** Returns true when at least one field was successfully extracted. */
        public boolean hasData() {
            return !channel.isBlank() || !description.isBlank();
        }
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private final HttpClient http;

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Creates a VideoEnricher backed by a single shared {@link HttpClient}.
     * The client is thread-safe and reused across all async calls.
     */
    public VideoEnricher() {
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Asynchronously fetches {@code youtube.com/watch?v=<videoId>} and
     * extracts the channel name and description.
     *
     * <p>The returned future <strong>never completes exceptionally</strong> —
     * any network error, timeout, or parse failure yields
     * {@link EnrichedData#EMPTY} so callers need no try/catch.</p>
     *
     * @param videoId  The YouTube video ID (e.g. {@code "dQw4w9WgXcQ"}).
     * @return A {@link CompletableFuture} that resolves to an
     *         {@link EnrichedData} instance.
     */
    public CompletableFuture<EnrichedData> enrichAsync(String videoId) {
        if (videoId == null || videoId.isBlank()) {
            return CompletableFuture.completedFuture(EnrichedData.EMPTY);
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(WATCH_BASE + videoId.trim()))
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept", "text/html,application/xhtml+xml")
            .timeout(Duration.ofSeconds(12))
            .GET()
            .build();

        return http
            .sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> parse(videoId, response))
            .exceptionally(e -> {
                System.err.printf(
                    "[VideoEnricher] Network error for %s: %s%n",
                    videoId,
                    e.getMessage()
                );
                return EnrichedData.EMPTY;
            });
    }

    // ── Internal logic ────────────────────────────────────────────────────────

    /**
     * Parses the HTTP response body and extracts channel + description.
     *
     * <p>Extraction strategy (each step is tried only if the previous failed):
     * <ol>
     *   <li>Channel: {@code "ownerChannelName":"..."} in ytInitialPlayerResponse</li>
     *   <li>Channel: {@code <link itemprop="name" content="...">} meta tag</li>
     *   <li>Description: {@code "shortDescription":"..."} in embedded JSON</li>
     *   <li>Description: {@code <meta name="description" content="...">} tag</li>
     * </ol>
     * </p>
     */
    private EnrichedData parse(String videoId, HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            System.err.printf(
                "[VideoEnricher] HTTP %d for video %s — skipping enrichment%n",
                response.statusCode(),
                videoId
            );
            return EnrichedData.EMPTY;
        }

        String html = response.body();

        // ── Channel name ────────────────────────────────────────────────────
        String channel = extract(CHANNEL_RE, html, 1);

        if (channel.isEmpty()) {
            // Secondary: itemprop meta tag
            channel = extract(CHANNEL_META_RE, html, 1);
        }

        // ── Description ─────────────────────────────────────────────────────
        String description = unescapeJson(extract(DESC_RE, html, 1));

        if (description.isEmpty()) {
            // Fallback: HTML meta description (shorter but always present)
            description = unescapeHtml(extract(DESC_META_RE, html, 1));
        }

        if (!channel.isEmpty() || !description.isEmpty()) {
            System.out.printf(
                "[VideoEnricher] ✔ %s → channel='%s'  desc=%d chars%n",
                videoId,
                channel,
                description.length()
            );
        } else {
            System.err.printf(
                "[VideoEnricher] ✘ %s → nothing extracted (page structure may have changed)%n",
                videoId
            );
        }

        return new EnrichedData(channel, description);
    }

    // ── Regex helpers ─────────────────────────────────────────────────────────

    /**
     * Applies {@code pattern} to {@code input} and returns capture group
     * {@code group}, or an empty string if there is no match.
     */
    private static String extract(Pattern pattern, String input, int group) {
        Matcher m = pattern.matcher(input);
        return m.find() ? m.group(group) : "";
    }

    // ── Escape helpers ────────────────────────────────────────────────────────

    /**
     * Converts JSON escape sequences in a string literal back to readable text.
     *
     * <p>YouTube's embedded JSON uses standard JSON escaping, so
     * {@code \\n} represents a newline, {@code \\"} a quote, etc.</p>
     */
    private static String unescapeJson(String s) {
        if (s == null || s.isEmpty()) return "";
        // Process two-character escape sequences first
        StringBuilder sb = new StringBuilder(s.length());
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < len) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n' -> {
                        sb.append('\n');
                        i++;
                    }
                    case 'r' -> {
                        sb.append('\r');
                        i++;
                    }
                    case 't' -> {
                        sb.append('\t');
                        i++;
                    }
                    case '"' -> {
                        sb.append('"');
                        i++;
                    }
                    case '\\' -> {
                        sb.append('\\');
                        i++;
                    }
                    case '/' -> {
                        sb.append('/');
                        i++;
                    }
                    case 'u' -> {
                        // u+XXXX — four hex digits
                        if (i + 5 < len) {
                            String hex = s.substring(i + 2, i + 6);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                                i += 5;
                            } catch (NumberFormatException e) {
                                sb.append(c);
                            }
                        } else {
                            sb.append(c);
                        }
                    }
                    default -> sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Decodes the handful of HTML entities that YouTube uses inside
     * {@code <meta>} tag content attributes.
     */
    private static String unescapeHtml(String s) {
        if (s == null || s.isEmpty()) return "";
        return s
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'");
    }
}
