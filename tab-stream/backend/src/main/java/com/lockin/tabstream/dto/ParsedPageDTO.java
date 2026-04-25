package com.lockin.tabstream.dto;

import java.util.List;

/**
 * DTO representing the fully parsed and analysed result of a page scrape.
 * Returned by both the REST /api/scrape endpoint and the WebSocket ANALYSIS frame.
 */
public class ParsedPageDTO {

    private String id;
    private String title;
    private String url;
    private String timestamp;
    private List<String> headings;
    private List<LinkDTO> links;
    private List<String> paragraphs;
    private String cleanText;
    private int wordCount;
    private int charCount;
    private String metaDescription;
    /** Either "rest" or "websocket" — indicates how this record was created. */
    private String processingMode;

    // -------------------------------------------------------------------------
    // Nested static LinkDTO
    // -------------------------------------------------------------------------

    /**
     * Represents a single anchor element extracted from the page.
     */
    public static class LinkDTO {

        private String text;
        private String href;
        /** True when the link points to a different host than the page's own URL. */
        private boolean external;

        public LinkDTO() {}

        public LinkDTO(String text, String href, boolean external) {
            this.text = text;
            this.href = href;
            this.external = external;
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

        public boolean isExternal() {
            return external;
        }

        public void setExternal(boolean external) {
            this.external = external;
        }

        @Override
        public String toString() {
            return "LinkDTO{text='" + text + "', href='" + href + "', external=" + external + '}';
        }
    }

    // -------------------------------------------------------------------------
    // No-arg constructor required by Jackson
    // -------------------------------------------------------------------------

    public ParsedPageDTO() {}

    public ParsedPageDTO(String id, String title, String url, String timestamp,
                         List<String> headings, List<LinkDTO> links, List<String> paragraphs,
                         String cleanText, int wordCount, int charCount,
                         String metaDescription, String processingMode) {
        this.id = id;
        this.title = title;
        this.url = url;
        this.timestamp = timestamp;
        this.headings = headings;
        this.links = links;
        this.paragraphs = paragraphs;
        this.cleanText = cleanText;
        this.wordCount = wordCount;
        this.charCount = charCount;
        this.metaDescription = metaDescription;
        this.processingMode = processingMode;
    }

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

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public List<String> getHeadings() {
        return headings;
    }

    public void setHeadings(List<String> headings) {
        this.headings = headings;
    }

    public List<LinkDTO> getLinks() {
        return links;
    }

    public void setLinks(List<LinkDTO> links) {
        this.links = links;
    }

    public List<String> getParagraphs() {
        return paragraphs;
    }

    public void setParagraphs(List<String> paragraphs) {
        this.paragraphs = paragraphs;
    }

    public String getCleanText() {
        return cleanText;
    }

    public void setCleanText(String cleanText) {
        this.cleanText = cleanText;
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

    @Override
    public String toString() {
        return "ParsedPageDTO{" +
                "id='" + id + '\'' +
                ", title='" + title + '\'' +
                ", url='" + url + '\'' +
                ", wordCount=" + wordCount +
                ", processingMode='" + processingMode + '\'' +
                '}';
    }
}
