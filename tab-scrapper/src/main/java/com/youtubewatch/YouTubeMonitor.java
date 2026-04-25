package com.youtubewatch;

import io.github.bonigarcia.wdm.WebDriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.NoSuchWindowException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * YouTubeMonitor
 *
 * A Selenium 4 based YouTube watcher that:
 *  - Polls for SPA URL changes without restarting the driver
 *  - Scrapes video title and meta keywords on every new watch page
 *  - Injects a full-screen blackout overlay (via JavascriptExecutor) and
 *    pauses playback if any scraped tag matches the forbidden list
 *  - Streams a VideoMetadata snapshot to an individual JSON file in /logs
 *    for every video visited
 *
 * Run via Gradle:
 *   ./gradlew run
 *
 * Override the start URL:
 *   ./gradlew run -Purl="https://www.youtube.com/watch?v=dQw4w9WgXcQ"
 *
 * Override the forbidden tags (comma-separated):
 *   ./gradlew run -Ptags="gaming,violence,gambling"
 */
public class YouTubeMonitor {

    // ── Defaults (can be overridden via JVM system properties) ───────────────

    private static final String DEFAULT_START_URL = "https://www.youtube.com";

    /**
     * Default forbidden tag list.  Override at runtime by passing
     * {@code -Dforbidden.tags=tag1,tag2,...} (set automatically when you
     * use {@code ./gradlew run -Ptags="..."}).
     */
    private static final List<String> DEFAULT_FORBIDDEN_TAGS = Arrays.asList(
        "gaming",
        "violence",
        "gambling",
        "nsfw",
        "adult content",
        "horror",
        "drugs",
        "explicit",
        "18+",
        "gore"
    );

    // ── Constants ─────────────────────────────────────────────────────────────

    /** DOM id of the injected overlay so we can locate and remove it later. */
    private static final String BLACKOUT_DIV_ID = "yt-monitor-blackout";

    /** How long to wait between URL-change polls (milliseconds). */
    private static final int POLL_INTERVAL_MS = 1_500;

    /**
     * After a URL change is detected, wait this long before scraping so the
     * YouTube SPA has time to render the new page's <head> metadata.
     */
    private static final int PAGE_SETTLE_MS = 2_500;

    /** Maximum time WebDriverWait will block waiting for a DOM element. */
    private static final Duration ELEMENT_WAIT = Duration.ofSeconds(12);

    /** Maximum time we wait for the YouTube consent screen button. */
    private static final Duration CONSENT_WAIT = Duration.ofSeconds(6);

    // ── Instance state ────────────────────────────────────────────────────────

    private final WebDriver driver;
    private final JsonLogger logger;
    private final List<String> forbiddenTags;

    /** Last URL seen by the polling loop. */
    private String lastKnownUrl = "";

    /**
     * Last processed video-id (the {@code v=} param value).
     * Prevents re-processing the same video when the URL gains extra
     * query params (e.g. YouTube appending {@code &t=30s}).
     */
    private String lastProcessedVideoId = "";

    // ── Construction ──────────────────────────────────────────────────────────

