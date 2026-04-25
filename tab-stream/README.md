# Tab Stream

A production-ready system for capturing and streaming active browser tab content to a Java backend for real-time processing and analysis.

---

## Architecture

```
[Browser Tab]
     │
     │  chrome.scripting.executeScript
     ▼
[Chrome Extension — background.js (MV3 Service Worker)]
     │
     ├──── REST POST ──────────────────────────────────────────────────────────┐
     │     http://localhost:8081/api/scrape                                    │
     │                                                                         │
     └──── WebSocket ──────────────────────────────────────────────────────────┤
           ws://localhost:8081/ws/stream                                       │
                                                                               ▼
                                              [Spring Boot Backend — port 8081]
                                                       │
                                              ┌────────┴────────┐
                                              │                 │
                                         JSoupPageParser   StorageService
                                              │                 │
                                         ParsedPageDTO    H2 File DB
                                              │           (page_snapshots)
                                              └────────┬────────┘
                                                       │
                                                PageProcessingService
```

### Two Streaming Modes

| Mode | Transport | Use case |
|---|---|---|
| **REST** | HTTP POST | Simple one-shot capture, reliable delivery |
| **WebSocket** | Persistent WS | Real-time streaming, low-latency analysis |

### Two Trigger Modes

| Mode | Mechanism | Use case |
|---|---|---|
| **Send Once** | Button click | Manual, on-demand capture |
| **Live Stream** | Interval or MutationObserver | Continuous monitoring |

---

## Project Structure

```
tab-stream/
├── extension/                        Chrome Extension (MV3)
│   ├── manifest.json
│   ├── popup.html                    Dark-themed control panel
│   ├── popup.js                      UI logic, settings persistence
│   └── background.js                 Service worker — scrapes, streams, reconnects
│
├── backend/                          Spring Boot 3.3.5 / Java 21
│   ├── build.gradle
│   ├── settings.gradle
│   └── src/main/java/com/lockin/tabstream/
│       ├── TabStreamApplication.java
│       ├── config/
│       │   ├── CorsConfig.java           Global CORS — allows chrome-extension:// origin
│       │   └── WebSocketConfig.java      WS handler registration + 30s PING scheduler
│       ├── controller/
│       │   └── ScrapeController.java     REST endpoints
│       ├── dto/
│       │   ├── ApiResponse.java          Generic {ok, data, error, timestamp} envelope
│       │   ├── PagePayloadDTO.java        Validated incoming payload
│       │   ├── ParsedPageDTO.java         Full analysis result
│       │   └── PageSnapshotSummaryDTO.java Lightweight list-view DTO
│       ├── exception/
│       │   └── GlobalExceptionHandler.java 400 / 413 / 429 / 500 handlers
│       ├── model/
│       │   └── PageSnapshot.java          JPA entity (H2 file-based)
│       ├── parser/
│       │   └── JSoupPageParser.java       Headings, links, paragraphs, clean text
│       ├── repository/
│       │   └── PageSnapshotRepository.java
│       ├── security/
│       │   └── RateLimitFilter.java       Per-IP sliding-window rate limiter
│       ├── service/
│       │   ├── PageProcessingService.java  Orchestration layer
│       │   └── StorageService.java         @Transactional persistence
│       └── websocket/
│           └── PageStreamHandler.java      WS session manager + ANALYSIS responder
│
└── README.md
```

---

