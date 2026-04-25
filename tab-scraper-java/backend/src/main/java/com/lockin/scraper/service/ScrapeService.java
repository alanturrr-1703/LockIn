package com.lockin.scraper.service;

import com.lockin.scraper.model.ScrapePayload;
import com.lockin.scraper.model.ScrapeResult;
import com.lockin.scraper.queue.ScrapeQueue;
import com.lockin.scraper.repository.ScrapeResultRepository;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

@Service
public class ScrapeService {

    private final ScrapeResultRepository repository;
    private final ScrapeQueue scrapeQueue;

    public ScrapeService(
        ScrapeResultRepository repository,
        ScrapeQueue scrapeQueue
    ) {
        this.repository = repository;
        this.scrapeQueue = scrapeQueue;
    }

    /**
     * Process the raw payload from the Chrome extension, enrich it with JSoup,
     * persist it to the H2 file database, and return the saved result.
     */
    public ScrapeResult process(ScrapePayload payload) {
        ScrapeResult result = new ScrapeResult();
        result.setId(UUID.randomUUID().toString());
        result.setTitle(
            payload.getTitle() != null ? payload.getTitle().trim() : ""
        );
        result.setUrl(payload.getUrl() != null ? payload.getUrl().trim() : "");
        result.setRawContent(payload.getContent());
        result.setScrapedAt(Instant.now());

        // ── JSoup processing ──────────────────────────────────────────────────
        String html = payload.getHtml();
        if (html != null && !html.isBlank()) {
            Document doc = Jsoup.parse(
                html,
                payload.getUrl() != null ? payload.getUrl() : ""
            );
            enrichFromDocument(doc, result);
        } else if (
            payload.getContent() != null && !payload.getContent().isBlank()
        ) {
            Document doc = Jsoup.parse(
                "<body><pre>" + payload.getContent() + "</pre></body>"
            );
            enrichFromDocument(doc, result);
        } else {
            result.setCleanedText("");
            result.setWordCount(0);
            result.setCharCount(0);
            result.setHeadings(Collections.emptyList());
            result.setLinks(Collections.emptyList());
            result.setMetaDescription("");
        }

        // ── Persist to H2 file database ───────────────────────────────────────
        ScrapeResult saved = repository.save(result);

        // ── Stream to JSONL file via queue (non-blocking) ─────────────────────
        scrapeQueue.enqueue(saved);

        return saved;
    }

    // ── Query helpers ─────────────────────────────────────────────────────────

    /** Return all stored results, newest-first. */
    public List<ScrapeResult> findAll() {
        return repository.findAllByOrderByScrapedAtDesc();
    }

    /** Return a single result by id. */
    public Optional<ScrapeResult> findById(String id) {
        return repository.findById(id);
    }

    /** Delete a single result by id. Returns true if it existed. */
    public boolean deleteById(String id) {
        if (!repository.existsById(id)) return false;
        repository.deleteById(id);
        return true;
    }

    /** Wipe the entire store. */
    public void deleteAll() {
        repository.deleteAll();
    }

    /** Total number of stored results. */
    public int count() {
        return (int) repository.count();
    }

    /** Check whether a URL has been scraped before. */
    public boolean hasUrl(String url) {
        return repository.existsByUrl(url);
    }

    /** How many times a URL has been scraped. */
    public long countByUrl(String url) {
        return repository.countByUrl(url);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void enrichFromDocument(Document doc, ScrapeResult result) {
        doc
            .select("script, style, noscript, svg, iframe, nav, footer, header")
            .remove();

        String cleaned =
            doc.body() != null
                ? doc.body().text().replaceAll("\\s{2,}", " ").trim()
                : "";
        result.setCleanedText(cleaned);
        result.setCharCount(cleaned.length());
        result.setWordCount(
            cleaned.isBlank() ? 0 : cleaned.split("\\s+").length
        );

        // Meta description
        Element metaEl = doc.selectFirst("meta[name=description]");
        if (metaEl == null) metaEl = doc.selectFirst(
            "meta[property=og:description]"
        );
        result.setMetaDescription(metaEl != null ? metaEl.attr("content") : "");

        // Headings h1–h3 (up to 30, deduplicated)
        List<String> headings = doc
            .select("h1, h2, h3")
            .stream()
            .map(el -> el.text().trim())
            .filter(t -> !t.isBlank())
            .distinct()
            .limit(30)
            .collect(Collectors.toList());
        result.setHeadings(headings);

        // Links — top 50 by appearance order
        List<ScrapeResult.LinkInfo> links = doc
            .select("a[href]")
            .stream()
            .map(el -> {
                String href = el.absUrl("href");
                if (href.isBlank()) href = el.attr("href");
                return new ScrapeResult.LinkInfo(el.text().trim(), href);
            })
            .filter(li -> !li.getHref().isBlank())
            .limit(50)
            .collect(Collectors.toList());
        result.setLinks(links);

        // Fall back to <title> if the extension didn't send one
        if (result.getTitle().isBlank()) {
            result.setTitle(doc.title());
        }
    }
}