    public YouTubeMonitor() {
        // ── Resolve forbidden tags from system property or use defaults ────
        String tagsProp = System.getProperty("forbidden.tags");
        if (tagsProp != null && !tagsProp.isBlank()) {
            forbiddenTags = Arrays.stream(tagsProp.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(t -> !t.isEmpty())
                .collect(Collectors.toList());
            System.out.println(
                "[YouTubeMonitor] Forbidden tags loaded from property: " +
                    forbiddenTags
            );
        } else {
            forbiddenTags = DEFAULT_FORBIDDEN_TAGS;
            System.out.println(
                "[YouTubeMonitor] Using default forbidden tags: " +
                    forbiddenTags
            );
        }

        // ── WebDriverManager sets up the matching ChromeDriver binary ──────
        WebDriverManager.chromedriver().setup();

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--start-maximized");
        options.addArguments("--disable-notifications");
        // Force English UI so our XPath consent-button text selectors work
        options.addArguments("--lang=en-US,en");
        options.addArguments("--disable-search-engine-choice-screen");
        // Uncomment the line below if you want headless (invisible) mode:
        // options.addArguments("--headless=new");

        driver = new ChromeDriver(options);
        logger = new JsonLogger();

        System.out.println(
            "[YouTubeMonitor] Logs directory: " + logger.getLogsDirPath()
        );
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * Opens the browser, handles any consent screen, then enters the
     * infinite watcher loop.  Exits cleanly on Ctrl+C.
     *
     * @param startUrl The URL to open when the browser first launches.
     */
    public void start(String startUrl) {
        System.out.println("[YouTubeMonitor] Opening: " + startUrl);
        driver.get(startUrl);

        // Register a shutdown hook so the driver quits gracefully on Ctrl+C
        Runtime.getRuntime().addShutdownHook(
            new Thread(
                () -> {
                    System.out.println(
                        "\n[YouTubeMonitor] Shutdown signal received — closing browser."
                    );
                    try {
                        driver.quit();
                    } catch (Exception ignored) {}
                },
                "driver-shutdown-hook"
            )
        );

        handleConsentScreen();

        System.out.println(
            "[YouTubeMonitor] Watcher loop started.  Navigate YouTube normally."
        );
        System.out.println("[YouTubeMonitor] Press Ctrl+C to stop.\n");

        watchLoop();
    }

    // ── Watcher loop ──────────────────────────────────────────────────────────

    /**
     * Polls {@link WebDriver#getCurrentUrl()} on a fixed cadence and reacts
     * whenever the SPA navigates to a new YouTube watch page.
     *
     * Because YouTube is a Single-Page Application the browser never triggers
     * a full page load between videos; comparing the URL string is the only
     * reliable cross-platform way to detect navigation without installing a
     * custom browser extension.
     */
    private void watchLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String currentUrl = driver.getCurrentUrl();

                if (!currentUrl.equals(lastKnownUrl)) {
                    lastKnownUrl = currentUrl;
                    System.out.println("[Watcher] URL changed → " + currentUrl);

                    if (isVideoUrl(currentUrl)) {
                        String videoId = extractVideoId(currentUrl);
                        if (!videoId.equals(lastProcessedVideoId)) {
                            lastProcessedVideoId = videoId;
                            processVideoPage(currentUrl);
                        }
                    } else {
                        // Navigated away from a video (e.g. back to home) –
                        // remove any leftover overlay.
                        removeBlackout();
                    }
                }

                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("[Watcher] Interrupted — exiting loop.");
            } catch (WebDriverException e) {
                // Browser window was closed manually (NoSuchWindowException is a subclass)
                System.out.println("[Watcher] Browser closed — exiting loop.");
                break;
            }
        }
    }

    // ── Per-video processing pipeline ─────────────────────────────────────────

    /**
     * Full pipeline executed for every newly detected YouTube watch URL:
     * <ol>
     *   <li>Wait for the SPA to settle</li>
     *   <li>Scrape title and meta keywords</li>
     *   <li>Evaluate against forbidden tags → apply or remove blackout</li>
     *   <li>Persist a {@link VideoMetadata} snapshot to the logs folder</li>
     * </ol>
     */
    private void processVideoPage(String url) {
        System.out.println("[Pipeline] Processing video: " + url);

        // Give the SPA time to swap in the new page's <head> metadata
        sleepQuietly(PAGE_SETTLE_MS);

        String title = scrapeTitle();
        List<String> tags = scrapeTags();
        boolean blocked = evaluateAndApplyBlackout(tags);

        System.out.printf("[Pipeline] Title   : %s%n", title);
        System.out.printf("[Pipeline] Tags    : %s%n", tags);
        System.out.printf("[Pipeline] Blocked : %b%n%n", blocked);

        VideoMetadata metadata = new VideoMetadata(
            title,
            url,
            tags,
            blocked,
            Instant.now().toString()
        );

        try {
            logger.log(metadata);
        } catch (JsonLogger.JsonLoggerException e) {
            System.err.println(
                "[Pipeline] ERROR saving log: " + e.getMessage()
            );
        }
    }

    // ── Metadata scraping ─────────────────────────────────────────────────────

    /**
     * Scrapes the video title from the YouTube watch page.
     *
     * <p>Primary strategy: wait for the {@code h1} inside
     * {@code ytd-watch-metadata} (current YouTube DOM as of mid-2024).<br>
     * Fallback: strip " - YouTube" from the browser tab title so we always
     * return something meaningful even if YouTube restructures their DOM.</p>
     *
     * @return Human-readable video title, never {@code null}.
     */
    private String scrapeTitle() {
        // Selector list in priority order — first match wins.
        // YouTube occasionally A/B tests its DOM so having multiple candidates
        // makes the scraper resilient to minor layout changes.
        List<By> titleSelectors = Arrays.asList(
            By.cssSelector("h1.ytd-watch-metadata yt-formatted-string"),
            By.cssSelector("ytd-watch-metadata h1 yt-formatted-string"),
            By.cssSelector("h1.title.ytd-video-primary-info-renderer"),
            By.cssSelector("#title h1 yt-formatted-string")
        );

        WebDriverWait wait = new WebDriverWait(driver, ELEMENT_WAIT);

        for (By selector : titleSelectors) {
            try {
                WebElement el = wait.until(
                    ExpectedConditions.visibilityOfElementLocated(selector)
                );
                String text = el.getText().trim();
                if (!text.isEmpty()) {
                    return text;
                }
            } catch (TimeoutException ignored) {
                // Try the next candidate
            }
        }

        // Final fallback: browser tab title
        try {
            String tabTitle = driver.getTitle();
            if (tabTitle != null && tabTitle.contains(" - YouTube")) {
                return tabTitle.replace(" - YouTube", "").trim();
            }
            if (tabTitle != null && !tabTitle.isBlank()) {
                return tabTitle.trim();
            }
        } catch (WebDriverException ignored) {}

        return "Unknown Title";
    }

    /**
     * Reads the {@code content} attribute of {@code <meta name="keywords">}.
     *
     * <p>YouTube populates this tag with the video's tags (the ones the
     * uploader chose), making it the most reliable single source for tag data
     * without needing to parse the rendered JS model.</p>
     *
     * @return Lowercased, trimmed list of keyword strings.
     *         Returns an empty list (never {@code null}) when the tag is absent
     *         or has no content — ensuring downstream code never throws NPEs.
     */
    private List<String> scrapeTags() {
        try {
            // meta tags are present immediately in the DOM — no wait needed
            WebElement metaKeywords = driver.findElement(
                By.cssSelector("meta[name='keywords']")
            );

            String content = metaKeywords.getAttribute("content");
            if (content == null || content.isBlank()) {
                System.out.println("[Scraper] meta[name='keywords'] is empty.");
                return Collections.emptyList();
            }

            return Arrays.stream(content.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(t -> !t.isEmpty())
                .collect(Collectors.toList());
        } catch (org.openqa.selenium.NoSuchElementException e) {
            System.out.println(
                "[Scraper] No meta keywords tag found — video may have no tags."
            );
            return Collections.emptyList();
        } catch (WebDriverException e) {
            System.err.println(
                "[Scraper] WebDriver error reading meta keywords: " +
                    e.getMessage()
            );
            return Collections.emptyList();
        }
    }

    // ── Blackout logic ────────────────────────────────────────────────────────

    /**
     * Compares the scraped tag list against {@link #forbiddenTags}.
     * Applies a blackout overlay when a match is found, or removes any
     * pre-existing overlay when the video is clean.
     *
     * @param tags Tags scraped from the current video's meta keywords.
     * @return {@code true} if the video was blocked, {@code false} otherwise.
     */
    private boolean evaluateAndApplyBlackout(List<String> tags) {
        // Always clean up any overlay left from the previous video first
        removeBlackout();

        for (String tag : tags) {
            for (String forbidden : forbiddenTags) {
                if (tag.contains(forbidden) || forbidden.contains(tag)) {
                    System.out.println(
                        "[Blackout] Forbidden tag matched: '" +
                            tag +
                            "' ← rule: '" +
                            forbidden +
                            "'"
                    );
                    applyBlackout(tag);
                    return true;
                }
            }
        }

        System.out.println("[Blackout] No forbidden tags — video allowed.");
        return false;
    }

    /**
     * Uses {@link JavascriptExecutor} to:
     * <ol>
     *   <li>Pause the HTML5 {@code <video>} element.</li>
     *   <li>Inject a full-viewport black {@code <div>} overlay with a
     *       descriptive message so the user knows why the screen is black.</li>
     * </ol>
     *
     * The overlay is idempotent — calling this method twice for the same page
     * will not create duplicate overlays because we first check for the id.
     *
     * @param matchedTag The tag that triggered the block (shown in the overlay).
     */
    private void applyBlackout(String matchedTag) {
        try {
            JavascriptExecutor js = (JavascriptExecutor) driver;

            // 1. Pause video playback
            js.executeScript(
                "var v = document.querySelector('video');" +
                    "if (v) { v.pause(); v.volume = 0; }"
            );

            // 2. Inject overlay (only if not already present)
            String safeTag = matchedTag.replace("'", "\\'");
            String script =
                "if (!document.getElementById('" +
                BLACKOUT_DIV_ID +
                "')) {" +
                // -- Outer overlay container
                "  var overlay = document.createElement('div');" +
                "  overlay.id = '" +
                BLACKOUT_DIV_ID +
                "';" +
                "  overlay.style.cssText = [" +
                "    'position:fixed'," +
                "    'top:0'," +
                "    'left:0'," +
                "    'width:100vw'," +
                "    'height:100vh'," +
                "    'background-color:#000000'," +
                "    'z-index:2147483647'," +
                "    'display:flex'," +
                "    'flex-direction:column'," +
                "    'align-items:center'," +
                "    'justify-content:center'," +
                "    'font-family:system-ui,sans-serif'," +
                "    'cursor:default'" +
                "  ].join(';');" +
                // -- Icon
                "  var icon = document.createElement('div');" +
                "  icon.innerText = '\u26D4';" +
                "  icon.style.cssText = 'font-size:5rem;margin-bottom:1.5rem;';" +
                // -- Headline
                "  var headline = document.createElement('div');" +
                "  headline.innerText = 'Content Blocked';" +
                "  headline.style.cssText = [" +
                "    'color:#ffffff'," +
                "    'font-size:2.2rem'," +
                "    'font-weight:700'," +
                "    'margin-bottom:0.75rem'," +
                "    'letter-spacing:0.02em'" +
                "  ].join(';');" +
                // -- Reason
                "  var reason = document.createElement('div');" +
                "  reason.innerText = 'Forbidden tag detected: \"" +
                safeTag +
                "\"';" +
                "  reason.style.cssText = [" +
                "    'color:#aaaaaa'," +
                "    'font-size:1.1rem'," +
                "    'margin-bottom:2rem'" +
                "  ].join(';');" +
                // -- Sub-hint
                "  var hint = document.createElement('div');" +
                "  hint.innerText = 'Navigate to another video to continue.';" +
                "  hint.style.cssText = 'color:#666666;font-size:0.9rem;';" +
                "  overlay.appendChild(icon);" +
                "  overlay.appendChild(headline);" +
                "  overlay.appendChild(reason);" +
                "  overlay.appendChild(hint);" +
                "  document.body.appendChild(overlay);" +
                "}";

            js.executeScript(script);
            System.out.println("[Blackout] Overlay injected successfully.");
        } catch (WebDriverException e) {
            System.err.println(
                "[Blackout] Failed to inject overlay: " + e.getMessage()
            );
        }
    }

    /**
     * Removes the blackout overlay injected by {@link #applyBlackout(String)}
     * if it is present in the current DOM.  Safe to call even when no overlay
     * exists — the JS checks before attempting removal.
     */
    private void removeBlackout() {
        try {
            JavascriptExecutor js = (JavascriptExecutor) driver;
            js.executeScript(
                "var el = document.getElementById('" +
                    BLACKOUT_DIV_ID +
                    "');" +
                    "if (el) { el.parentNode.removeChild(el); }"
            );
        } catch (WebDriverException ignored) {
            // Safe to swallow — the page may have fully navigated away,
            // which automatically destroys any previously injected DOM nodes.
        }
    }

    // ── YouTube consent / cookie screen handler ───────────────────────────────

    /**
     * Handles YouTube's GDPR consent interstitial ("Before you continue to
     * YouTube…") that appears in EU/EEA regions or for fresh browser profiles.
     *
     * <p>Strategy: try both "Reject all" and "Accept all" button variants with
     * a short timeout so we don't slow down runs where the screen never appears.
     * If neither button is found within {@link #CONSENT_WAIT} we log a notice
     * and continue — the screen simply wasn't shown.</p>
     */
    private void handleConsentScreen() {
        System.out.println("[Consent] Checking for consent / cookie screen...");

        // XPath texts to try, in preference order.
        // "Reject all" is preferred for privacy; "Accept all" is the fallback.
        List<String> buttonLabels = Arrays.asList(
            "Reject all",
            "Reject All",
            "REJECT ALL",
            "Accept all",
            "Accept All",
            "ACCEPT ALL",
            "I agree",
            "Agree"
        );

        WebDriverWait wait = new WebDriverWait(driver, CONSENT_WAIT);

        for (String label : buttonLabels) {
            try {
                // Match buttons whose visible text contains the label string.
                // Using `normalize-space` trims whitespace YouTube sometimes adds.
                By locator = By.xpath(
                    "//button[contains(normalize-space(.), '" + label + "')]"
                );

                WebElement button = wait.until(
                    ExpectedConditions.elementToBeClickable(locator)
                );

                button.click();
                System.out.println(
                    "[Consent] Dismissed with button: \"" + label + "\""
                );

                // Brief pause to let YouTube process the choice and redirect
                sleepQuietly(1_200);
                return;
            } catch (TimeoutException ignored) {
                // This particular label wasn't found — try the next one
            } catch (WebDriverException e) {
                System.err.println(
                    "[Consent] Click error for label \"" +
                        label +
                        "\": " +
                        e.getMessage()
                );
            }
        }

        System.out.println(
            "[Consent] No consent screen detected — continuing normally."
        );
    }

    // ── URL helpers ───────────────────────────────────────────────────────────

    /**
     * Returns {@code true} when {@code url} represents a YouTube video watch
     * page, i.e. it contains both {@code youtube.com/watch} and a {@code v=}
     * query parameter.
     */
    private boolean isVideoUrl(String url) {
        return (
            url != null &&
            url.contains("youtube.com/watch") &&
            url.contains("v=")
        );
    }

    /**
     * Extracts the {@code v=} parameter value from a YouTube watch URL.
     *
     * <p>Returns an empty string on any parse failure so callers receive a safe
     * non-null value without try/catch boilerplate at the call site.</p>
     *
     * @param url Full YouTube watch URL string.
     * @return The video ID, e.g. {@code "dQw4w9WgXcQ"}, or {@code ""}.
     */
    private String extractVideoId(String url) {
        try {
            int vIdx = url.indexOf("v=");
            if (vIdx == -1) return "";
            String rest = url.substring(vIdx + 2);
            int ampIdx = rest.indexOf('&');
            return ampIdx == -1 ? rest : rest.substring(0, ampIdx);
        } catch (Exception e) {
            return "";
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /**
     * Sleeps for {@code millis} milliseconds, restoring the interrupt flag
     * if the thread is interrupted rather than swallowing it silently.
     */
    private void sleepQuietly(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── main ──────────────────────────────────────────────────────────────────

    /**
     * Application entry point.
     *
     * <p>Reads the optional {@code start.url} system property (set when you
     * run {@code ./gradlew run -Purl="..."}) and falls back to the YouTube
     * home page so the user can navigate to a video themselves.</p>
     */
    public static void main(String[] args) {
        String startUrl = System.getProperty("start.url", DEFAULT_START_URL);

        YouTubeMonitor monitor = new YouTubeMonitor();
        monitor.start(startUrl);
    }
}
