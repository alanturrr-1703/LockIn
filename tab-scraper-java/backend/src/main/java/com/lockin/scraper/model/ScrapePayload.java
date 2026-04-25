package com.lockin.scraper.model;

/**
 * Raw payload posted by the Chrome extension.
 * Fields match exactly what popup.js / background.js sends.
 */
public class ScrapePayload {

    private String title;
    private String url;
    private String content;   // document.body.innerText
    private String html;      // optional: document.documentElement.outerHTML

    public ScrapePayload() {}

    public ScrapePayload(String title, String url, String content, String html) {
        this.title   = title;
        this.url     = url;
        this.content = content;
        this.html    = html;
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public String getTitle()   { return title;   }
    public String getUrl()     { return url;     }
    public String getContent() { return content; }
    public String getHtml()    { return html;    }

    // ── Setters ──────────────────────────────────────────────────────────────

    public void setTitle(String title)     { this.title   = title;   }
    public void setUrl(String url)         { this.url     = url;     }
    public void setContent(String content) { this.content = content; }
    public void setHtml(String html)       { this.html    = html;    }

    @Override
    public String toString() {
        return "ScrapePayload{" +
               "title='" + title + '\'' +
               ", url='" + url + '\'' +
               ", contentLength=" + (content != null ? content.length() : 0) +
               ", htmlLength="    + (html    != null ? html.length()    : 0) +
               '}';
    }
}
