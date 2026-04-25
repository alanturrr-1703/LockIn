package com.lockin.scraper.model;

import com.lockin.scraper.converter.LinkInfoListConverter;
import com.lockin.scraper.converter.StringListConverter;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "scrape_results")
public class ScrapeResult {

    @Id
    private String id;

    @Column(length = 1024)
    private String title;

    @Column(length = 2048)
    private String url;

    @Column(columnDefinition = "TEXT")
    private String rawContent;

    @Column(columnDefinition = "TEXT")
    private String cleanedText;

    private int wordCount;
    private int charCount;

    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<String> headings;

    @Convert(converter = LinkInfoListConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<LinkInfo> links;

    @Column(length = 1024)
    private String metaDescription;

    private Instant scrapedAt;

    public ScrapeResult() {}

    // ── Nested DTO ────────────────────────────────────────────────────────────

    public static class LinkInfo {

        private String text;
        private String href;

        public LinkInfo() {}

        public LinkInfo(String text, String href) {
            this.text = text;
            this.href = href;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public String getHref() {
            return href;
        }

        public void setHref(String href) {
            this.href = href;
        }
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getRawContent() {
        return rawContent;
    }

    public void setRawContent(String rawContent) {
        this.rawContent = rawContent;
    }

    public String getCleanedText() {
        return cleanedText;
    }

    public void setCleanedText(String cleanedText) {
        this.cleanedText = cleanedText;
    }

    public int getWordCount() {
        return wordCount;
    }

    public void setWordCount(int wordCount) {
        this.wordCount = wordCount;
    }

    public int getCharCount() {
        return charCount;
    }

    public void setCharCount(int charCount) {
        this.charCount = charCount;
    }

    public List<String> getHeadings() {
        return headings;
    }

    public void setHeadings(List<String> headings) {
        this.headings = headings;
    }

    public List<LinkInfo> getLinks() {
        return links;
    }

    public void setLinks(List<LinkInfo> links) {
        this.links = links;
    }

    public String getMetaDescription() {
        return metaDescription;
    }

    public void setMetaDescription(String metaDescription) {
        this.metaDescription = metaDescription;
    }

    public Instant getScrapedAt() {
        return scrapedAt;
    }

    public void setScrapedAt(Instant scrapedAt) {
        this.scrapedAt = scrapedAt;
    }
}
