package com.lockin.tabstream.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO representing the raw page data sent by the Chrome extension.
 * Validation constraints mirror the extension's maximum payload sizes.
 */
public class PagePayloadDTO {

    @NotBlank(message = "Title must not be blank")
    @Size(max = 2048, message = "Title must not exceed 2048 characters")
    private String title;

    @NotBlank(message = "URL must not be blank")
    @Size(max = 4096, message = "URL must not exceed 4096 characters")
    private String url;

    /**
     * Raw HTML of the page — optional but large; 10 MB ceiling enforced here
     * (the servlet layer already blocks requests over 15 MB).
     */
    @Size(max = 10_485_760, message = "HTML payload must not exceed 10 MB")
    private String html;

    /**
     * Pre-extracted plain text from the extension — optional; 2 MB ceiling.
     */
    @Size(max = 2_097_152, message = "Text payload must not exceed 2 MB")
    private String text;

    @NotBlank(message = "Timestamp must not be blank")
    private String timestamp;

    // -------------------------------------------------------------------------
    // No-arg constructor required by Jackson
    // -------------------------------------------------------------------------

    public PagePayloadDTO() {}

    // -------------------------------------------------------------------------
    // Getters & Setters
    // -------------------------------------------------------------------------

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

    public String getHtml() {
        return html;
    }

    public void setHtml(String html) {
        this.html = html;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "PagePayloadDTO{" +
                "title='" + title + '\'' +
                ", url='" + url + '\'' +
                ", timestamp='" + timestamp + '\'' +
                ", htmlLength=" + (html != null ? html.length() : 0) +
                ", textLength=" + (text != null ? text.length() : 0) +
                '}';
    }
}
