package com.lockin.tabstream.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA entity representing a saved page scrape result.
 * Lists (headings, links, paragraphs) are stored as JSON strings to avoid
 * additional join tables while keeping the schema simple for this use case.
 */
@Entity
@Table(
    name = "page_snapshots",
    indexes = {
        @Index(name = "idx_page_snapshots_captured_at", columnList = "capturedAt"),
        @Index(name = "idx_page_snapshots_url",         columnList = "url")
    }
)
public class PageSnapshot {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(length = 2048)
    private String title;

    @Column(length = 4096)
    private String url;

    /** Full raw HTML of the page — can be very large, stored as TEXT. */
    @Column(columnDefinition = "TEXT")
    private String rawHtml;

    /** Cleaned body text after stripping boilerplate tags. */
    @Column(columnDefinition = "TEXT")
    private String cleanText;

    /**
     * JSON array string of heading texts, e.g. ["Heading 1","Heading 2"].
     * Serialised/deserialised by StorageService using ObjectMapper.
     */
    @Column(columnDefinition = "TEXT")
    private String headingsJson;

    /**
     * JSON array string of LinkDTO objects.
     * Serialised/deserialised by StorageService using ObjectMapper.
     */
    @Column(columnDefinition = "TEXT")
    private String linksJson;

    /**
     * JSON array string of paragraph texts.
     * Serialised/deserialised by StorageService using ObjectMapper.
     */
    @Column(columnDefinition = "TEXT")
    private String paragraphsJson;

    private int wordCount;

    private int charCount;

    @Column(length = 1024)
    private String metaDescription;

    /** "rest" or "websocket" */
    @Column(length = 50)
    private String processingMode;

    /** UTC instant when this snapshot was captured. */
    private Instant capturedAt;

    // -------------------------------------------------------------------------
    // No-arg constructor required by JPA
    // -------------------------------------------------------------------------

    public PageSnapshot() {}

    // -------------------------------------------------------------------------
    // Getters & Setters
    // -------------------------------------------------------------------------

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

    public String getRawHtml() {
        return rawHtml;
    }

    public void setRawHtml(String rawHtml) {
        this.rawHtml = rawHtml;
    }

    public String getCleanText() {
        return cleanText;
    }

    public void setCleanText(String cleanText) {
        this.cleanText = cleanText;
    }

    public String getHeadingsJson() {
        return headingsJson;
    }

    public void setHeadingsJson(String headingsJson) {
        this.headingsJson = headingsJson;
    }

    public String getLinksJson() {
        return linksJson;
    }

    public void setLinksJson(String linksJson) {
        this.linksJson = linksJson;
    }

    public String getParagraphsJson() {
        return paragraphsJson;
    }

    public void setParagraphsJson(String paragraphsJson) {
        this.paragraphsJson = paragraphsJson;
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

    public String getMetaDescription() {
        return metaDescription;
    }

    public void setMetaDescription(String metaDescription) {
        this.metaDescription = metaDescription;
    }

    public String getProcessingMode() {
        return processingMode;
    }

    public void setProcessingMode(String processingMode) {
        this.processingMode = processingMode;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(Instant capturedAt) {
        this.capturedAt = capturedAt;
    }

    @Override
    public String toString() {
        return "PageSnapshot{" +
                "id='" + id + '\'' +
                ", title='" + title + '\'' +
                ", url='" + url + '\'' +
                ", wordCount=" + wordCount +
                ", processingMode='" + processingMode + '\'' +
                ", capturedAt=" + capturedAt +
                '}';
    }
}