## REST API

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/scrape` | Receive page payload, analyze, store, return result |
| `GET` | `/api/history` | List all captured pages (newest first) |
| `GET` | `/api/history/{id}` | Full analysis for a single capture |
| `DELETE` | `/api/history` | Wipe all stored captures |
| `GET` | `/api/health` | Liveness + stored count |

### POST `/api/scrape` — Request body

```json
{
  "title":     "Page title",
  "url":       "https://example.com",
  "html":      "<!DOCTYPE html>...",
  "text":      "Visible body text...",
  "timestamp": "2024-11-01T14:22:05.000Z"
}
```

### POST `/api/scrape` — Response

```json
{
  "ok": true,
  "data": {
    "id":              "3f2a1b4c-...",
    "title":           "Example Domain",
    "url":             "https://example.com",
    "timestamp":       "2024-11-01T14:22:05Z",
    "headings":        ["Example Domain"],
    "links":           [{ "text": "More info", "href": "https://iana.org/", "external": true }],
    "paragraphs":      ["This domain is for use in illustrative examples..."],
    "cleanText":       "Example Domain This domain is for use...",
    "wordCount":       28,
    "charCount":       182,
    "metaDescription": "",
    "processingMode":  "rest"
  },
  "error":     null,
  "timestamp": "2024-11-01T14:22:05.123Z"
}
```

---

## WebSocket Protocol

Connect to: `ws://localhost:8081/ws/stream`

### Client → Server

```json
{
  "type":    "PAGE_DATA",
  "payload": {
    "title":     "...",
    "url":       "...",
    "html":      "...",
    "text":      "...",
    "timestamp": "..."
  }
}
```

### Server → Client (analysis result)

```json
{
  "type":      "ANALYSIS",
  "payload":   { ...ParsedPageDTO fields... },
  "timestamp": "2024-11-01T14:22:05Z"
}
```

### Server → Client (heartbeat, every 30s)

```json
{ "type": "PING", "timestamp": "2024-11-01T14:22:05Z" }
```

### Server → Client (error)

```json
{ "type": "ERROR", "message": "...", "timestamp": "..." }
```

---

## What JSoup Extracts

When `html` is provided in the payload, `JSoupPageParser` runs a full parse:

| Field | How it's extracted |
|---|---|
| `headings` | `h1–h6` text, deduplicated, max 50 |
| `links` | `a[href]` → `{text, absUrl, isExternal}`, max 200 |
| `paragraphs` | `p` element text, non-empty, max 100 |
| `cleanText` | `body.text()` after stripping scripts/styles/nav/footer, truncated at 50 000 chars |
| `wordCount` | `cleanText.split("\\s+").length` |
| `metaDescription` | `<meta name="description">` or `og:description` |

---

## Quick Start

### 1 — Start the backend

**Prerequisites:** JDK 21+, or use the bundled Gradle wrapper

```bash
cd tab-stream/backend
./gradlew bootRun
```

Server starts on **port 8081** in ~1 second.

Verify:
```bash
curl http://localhost:8081/api/health
# {"ok":true,"data":{"service":"tab-stream","stored":0,...}}
```

### 2 — Load the Chrome extension

1. Open **`chrome://extensions`** (or **`arc://extensions`** in Arc)
2. Enable **Developer mode** (top-right toggle)
3. Click **Load unpacked** → select the `tab-stream/extension/` folder
4. Pin **"Tab Stream"** to the toolbar

### 3 — Send Once (REST)

1. Navigate to any `http://` or `https://` page
2. Click the **Tab Stream** icon → the status dot should turn **green**
3. Make sure **REST** pill is selected
4. Click **⚡ Send Once**
5. The status bar shows word count, headings, and link counts

### 4 — Send Once (WebSocket)

1. Click the **WebSocket** pill
2. The WS badge shows **CONNECTING** then **OPEN**
3. Click **⚡ Send Once**
4. Analysis streams back via the open connection

### 5 — Live Streaming

1. Select a mode (REST or WebSocket)
2. Choose an interval (3s / 5s / 10s / 30s / 1 min)
3. Optionally check **"Use MutationObserver"** — sends only when DOM actually changes
4. Click **▶ Start Streaming**
5. The popup can be closed — streaming continues via `chrome.alarms`
6. Click **⏹ Stop Streaming** to halt

### 6 — Browse stored captures

```bash
# All captures (newest first)
curl http://localhost:8081/api/history | python3 -m json.tool

# Single capture
curl http://localhost:8081/api/history/<uuid>

# Clear everything
curl -X DELETE http://localhost:8081/api/history
```

Or click **📋 View History** in the extension popup — opens the JSON directly in a new tab.

