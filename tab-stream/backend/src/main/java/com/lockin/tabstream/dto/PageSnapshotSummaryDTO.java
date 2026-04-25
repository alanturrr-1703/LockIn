package com.lockin.tabstream.dto;

/**
 * Lightweight summary DTO returned by GET /api/history.
 * Avoids loading full HTML/text blobs from the database for list views.
 */
public class PageSnapshotSummaryDTO {

    private String id;
    private String title;
    private String url;
    private int wordCount;
    private int headingCount;
    private int linkCount;
    private String capturedAt;
    private String processingMode;

    // -------------------------------------------------------------------------
    // No-arg constructor required by Jackson
    // -------------------------------------------------------------------------

    public PageSnapshotSummaryDTO() {}

    public PageSnapshotSummaryDTO(String id, String title, String url,
                                   int wordCount, int headingCount, int linkCount,
                                   String capturedAt, String processingMode) {
        this.id = id;
        this.title = title;
        this.url = url;
        this.wordCount = wordCount;
        this.headingCount = headingCount;
        this.linkCount = linkCount;
        this.capturedAt = capturedAt;
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

    public int getWordCount() {
        return wordCount;
    }

    public void setWordCount(int wordCount) {
        this.wordCount = wordCount;
    }

    public int getHeadingCount() {
        return headingCount;
    }

    public void setHeadingCount(int headingCount) {
        this.headingCount = headingCount;
    }

    public int getLinkCount() {
        return linkCount;
    }

    public void setLinkCount(int linkCount) {
        this.linkCount = linkCount;
    }

    public String getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(String capturedAt) {
        this.capturedAt = capturedAt;
    }

    public String getProcessingMode() {
        return processingMode;
    }

    public void setProcessingMode(String processingMode) {
        this.processingMode = processingMode;
    }

    @Override
    public String toString() {
        return "PageSnapshotSummaryDTO{" +
                "id='" + id + '\'' +
                ", title='" + title + '\'' +
                ", url='" + url + '\'' +
                ", wordCount=" + wordCount +
                ", headingCount=" + headingCount +
                ", linkCount=" + linkCount +
                ", capturedAt='" + capturedAt + '\'' +
                ", processingMode='" + processingMode + '\'' +
                '}';
    }
}
