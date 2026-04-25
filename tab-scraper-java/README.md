# Tab Scraper → Java

A full-stack browser scraping system built with a **Chrome Extension (MV3)** frontend
and a **Java Spring Boot** backend — the correct architecture for accessing live browser tabs from Java.

---

## Architecture

```
[Browser Tab]
     │
     │  chrome.scripting.executeScript
     ▼
[Chrome Extension — background.js]
     │
     │  HTTP POST  /scrape  (JSON)
     ▼
[Java Spring Boot — port 8080]
     │
     ├── ScrapeController   (REST API)
     ├── ScrapeService      (business logic)
     │       └── JSoup      (HTML parsing)
     └── In-memory store    (ConcurrentHashMap)
```

**Why not Java alone?**
Java cannot reach into a running browser tab due to sandboxing.
The extension handles that boundary; Java handles everything after.

---

## Project Structure

```
tab-scraper-java/
├── extension/                  Chrome Extension (MV3)
│   ├── manifest.json
│   ├── popup.html              Extension popup UI
│   ├── popup.js                Popup logic (health check, result card)
│   └── background.js          Service worker — scrapes tab, POSTs to Java
│
├── backend/                    Gradle / Spring Boot project
│   ├── build.gradle
│   ├── settings.gradle
│   └── src/main/java/com/lockin/scraper/
│       ├── ScraperApplication.java
│       ├── config/
│       │   └── CorsConfig.java          Global CORS (allows chrome-extension:// origin)
│       ├── controller/
│       │   └── ScrapeController.java    REST endpoints
│       ├── model/
│       │   ├── ScrapePayload.java       Raw POST body from extension
│       │   └── ScrapeResult.java        Processed + stored result
│       └── service/
│           └── ScrapeService.java       JSoup processing + in-memory store
│
└── README.md
```

---

## REST API

| Method   | Path              | Description                                    |
|----------|-------------------|------------------------------------------------|
| `GET`    | `/health`         | Liveness check — returns stored result count   |
| `POST`   | `/scrape`         | Receive payload from extension, process, store |
| `GET`    | `/results`        | List all stored results (newest first)         |
| `GET`    | `/results/{id}`   | Fetch a single result by UUID                  |
| `DELETE` | `/results/{id}`   | Delete a single result                         |
| `DELETE` | `/results`        | Wipe the entire in-memory store                |

### POST `/scrape` — request body

```json
{
  "title":   "Page title",
  "url":     "https://example.com",
  "content": "Visible text from document.body.innerText …",
  "html":    "<!DOCTYPE html>…"
}
```

`html` is optional but strongly recommended — JSoup extracts headings, links, and
meta descriptions from it.

### POST `/scrape` — response

```json
{
  "ok":        true,
  "id":        "3f2a1b4c-…",
  "wordCount": 842,
  "charCount": 5301,
  "headings":  7,
  "links":     34,
  "scrapedAt": "2024-11-01T14:22:05.123Z"
}
```

---

## Quick Start

### 1 — Start the Java backend

**Prerequisites:** JDK 21+, Gradle 7+ (or use the wrapper once generated)

```bash
cd tab-scraper-java/backend

# First run — download dependencies and start
./gradlew bootRun
```

> On Windows use `gradlew.bat bootRun`

You should see:
```
Started ScraperApplication on port 8080
```

Verify it's up:
```bash
curl http://localhost:8080/health
# {"ok":true,"service":"tab-scraper-java","stored":0,"timestamp":"…"}
```

### 2 — Load the Chrome Extension

1. Open **chrome://extensions**
2. Toggle **Developer mode** on (top-right)
3. Click **Load unpacked** → select the `tab-scraper-java/extension/` folder
4. Pin **"Tab Scraper → Java"** to the toolbar

### 3 — Scrape a tab

1. Navigate to any `http://` or `https://` page
2. Click the **Tab Scraper → Java** extension icon
3. The status dot turns **green** when the backend is reachable
4. Click **⚡ Scrape Tab**
5. The popup shows word count, link count, and extracted headings
6. The backend logs the result and stores it in memory

### 4 — Browse stored results

```bash
# All results
curl http://localhost:8080/results | python3 -m json.tool

# Single result (replace UUID)
curl http://localhost:8080/results/3f2a1b4c-…

# Delete everything
curl -X DELETE http://localhost:8080/results
```

Or click **📋 View All** in the popup to open `/results` directly in a new tab.

---

## What JSoup Extracts

When `html` is included in the POST body, `ScrapeService` uses
[JSoup](https://jsoup.org/) to parse and enrich the raw payload:

| Field             | How it's extracted                                      |
|-------------------|---------------------------------------------------------|
| `cleanedText`     | `body.text()` after stripping scripts/styles/nav/footer |
| `wordCount`       | `cleanedText.split("\\s+")`                            |
| `charCount`       | `cleanedText.length()`                                  |
| `headings`        | `h1, h2, h3` — up to 30, deduplicated                  |
| `links`           | `a[href]` — top 50, with text + absolute URL            |
| `metaDescription` | `<meta name="description">` or `og:description`        |

---

## Extension Options

| Toggle        | Default | Effect                                                   |
|---------------|---------|----------------------------------------------------------|
| **Send HTML** | ✅ on   | Includes `document.documentElement.outerHTML` in payload |
| **Send Text** | ✅ on   | Includes `document.body.innerText` in payload            |

Disabling **Send HTML** reduces payload size but limits JSoup's analysis to
plain text only.

---

## Extending the Backend

Some ideas for next steps:

- **Persist to a database** — swap the `LinkedHashMap` in `ScrapeService` for a
  Spring Data JPA repository (H2 for local, PostgreSQL for production)
- **NLP / keyword extraction** — pipe `cleanedText` through Apache OpenNLP or
  Stanford NLP
- **Duplicate detection** — hash the URL or content fingerprint before storing
- **Scheduled re-scrape** — use `@Scheduled` + Selenium/Playwright headless for
  pages that require JavaScript rendering
- **WebSocket push** — stream new results to a dashboard in real time

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| Status dot stays amber | Backend not running — `./gradlew bootRun` |
| `Cannot scrape this page` | Extension cannot inject into `chrome://` or `edge://` pages — use a normal website |
| `Script injection failed` | Reload the tab and try again; some CSP-heavy pages block injection |
| `Backend returned 400` | Payload was empty — make sure the page is fully loaded before scraping |
| Port conflict | Change `server.port` in `application.properties` and update the endpoint in the popup |

---

## Comparison with Alternatives

| Approach | Can access open tab? | Production-ready? |
|----------|---------------------|-------------------|
| **Extension + Java (this project)** | ✅ Yes | ✅ Yes |
| Selenium (Java) | ❌ Opens new tab | ⚠️  Dev/test only |
| Chrome DevTools Protocol (Java) | ⚠️  Hacky | ❌ Fragile |
| Python `requests` / `httpx` | ❌ No live tab | ✅ For static pages |