### 7 — H2 Console (inspect the database)

While the backend is running, open:

```
http://localhost:8081/h2-console
```

JDBC URL: `jdbc:h2:file:./data/tabstream`  
Username: `sa`  Password: *(empty)*

---

## Security

### Rate Limiting

Every `/api/**` request is checked by `RateLimitFilter` before reaching any controller.

| Property | Default | Override in `application.properties` |
|---|---|---|
| Max requests | 30 | `scraper.rate-limit.max-requests=N` |
| Time window | 60 s | `scraper.rate-limit.window-seconds=N` |

Excess requests return **HTTP 429**:
```json
{ "ok": false, "error": "Rate limit exceeded. Try again later." }
```

### Payload Size Limits

| Field | Limit |
|---|---|
| `html` | 10 MB |
| `text` | 2 MB |
| Total request | 15 MB (`spring.servlet.multipart.max-request-size`) |

Oversized payloads return **HTTP 413**.

### CORS

All origins are allowed during development (`allowedOriginPatterns("*")`) so the `chrome-extension://` scheme is accepted. Tighten this for any public deployment.

---

## Extension UI Reference

```
┌─────────────────────────────────────────┐
│ ● Tab Stream                     v1.0.0 │
├─────────────────────────────────────────┤
│  [ REST ]  [ WebSocket ]                │
│                                         │
│  ⚡ Send Once                           │
├─────────────────────────────────────────┤
│  Live Stream                            │
│  Interval: [ 5s ▾ ]                     │
│  ☑ Use MutationObserver                 │
│  [ ▶ Start Streaming ]                  │
│  WS: ●OPEN                             │  ← visible in WS mode only
├─────────────────────────────────────────┤
│  ✓ Sent · 842 words · 7 headings · 34  │  ← status bar
│  Stored: 12                             │
├─────────────────────────────────────────┤
│                    📋 View History      │
└─────────────────────────────────────────┘
```

---

## MutationObserver Mode

When **"Use MutationObserver"** is checked, the extension injects an observer into the page that watches `document.body` for:

- Added / removed nodes
- Attribute changes (excluding `<input>` to avoid noise from typing)
- Character data changes

Changes are **debounced 600 ms** — only one scrape fires per burst of mutations. This means page data is only sent when the content actually changed, making it far more efficient than a fixed interval for dynamic SPAs.

---

## Configuration Reference

### `application.properties`

```properties
server.port=8081
spring.application.name=tab-stream

# H2 file database
spring.datasource.url=jdbc:h2:file:./data/tabstream;AUTO_SERVER=TRUE

# Rate limiting
scraper.rate-limit.max-requests=30
scraper.rate-limit.window-seconds=60

# Multipart limits
spring.servlet.multipart.max-file-size=15MB
spring.servlet.multipart.max-request-size=15MB
```

---

## Extending the System

| Feature | Where to add |
|---|---|
| AI summarisation | `PageProcessingService.process()` — call your LLM API after parsing |
| Keyword extraction | `JSoupPageParser` — add NLP step on `cleanText` |
| PostgreSQL persistence | Replace H2 dependency, update datasource URL |
| Auth / API keys | Add `OncePerRequestFilter` checking `Authorization` header |
| Server-sent events | Add `SseEmitter`-based endpoint alongside WebSocket |
| Export to NDJSON | Add `GET /api/history/export` streaming `StreamingResponseBody` |

---

## Comparison with `tab-scraper-java`

| Feature | `tab-scraper-java` | `tab-stream` |
|---|---|---|
| YouTube tile scraping | ✅ | ❌ (general purpose) |
| WebSocket streaming | ❌ | ✅ |
| MutationObserver | ❌ | ✅ |
| Rate limiting | ❌ | ✅ |
| Input validation (DTOs) | ❌ | ✅ |
| Global error handler | ❌ | ✅ |
| NDJSON file output | ✅ | ❌ |
| H2 persistence | ✅ | ✅ |

Both systems can run simultaneously — they use different ports (8080 vs 8081).