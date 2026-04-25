package com.youtubewatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService; // NOSONAR
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VideoEnricher
 *
 * Fetches channel name and description for a YouTube video without opening
 * any browser tabs.
 *
 * Strategy
 * ────────
 *  Channel name  → YouTube oEmbed API  (JSON, no auth, always reliable)
 *                  https://www.youtube.com/oembed?url=...&format=json
 *
 *  Description   → youtube.com/watch?v={id}  page fetch
 *                  Extracts "shortDescription" from the embedded
 *                  ytInitialPlayerResponse JS object using a char-by-char
 *                  JSON-string parser (avoids regex backtracking on very
 *                  long descriptions and correctly handles all escape seqs).
 *
 * Threading
 * ─────────
 *  A dedicated ExecutorService is created at construction time and passed
 *  into the HttpClient.  Every sendAsync call runs on this pool so all
 *  tile fetches truly execute in parallel regardless of the caller's thread.
 *  Pool size = min(16, availableProcessors × 2).
 */
public class VideoEnricher {

    // ── URL bases ─────────────────────────────────────────────────────────────

    private static final String WATCH_BASE = "https://www.youtube.com/watch?v=";

    private static final String OEMBED_BASE =
        "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=";

    // ── HTTP headers ──────────────────────────────────────────────────────────

    /** Desktop Chrome UA — YouTube returns the full page for known browsers. */
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Safari/537.36";

    /**
     * SOCS=CAI bypasses YouTube's GDPR consent interstitial.
     * Without it, YouTube returns the consent gate HTML which contains no
     * video metadata at all.
     */
    private static final String CONSENT_COOKIE = "CONSENT=YES+cb; SOCS=CAI";

    // ── Fallback: meta description tag ───────────────────────────────────────

    private static final Pattern DESC_META_RE = Pattern.compile(
        "<meta\\s+name=\"description\"\\s+content=\"([^\"]+)\""
    );

    // ── Jackson ───────────────────────────────────────────────────────────────

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    // ── Result record ─────────────────────────────────────────────────────────

    /**
     * Immutable result carrier.
     * Both fields are non-null; empty string means "not found / fetch failed".
     */
    public record EnrichedData(String channel, String description) {
        public static final EnrichedData EMPTY = new EnrichedData("", "");

