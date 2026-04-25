package com.youtubewatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VideoEnricher
 *
 * Silently enriches a YouTube tile with channel name and full description
 * without opening any browser tab.
 *
 * Channel name  -> YouTube oEmbed JSON API  (no auth required, always reliable)
 * Description   -> youtube.com/watch page   (char-by-char JSON string parser)
 *
 * Parallelism
 * -----------
 * Uses a CachedThreadPool passed into HttpClient so every tile fetch gets
 * its own thread and its own HTTP/1.1 TCP connection.  With HTTP/2 (the
 * default) all requests share one multiplexed connection and YouTube applies
 * per-connection flow-control, making fetches appear sequential.  Forcing
 * HTTP/1.1 gives each request an independent socket so they all proceed
 * simultaneously.
 */
public class VideoEnricher {

    // ── URL constants ─────────────────────────────────────────────────────────

    private static final String WATCH_BASE =
        "https://www.youtube.com/watch?v=";

    private static final String OEMBED_BASE =
        "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=";

    // ── HTTP headers ──────────────────────────────────────────────────────────

    /** Desktop Chrome UA — ensures YouTube returns the full metadata page. */
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Safari/537.36";

    /**
     * SOCS=CAI bypasses YouTube's GDPR consent interstitial.
     * Without this cookie YouTube returns the consent-gate HTML which
     * contains no video metadata at all.
     */
    private static final String CONSENT_COOKIE = "CONSENT=YES+cb; SOCS=CAI";

    // ── Fallback patterns ─────────────────────────────────────────────────────

    /** Fallback channel from page HTML when oEmbed fails. */
    private static final Pattern CHANNEL_RE =
        Pattern.compile("\"ownerChannelName\":\"([^\"]+)\"");

    /** Fallback description from the short meta tag (truncated but always present). */
    private static final Pattern DESC_META_RE =
        Pattern.compile("<meta\\s+name=\"description\"\\s+content=\"([^\"]+)\"");

    // ── Jackson mapper (thread-safe after construction) ───────────────────────

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Result record ─────────────────────────────────────────────────────────

    /**
     * Immutable result carrier.
     * Both fields are non-null; empty string means the value was not found.
     */
    public record EnrichedData(String channel, String description) {

        public static final EnrichedData EMPTY = new EnrichedData("", "");

