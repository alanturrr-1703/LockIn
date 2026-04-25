package com.lockin.tabstream.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lockin.tabstream.dto.PagePayloadDTO;
import com.lockin.tabstream.dto.ParsedPageDTO;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Stateless Spring component that converts raw page HTML (or plain text)
 * into a structured {@link ParsedPageDTO}.
 *
 * <p>Design decisions:
 * <ul>
 *   <li>ObjectMapper is injected but not used for parsing here — it is
 *       available for any future JSON-embedded-in-page scenarios and keeps
 *       the constructor signature consistent with other services.</li>
 *   <li>All extraction limits (50 headings, 100 paragraphs, 50 000 char
 *       cleanText) are enforced here rather than in the service layer so
 *       that the parser is the single source of truth for content bounds.</li>
 * </ul>
 */
@Component
public class JSoupPageParser {

    private static final Logger log = LoggerFactory.getLogger(JSoupPageParser.class);

    private static final int MAX_HEADINGS   = 50;
    private static final int MAX_PARAGRAPHS = 100;
    private static final int MAX_CLEAN_TEXT = 50_000;

    private final ObjectMapper objectMapper;

    public JSoupPageParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Parse the payload and return a fully populated {@link ParsedPageDTO}.
     *
     * @param payload the raw page data from the extension
     * @param id      pre-generated UUID for this record
     * @param mode    "rest" or "websocket"
     * @return structured analysis result
     */
    public ParsedPageDTO parse(PagePayloadDTO payload, String id, String mode) {
        log.debug("Parsing page [id={}] url='{}' mode={} htmlLen={} textLen={}",
                id, payload.getUrl(),
                mode,
                payload.getHtml()  != null ? payload.getHtml().length()  : 0,
                payload.getText()  != null ? payload.getText().length()   : 0);

        Document doc = buildDocument(payload);

        // Strip noise elements before any text extraction
        stripBoilerplate(doc);

        String resolvedTitle    = resolveTitle(doc, payload.getTitle());
        List<String> headings   = extractHeadings(doc);
        List<ParsedPageDTO.LinkDTO> links = extractLinks(doc, payload.getUrl());
        List<String> paragraphs = extractParagraphs(doc);
        String cleanText        = buildCleanText(doc);
        int wordCount           = countWords(cleanText);
        int charCount           = cleanText.length();
        String metaDescription  = extractMetaDescription(doc);

        log.debug("Parsed [id={}]: title='{}' headings={} links={} paragraphs={} words={} chars={}",
                id, resolvedTitle, headings.size(), links.size(),
                paragraphs.size(), wordCount, charCount);

        return new ParsedPageDTO(
                id,
                resolvedTitle,
                payload.getUrl(),
                payload.getTimestamp(),
                headings,
                links,
                paragraphs,
                cleanText,
                wordCount,
                charCount,
                metaDescription,
                mode
        );
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Build a Jsoup {@link Document} from the payload.
     * If HTML is present it is parsed with the page URL as the base URI so that
     * relative hrefs can be resolved to absolute URLs.
     * If HTML is absent we wrap the plain text in a minimal HTML body so that
     * the rest of the pipeline can treat both cases uniformly.
     */
    private Document buildDocument(PagePayloadDTO payload) {
        if (payload.getHtml() != null && !payload.getHtml().isBlank()) {
            return Jsoup.parse(payload.getHtml(), payload.getUrl());
        }

        // Fallback: construct a minimal document from plain text
        log.debug("No HTML provided — constructing synthetic document from plain text");
        String safeText = payload.getText() != null ? payload.getText() : "";
        String syntheticHtml = "<html><head><title>" +
                escapeHtml(payload.getTitle()) +
                "</title></head><body><p>" +
                escapeHtml(safeText) +
                "</p></body></html>";
        return Jsoup.parse(syntheticHtml, payload.getUrl());
    }

    /**
     * Remove elements that contribute noise rather than content.
     * Modifying the document in place is intentional — the original HTML is
     * already persisted in {@code rawHtml} before this method is called.
     */
    private void stripBoilerplate(Document doc) {
        doc.select("script, style, noscript, svg, iframe, header, footer, nav").remove();
        log.debug("Boilerplate elements removed from document");
    }

    /**
     * Use the payload title; fall back to the document's own {@code <title>} tag
     * if the payload title is blank (e.g. extension sent an empty string).
     */
    private String resolveTitle(Document doc, String payloadTitle) {
        if (payloadTitle != null && !payloadTitle.isBlank()) {
            return payloadTitle;
        }
        String docTitle = doc.title();
        log.debug("Payload title blank — falling back to document title: '{}'", docTitle);
        return docTitle;
    }

    /**
     * Extract h1–h6 texts as a deduplicated, ordered list capped at {@value #MAX_HEADINGS}.
     */
    private List<String> extractHeadings(Document doc) {
        Elements elements = doc.select("h1, h2, h3, h4, h5, h6");
        // LinkedHashSet preserves insertion order while deduplicating
        Set<String> seen = new LinkedHashSet<>();
        for (Element el : elements) {
            String text = el.text().trim();
            if (!text.isEmpty()) {
                seen.add(text);
                if (seen.size() >= MAX_HEADINGS) break;
            }
        }
        return new ArrayList<>(seen);
    }

    /**
     * Extract anchor links with resolved absolute URLs and an external flag.
     * Links without an href or with only a fragment/javascript href are skipped.
     */
    private List<ParsedPageDTO.LinkDTO> extractLinks(Document doc, String pageUrl) {
        Elements anchors = doc.select("a[href]");
        String pageHost  = extractHost(pageUrl);

        List<ParsedPageDTO.LinkDTO> links = new ArrayList<>(anchors.size());
        for (Element anchor : anchors) {
            String absHref = anchor.absUrl("href");
            if (absHref.isBlank() || absHref.startsWith("javascript:")) {
                continue;
            }

            String text     = anchor.text().trim();
            String linkHost = extractHost(absHref);
            // A link is external when it has a resolvable host different from the page's host
            boolean external = !linkHost.isEmpty() && !linkHost.equalsIgnoreCase(pageHost);

            links.add(new ParsedPageDTO.LinkDTO(text, absHref, external));
        }
        return links;
    }

    /**
     * Extract non-empty paragraph texts capped at {@value #MAX_PARAGRAPHS}.
     */
    private List<String> extractParagraphs(Document doc) {
        Elements elements = doc.select("p");
        List<String> paragraphs = new ArrayList<>(Math.min(elements.size(), MAX_PARAGRAPHS));
        for (Element el : elements) {
            String text = el.text().trim();
            if (!text.isEmpty()) {
                paragraphs.add(text);
                if (paragraphs.size() >= MAX_PARAGRAPHS) break;
            }
        }
        return paragraphs;
    }

    /**
     * Produce clean body text and truncate to {@value #MAX_CLEAN_TEXT} characters.
     * Called after {@link #stripBoilerplate} so noise tags are already gone.
     */
    private String buildCleanText(Document doc) {
        Element body = doc.body();
        if (body == null) return "";
        String text = body.text().trim();
        if (text.length() > MAX_CLEAN_TEXT) {
            log.debug("Clean text truncated from {} to {} chars", text.length(), MAX_CLEAN_TEXT);
            return text.substring(0, MAX_CLEAN_TEXT);
        }
        return text;
    }

    /**
     * Count whitespace-delimited words in the given string.
     * Returns 0 for blank input rather than 1 (which split would give).
     */
    private int countWords(String text) {
        if (text == null || text.isBlank()) return 0;
        return text.split("\\s+").length;
    }

    /**
     * Extract meta description from {@code <meta name="description">} or
     * the Open Graph fallback {@code <meta property="og:description">}.
     */
    private String extractMetaDescription(Document doc) {
        // Prefer standard description
        Element meta = doc.selectFirst("meta[name=description]");
        if (meta != null) {
            String content = meta.attr("content").trim();
            if (!content.isEmpty()) return content;
        }

        // OG fallback
        Element ogMeta = doc.selectFirst("meta[property=og:description]");
        if (ogMeta != null) {
            String content = ogMeta.attr("content").trim();
            if (!content.isEmpty()) return content;
        }

        return null;
    }

    /**
     * Safely extract the host portion of a URL string.
     * Returns an empty string if the URL cannot be parsed.
     */
    private String extractHost(String url) {
        if (url == null || url.isBlank()) return "";
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            return host != null ? host.toLowerCase() : "";
        } catch (URISyntaxException e) {
            log.debug("Could not parse host from URL '{}': {}", url, e.getMessage());
            return "";
        }
    }

    /**
     * Minimal HTML escaping for synthetic document construction.
     * Only {@code <}, {@code >}, and {@code &} need to be escaped in a text node context.
     */
    private String escapeHtml(String input) {
        if (input == null) return "";
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