        public boolean hasData() {
            return !channel.isBlank() || !description.isBlank();
        }
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private final ExecutorService executor;
    private final HttpClient http;

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Creates a VideoEnricher with a dedicated thread pool.
     * Pool size = min(16, availableProcessors × 2) so even modest machines
     * parallelise effectively while avoiding thread explosion on large batches.
     */
    public VideoEnricher() {
        int threads = Math.min(
            16,
            Runtime.getRuntime().availableProcessors() * 2
        );
        this.executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "enricher-worker");
            t.setDaemon(true); // don't prevent JVM shutdown
            return t;
        });

        this.http = HttpClient.newBuilder()
            .executor(executor)
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

        System.out.printf(
            "[VideoEnricher] Initialised — thread pool size: %d%n",
            threads
        );
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Asynchronously fetches the channel name and description for
     * {@code videoId}.
     *
     * <p>Both the oEmbed request (channel) and the watch-page request
     * (description) are fired simultaneously and joined with
     * {@link CompletableFuture#thenCombine}.</p>
     *
     * <p>This method <strong>never completes exceptionally</strong> —
     * any error yields {@link EnrichedData#EMPTY}.</p>
     *
     * @param videoId YouTube video ID, e.g. {@code "dQw4w9WgXcQ"}
     * @return Future resolving to an {@link EnrichedData} instance.
     */
    public CompletableFuture<EnrichedData> enrichAsync(String videoId) {
        if (videoId == null || videoId.isBlank()) {
            return CompletableFuture.completedFuture(EnrichedData.EMPTY);
        }

        String vid = videoId.trim();

        // Fire both requests in parallel on the dedicated pool
        CompletableFuture<String> channelFuture = fetchChannel(vid);
        CompletableFuture<String> descFuture = fetchDescription(vid);

        return channelFuture
            .thenCombine(descFuture, (channel, desc) -> {
                EnrichedData result = new EnrichedData(channel, desc);
                if (result.hasData()) {
                    System.out.printf(
                        "[VideoEnricher] ✔ %-14s  channel='%s'  desc=%d chars%n",
                        vid,
                        channel,
                        desc.length()
                    );
                } else {
                    System.err.printf(
                        "[VideoEnricher] ✘ %-14s  nothing extracted%n",
                        vid
                    );
                }
                return result;
            })
            .exceptionally(e -> {
                System.err.printf(
                    "[VideoEnricher] Error for %s: %s%n",
                    vid,
                    e.getMessage()
                );
                return EnrichedData.EMPTY;
            });
    }

    /**
     * Gracefully shuts down the enricher's thread pool.
     * Call this when the receiver is stopping.
     */
    public void shutdown() {
        executor.shutdown();
        System.out.println("[VideoEnricher] Thread pool shut down.");
    }

    // ── Channel fetch — oEmbed JSON API ───────────────────────────────────────

    /**
     * Calls the YouTube oEmbed endpoint and extracts {@code author_name}.
     *
     * <p>oEmbed is YouTube's official embed API, requires no auth key, and
     * returns a small JSON object reliably — far more stable than scraping
     * the watch page HTML for a channel name.</p>
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
                    System.err.printf(
                        "[VideoEnricher] oEmbed HTTP %d for %s%n",
                        resp.statusCode(),
                        videoId
                    );
                    return "";
                }
                try {
                    JsonNode node = JSON_MAPPER.readTree(resp.body());
                    return node.path("author_name").asText("").trim();
                } catch (Exception e) {
                    System.err.printf(
                        "[VideoEnricher] oEmbed parse error for %s: %s%n",
                        videoId,
                        e.getMessage()
                    );
                    return "";
                }
            })
            .exceptionally(e -> {
                System.err.printf(
                    "[VideoEnricher] oEmbed fetch failed for %s: %s%n",
                    videoId,
                    e.getMessage()
                );
                return "";
            });
    }

    // ── Description fetch — watch page ────────────────────────────────────────

    /**
     * Fetches {@code youtube.com/watch?v=<videoId>} and extracts the full
     * description from YouTube's embedded {@code ytInitialPlayerResponse}
     * JSON object.
     *
     * <p>The {@code SOCS=CAI} cookie is sent with the request to bypass
     * YouTube's GDPR consent gate which would otherwise return a page that
     * contains no video metadata.</p>
     *
     * <p>Falls back to the {@code <meta name="description">} tag when the
     * embedded JSON is absent or the {@code shortDescription} key is missing.</p>
     */
    private CompletableFuture<String> fetchDescription(String videoId) {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(WATCH_BASE + videoId))
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            .header("Cookie", CONSENT_COOKIE)
            .timeout(Duration.ofSeconds(12))
            .GET()
            .build();

        return http
            .sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                if (resp.statusCode() != 200) {
                    System.err.printf(
                        "[VideoEnricher] Watch page HTTP %d for %s%n",
                        resp.statusCode(),
                        videoId
                    );
                    return "";
                }

                String html = resp.body();

                // Primary: shortDescription from embedded JS object
                String desc = parseShortDescription(html);

                // Fallback: <meta name="description"> (truncated but reliable)
                if (desc.isBlank()) {
                    Matcher m = DESC_META_RE.matcher(html);
                    if (m.find()) {
                        desc = unescapeHtml(m.group(1));
                    }
                }

                return desc;
            })
            .exceptionally(e -> {
                System.err.printf(
                    "[VideoEnricher] Watch page fetch failed for %s: %s%n",
                    videoId,
                    e.getMessage()
                );
                return "";
            });
    }

    // ── Description parser ────────────────────────────────────────────────────

    /**
     * Locates the {@code "shortDescription":"..."} key inside YouTube's
     * embedded {@code ytInitialPlayerResponse} JS object and returns the
     * decoded string value.
     *
     * <p>Uses a character-by-character walk rather than a regex to correctly
     * handle all JSON escape sequences ({@code \n \t \\ \" \/} and
     * unicode escapes) and to avoid catastrophic backtracking on descriptions
     * that are thousands of characters long.</p>
     *
     * @param html Raw HTML of the YouTube watch page.
     * @return Decoded description string, or {@code ""} if not found.
     */
    private static String parseShortDescription(String html) {
        final String MARKER = "\"shortDescription\":\"";
        int idx = html.indexOf(MARKER);
        if (idx == -1) return "";

        int start = idx + MARKER.length();
        int limit = Math.min(start + 50_000, html.length()); // cap at ~50 KB
        StringBuilder sb = new StringBuilder(512);
        boolean escaped = false;

        for (int i = start; i < limit; i++) {
            char c = html.charAt(i);

            if (escaped) {
                switch (c) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        // 4-hex-digit unicode escape
                        if (i + 4 < limit) {
                            String hex = html.substring(i + 1, i + 5);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                                i += 4;
                            } catch (NumberFormatException e) {
                                sb.append(c); // not valid hex — keep literally
                            }
                        } else {
                            sb.append(c);
                        }
                    }
                    default -> sb.append(c); // unknown escape — keep as-is
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break; // reached the closing quote — we're done
            } else {
                sb.append(c);
            }
        }

        return sb.toString();
    }

    // ── HTML entity decoder ───────────────────────────────────────────────────

    /**
     * Decodes the small set of HTML entities that YouTube uses inside
     * {@code <meta>} tag {@code content} attributes.
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