        public boolean hasData() {
            return !channel.isBlank() || !description.isBlank();
        }
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private final ExecutorService executor;
    private final HttpClient      http;

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Builds a VideoEnricher backed by a CachedThreadPool.
     *
     * CachedThreadPool creates one thread per in-flight request (reusing
     * idle ones) so a batch of 24 tiles spawns up to 48 threads (one oEmbed
     * + one watch-page fetch per tile) and they all run simultaneously.
     *
     * HTTP_1_1 is set explicitly so Java's HttpClient opens a separate TCP
     * connection for every sendAsync call instead of multiplexing them over
     * one HTTP/2 stream where YouTube would throttle concurrent requests.
     */
    public VideoEnricher() {
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "enricher-worker");
            t.setDaemon(true);
            return t;
        });

        this.http = HttpClient.newBuilder()
            .executor(executor)
            .version(Version.HTTP_1_1)          // one TCP connection per request
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

        System.out.println(
            "[VideoEnricher] Ready — CachedThreadPool + HTTP/1.1 " +
            "(one connection per tile, fully parallel)"
        );
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fires two HTTP requests in parallel for {@code videoId}:
     *   1. oEmbed endpoint  -> channel name  (JSON, fast)
     *   2. watch page       -> description   (HTML, char-by-char parse)
     *
     * The returned future never completes exceptionally.
     * Any network or parse failure returns {@link EnrichedData#EMPTY}.
     *
     * @param videoId YouTube video ID e.g. "dQw4w9WgXcQ"
     * @return CompletableFuture resolving to an EnrichedData instance.
     */
    public CompletableFuture<EnrichedData> enrichAsync(String videoId) {
        if (videoId == null || videoId.isBlank()) {
            return CompletableFuture.completedFuture(EnrichedData.EMPTY);
        }

        String vid = videoId.trim();

        // Both futures are fired immediately — they run in parallel
        CompletableFuture<String> channelFuture  = fetchChannel(vid);
        CompletableFuture<String> descFuture     = fetchDescription(vid);

        return channelFuture
            .thenCombine(descFuture, (channel, desc) -> {
                EnrichedData result = new EnrichedData(channel, desc);
                if (result.hasData()) {
                    System.out.printf(
                        "[VideoEnricher] ✔ %-13s  channel='%s'  desc=%d chars%n",
                        vid, channel, desc.length()
                    );
                } else {
                    System.err.printf(
                        "[VideoEnricher] ✘ %-13s  nothing extracted%n", vid
                    );
                }
                return result;
            })
            .exceptionally(e -> {
                System.err.printf(
                    "[VideoEnricher] Error for %s: %s%n", vid, e.getMessage()
                );
                return EnrichedData.EMPTY;
            });
    }

    /** Gracefully shuts down the thread pool. Call when the receiver stops. */
    public void shutdown() {
        executor.shutdown();
    }

    // ── Channel fetch — oEmbed ────────────────────────────────────────────────

    /**
     * Calls the YouTube oEmbed endpoint and reads "author_name" from the JSON.
     * This is YouTube's official embed API — requires no auth key and always
     * returns a reliable channel name without any HTML scraping.
     */
    private CompletableFuture<String> fetchChannel(String videoId) {
        String url = OEMBED_BASE + videoId + "&format=json";

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", USER_AGENT)
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();

        return http
            .sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                if (resp.statusCode() != 200) {
                    // oEmbed returns 404 for private / deleted videos — that is normal
                    return fallbackChannelFromPage(videoId);
                }
                try {
                    JsonNode node = MAPPER.readTree(resp.body());
                    String name = node.path("author_name").asText("").trim();
                    return name.isEmpty() ? fallbackChannelFromPage(videoId) : name;
                } catch (Exception e) {
                    return "";
                }
            })
            .exceptionally(e -> "");
    }

    /**
     * Secondary channel extraction: reads "ownerChannelName" from the
     * embedded ytInitialPlayerResponse on the watch page.
     * Only called when oEmbed fails (private/deleted/unlisted videos).
     */
    private String fallbackChannelFromPage(String videoId) {
        // Synchronous fallback — called only on oEmbed failure, rare path
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(WATCH_BASE + videoId))
                .header("User-Agent", USER_AGENT)
                .header("Cookie", CONSENT_COOKIE)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

            HttpResponse<String> resp =
                http.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) return "";

            Matcher m = CHANNEL_RE.matcher(resp.body());
            return m.find() ? m.group(1) : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ── Description fetch — watch page ────────────────────────────────────────

    /**
     * Fetches youtube.com/watch?v={videoId} and extracts the full description
     * from YouTube's embedded ytInitialPlayerResponse JavaScript object.
     *
     * The SOCS=CAI cookie bypasses the GDPR consent gate so we always get
     * the real page content.
     *
     * Falls back to the meta description tag if shortDescription is absent.
     */
    private CompletableFuture<String> fetchDescription(String videoId) {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(WATCH_BASE + videoId))
            .header("User-Agent",      USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Cookie", CONSENT_COOKIE)
            .timeout(Duration.ofSeconds(12))
            .GET()
            .build();

        return http
            .sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                if (resp.statusCode() != 200) return "";

                String html = resp.body();

                // Primary: full description from embedded JS
                String desc = parseShortDescription(html);

                // Fallback: meta description (truncated ~160 chars but always present)
                if (desc.isBlank()) {
                    Matcher m = DESC_META_RE.matcher(html);
                    if (m.find()) desc = unescapeHtml(m.group(1));
                }

                return desc;
            })
            .exceptionally(e -> "");
    }

    // ── Short description parser ──────────────────────────────────────────────

    /**
     * Finds the "shortDescription":"..." key in the YouTube page HTML and
     * returns the decoded string value.
     *
     * Uses a character-by-character walk instead of a regex to:
     *   - Correctly handle all JSON escape sequences (backslash-n, backslash-t,
     *     backslash-quote, backslash-backslash, backslash-slash, unicode escapes)
     *   - Avoid catastrophic backtracking on descriptions that are thousands
     *     of characters long
     *
     * @param html Raw HTML of the YouTube watch page
     * @return Decoded description, or "" if the key is not present
     */
    private static String parseShortDescription(String html) {
        final String MARKER = "\"shortDescription\":\"";
        int idx = html.indexOf(MARKER);
        if (idx == -1) return "";

        int start   = idx + MARKER.length();
        int limit   = Math.min(start + 60_000, html.length()); // cap at 60 KB
        StringBuilder sb = new StringBuilder(256);
        boolean escaped = false;

        for (int i = start; i < limit; i++) {
            char c = html.charAt(i);

            if (escaped) {
                switch (c) {
                    case 'n'  -> sb.append('\n');
                    case 'r'  -> sb.append('\r');
                    case 't'  -> sb.append('\t');
                    case '"'  -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/'  -> sb.append('/');
                    case 'b'  -> sb.append('\b');
                    case 'f'  -> sb.append('\f');
                    case 'u'  -> {
                        // 4-hex-digit unicode escape
                        if (i + 4 < limit) {
                            try {
                                int cp = Integer.parseInt(
                                    html.substring(i + 1, i + 5), 16);
                                sb.append((char) cp);
                                i += 4;
                            } catch (NumberFormatException ignored) {
                                sb.append(c);
                            }
                        } else {
                            sb.append(c);
                        }
                    }
                    default -> sb.append(c);
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break; // closing quote — string ends here
            } else {
                sb.append(c);
            }
        }

        return sb.toString();
    }

    // ── HTML entity decoder ───────────────────────────────────────────────────

    private static String unescapeHtml(String s) {
        if (s == null || s.isEmpty()) return "";
        return s
            .replace("&amp;",  "&")
            .replace("&lt;",   "<")
            .replace("&gt;",   ">")
            .replace("&quot;", "\"")
            .replace("&#39;",  "'")
            .replace("&apos;", "'");
    }
}
