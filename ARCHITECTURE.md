# LockIn — Architecture

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Repository Layout](#2-repository-layout)
3. [High-Level Architecture](#3-high-level-architecture)
4. [Data Flow](#4-data-flow)
5. [Chrome Extension](#5-chrome-extension)
   - 5.1 [manifest.json](#51-manifestjson)
   - 5.2 [background.js (Service Worker)](#52-backgroundjs-service-worker)
   - 5.3 [popup.html / popup.js](#53-popuphtml--popupjs)
   - 5.4 [options.html / options.js](#54-optionshtml--optionsjs)
6. [Java Backend](#6-java-backend)
   - 6.1 [TileReceiver](#61-tilereceiver)
   - 6.2 [VideoEnricher](#62-videoenricher)
   - 6.3 [YouTubeMonitor (Selenium)](#63-youtubemonitor-selenium)
   - 6.4 [JsonLogger](#64-jsonlogger)
   - 6.5 [Data Models](#65-data-models)
7. [Storage & Persistence](#7-storage--persistence)
8. [Classification Pipeline](#8-classification-pipeline)
9. [Overlay & Blocking Mechanism](#9-overlay--blocking-mechanism)
10. [Build System](#10-build-system)
11. [Key Design Decisions](#11-key-design-decisions)
12. [Component Interaction Diagram](#12-component-interaction-diagram)

---

## 1. Project Overview

**LockIn** is a YouTube content-filtering system. It intercepts the video tiles rendered on YouTube pages, classifies them against a user-defined interest profile, and visually blocks irrelevant content before the user can see it. Classification happens locally (heuristic keyword matching) and optionally via a cloud LLM (Google Gemini). An optional Java backend provides durable archival of every scraped tile.

The system has **two independent runtimes** that can work together or separately:

| Runtime | Technology | Purpose |
|---|---|---|
| Chrome Extension | JavaScript / Manifest V3 | Scrape, classify, and block YouTube tiles live in the browser |
| Java Backend | Java 24 + Gradle | Receive tiles from the extension, enrich them, and persist to disk |

There is also a **legacy Selenium monitor** (`YouTubeMonitor`) that predates the extension and operates a real Chrome window under programmatic control — it still ships in the codebase as an alternative approach.

---

## 2. Repository Layout

```
LockIn/
└── tab-scrapper/
    ├── build.gradle              # Gradle build — dependencies, tasks
    ├── settings.gradle
    ├── gradlew / gradlew.bat     # Gradle wrapper scripts
    │
    ├── extension/                # Chrome Extension (MV3)
    │   ├── manifest.json         # Permissions, entry points, host rules
    │   ├── background.js         # Service worker — core logic
    │   ├── popup.html            # Toolbar popup markup
    │   ├── popup.js              # Popup controller
    │   ├── options.html          # Full settings page markup
    │   └── options.js            # Settings page controller
    │
    ├── src/main/java/com/youtubewatch/
    │   ├── TileReceiver.java     # HTTP server — receives extension POSTs
    │   ├── VideoEnricher.java    # Async metadata enrichment via HTTP
    │   ├── YouTubeMonitor.java   # Selenium-based monitor (legacy path)
    │   ├── JsonLogger.java       # Writes VideoMetadata snapshots to disk
    │   ├── TileRecord.java       # Data model — one scraped tile
    │   └── VideoMetadata.java    # Data model — one video visit (Selenium path)
    │
    └── logs/                     # Auto-created at runtime; JSON output files
```

---

## 3. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Chrome Browser                               │
│                                                                     │
│   ┌──────────────────────────────────────────────────────────────┐  │
│   │              YouTube Tab  (youtube.com)                      │  │
│   │                                                              │  │
│   │   DOM: ytd-rich-item-renderer, ytd-compact-video-renderer   │  │
│   │        ↑ scrapeFn() injected & reads tiles                  │  │
│   │        ↓ applyOverlayFn() injected & blocks tiles           │  │
│   └────────────────────┬───────────────────────────────────────┘  │
│                        │ chrome.scripting.executeScript            │
│   ┌────────────────────▼───────────────────────────────────────┐   │
│   │           background.js  (Service Worker)                  │   │
│   │                                                            │   │
│   │  ┌─────────────┐  ┌──────────────────┐  ┌──────────────┐  │   │
│   │  │ scrapeAndSend│  │classifyTilesLocally│  │applyOverlay │  │   │
│   │  └──────┬──────┘  └────────┬─────────┘  └──────┬───────┘  │   │
│   │         │                  │                    │           │   │
│   │         │           ┌──────▼──────┐             │           │   │
│   │         │           │Gemini API   │ (optional)  │           │   │
│   │         │           │(cloud LLM)  │             │           │   │
│   │         │           └─────────────┘             │           │   │
│   └─────────┼──────────────────────────────────────┼───────────┘   │
│             │ POST /tiles (optional)                │               │
│   ┌─────────▼──────────────────────────────────────┘               │
│   │  popup.js / options.js  (UI layer)                             │
│   └─────────────────────────────────────────────────────────────┘  │
└─────────────────────────┬───────────────────────────────────────────┘
                          │ HTTP POST  http://127.0.0.1:8765/tiles
                          │ (optional — archival only)
┌─────────────────────────▼───────────────────────────────────────────┐
│                    Java Backend (optional)                           │
│                                                                     │
│  ┌──────────────┐     ┌─────────────────┐     ┌──────────────────┐ │
│  │ TileReceiver │────▶│  VideoEnricher  │────▶│  logs/*.json     │ │
│  │ :8765/tiles  │     │ (async HTTP)    │     │  (persisted)     │ │
│  └──────────────┘     └─────────────────┘     └──────────────────┘ │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  YouTubeMonitor  (separate Selenium path — independent)      │  │
│  │  Opens real Chrome ▶ scrapes title/tags ▶ logs + blackout   │  │
│  └──────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 4. Data Flow

### 4.1 Primary Flow — Extension (scrape → classify → block → archive)

```
1. TRIGGER
   User opens YouTube  ──OR──  alarm fires (Watch mode)
          │
          ▼
2. SCRAPE  (background.js → scrapeFn injected into tab)
   Walk YouTube DOM for tile elements
   Extract: video_id, url, title, channel, description, thumbnail_url,
            tile_type, scraped_at, page_url
   Return array of tile objects to the service worker
          │
          ▼
3. CLASSIFY  (background.js → classifyTilesLocally)
   ┌─────────────────────────────────────────────────────┐
   │  Per-tile cache check (chrome.storage.local)        │
   │    HIT  → use cached verdict (TTL 30 min)           │
   │    MISS → classify                                  │
   │                                                     │
   │  Backoff guard — if last API call failed, skip LLM  │
   │                                                     │
   │  Heuristic pass (always runs first):                │
   │    Tokenise title + channel + description           │
   │    Match against active profile tags (keyword set)  │
   │    Fast, zero-latency, no network                   │
   │                                                     │
   │  LLM pass (if model enabled & API key present):     │
   │    Chunk tiles → callChatCompletion (Gemini/OpenAI) │
   │    Parse JSON response → irrelevant[] list          │
   │    Record backoff on failure                        │
   └─────────────────────────────────────────────────────┘
          │
          ▼  blockedItemKeys[]  (normalized URLs or item_key values)
4. BLOCK  (background.js → applyOverlayFn injected into tab)
   For each tile in the DOM:
     If its URL / video_id is in blockedItemKeys:
       Inject a frosted-glass overlay div over the element
          │
          ▼
5. ARCHIVE  (background.js → postTilesToReceiver)
   POST { tiles[], page_url, blocked_item_keys[], blocked_video_ids[] }
   to http://127.0.0.1:8765/tiles
   (Fire-and-forget; extension continues whether or not Java is running)
          │
          ▼
6. ENRICH  (TileReceiver → VideoEnricher)
   For each new (non-duplicate) tile:
     Parallel async HTTP:
       • YouTube oEmbed API   → channel name
       • youtube.com/watch    → full description (char-by-char JSON parse)
   Merge enriched data onto the tile ObjectNode
          │
          ▼
7. PERSIST  (TileReceiver → logs/)
   Write logs/tiles_<epoch-ms>.json  (pretty-printed, one file per batch)
```

### 4.2 Secondary Flow — Selenium Monitor (independent)

```
./gradlew run
    │
    ▼
YouTubeMonitor.start()
    │
    ├── WebDriverManager → download matching ChromeDriver
    ├── ChromeDriver.get(startUrl)
    ├── handleConsentScreen() → dismiss GDPR gate if present
    │
    └── watchLoop() [polls every 1 500 ms]
            │
            ├── Detect SPA URL change (window.location.href)
            ├── isVideoUrl()  → only act on /watch?v= pages
            ├── extractVideoId()  → deduplicate on video ID
            ├── scrapeTitle()  → WebDriverWait on <yt-formatted-string>
            ├── scrapeTags()   → <meta name="keywords"> via JS
            ├── evaluateAndApplyBlackout()
            │     ├── ANY forbidden tag matched? → applyBlackout() overlay
            │     └── No match → removeBlackout()
            └── JsonLogger.log(VideoMetadata)  → logs/video_<ts>.json
```

---

## 5. Chrome Extension

### 5.1 `manifest.json`

**Manifest Version:** 3

| Key | Value | Purpose |
|---|---|---|
| `permissions` | `activeTab, scripting, storage, alarms, tabs, permissions` | Core API access |
| `host_permissions` | `*.youtube.com/*, 127.0.0.1:8765/*, localhost:8765/*` | Inject scripts into YT; reach local Java server |
| `optional_host_permissions` | `http://*/, https://*/` | Requested at runtime for custom LLM endpoints |
| `background.service_worker` | `background.js` | The persistent-free MV3 service worker |
| `action.default_popup` | `popup.html` | Toolbar icon click target |
| `options_ui.page` | `options.html` | Full settings page (opened in a tab) |

---

### 5.2 `background.js` (Service Worker)

This is the **brain** of the entire extension. It is event-driven (no persistent state in memory between alarm firings — everything is serialised to `chrome.storage.local`).

#### Key Constants & Defaults

| Symbol | Value | Purpose |
|---|---|---|
| `DEFAULT_ENDPOINT` | `http://127.0.0.1:8765/tiles` | Java receiver URL |
| `DEFAULT_MODE` | `lookahead` | Scrape viewport + 3000 px below fold |
| `DEFAULT_INTERVAL_MIN` | `0.5` | Watch-mode polling cadence |
| `MODEL_CHUNK_SIZE` | `24` | Tiles sent to LLM in one batch |
| `CACHE_KEY` | `classifyCache` | storage key for classification verdicts |
| `BACKOFF_KEY` | `classifyBackoff` | storage key for API backoff state |
| `BACKOFF_DELAYS` | `[60, 120, 300, 600]` seconds | Exponential-like back-off schedule |
| `FAILED_TTL_MS` | `20 * 60 * 1000` | Failed-verdict cache TTL (20 min) |

#### Function Map

```
background.js
│
├── UTILITIES
│   ├── makeId()            — crypto.randomUUID or Date.now fallback
│   ├── uniqStrings()       — dedup + trim an array of strings
│   ├── normalizeTag()      — lowercase + collapse whitespace
│   ├── splitTags()         — split comma/newline separated tag text
│   ├── topicWords()        — tokenise profile description into words
│   └── ensureProfileShape()— guarantee all profile fields are present
│
├── STORAGE
│   ├── loadSettings()      — read all keys from chrome.storage.local,
│   │                         reconstruct active profile + model config
│   ├── loadCache()         — deserialise classification verdict cache
│   ├── saveCache()         — persist verdict cache to storage
│   ├── loadBackoff()       — read API backoff state { until, count }
│   ├── recordBackoff()     — advance backoff on API failure
│   └── clearBackoff()      — reset backoff on API success
│
├── SCRAPING  (runs inside the YouTube tab via executeScript)
│   └── scrapeFn(lookahead)
│       ├── SELECTORS[]     — ordered list of CSS selectors for tile types
│       │                     (ytd-rich-item-renderer, ytd-video-renderer,
│       │                      ytd-compact-video-renderer, ytd-reel-item-renderer,
│       │                      ytd-grid-video-renderer, ytd-playlist-renderer …)
│       ├── TITLE_SEL[]     — CSS selectors tried in order for video title
│       ├── CHANNEL_SEL[]   — CSS selectors tried in order for channel name
│       ├── DESC_SEL[]      — CSS selectors tried for description text
│       ├── passesViewport()— filter by vertical position vs scrape mode
│       ├── normalizeUrl()  — strip tracking params, canonicalise watch URLs
│       ├── itemKeyFrom()   — stable dedup key = normalized URL or href
│       ├── videoIdFromUrl()— parse ?v= or /shorts/ from URL
│       └── firstThumb()    — extract best-quality thumbnail src/srcset
│
├── CLASSIFICATION
│   ├── heuristicIrrelevant(tile, profile)
│   │   — Tokenise title + channel + description
│   │   — Check intersection against profile.tags
│   │   — Returns true if NONE of the profile tags appear (irrelevant)
│   │
│   ├── callChatCompletion(messages, modelSettings)
│   │   — Branches on endpoint URL to select Gemini vs OpenAI wire format
│   │   — Gemini: POST to generateContent, read candidates[0].content.parts
│   │   — OpenAI: POST with Authorization header, read choices[0].message.content
│   │   — Returns raw assistant content string
│   │
│   ├── suggestTags(profile, topic, modelSettings)
│   │   — Constructs a structured prompt asking the LLM for 10–20 tags
│   │   — Parses JSON array from response (with extractJsonObject fallback)
│   │   — Returns { ok, tags[] }
│   │
│   └── classifyTilesLocally(tiles, activeProfile, modelSettings)
│       ├── Phase 1: cache lookup — skip already-decided tiles
│       ├── Phase 2: backoff guard — bail to heuristic if API cooling down
│       ├── Phase 3: heuristic pre-filter on uncached tiles
│       ├── Phase 4: LLM batch classification (chunks of MODEL_CHUNK_SIZE)
│       │           tileSummary() → compact JSON per tile for the prompt
│       │           profilePrompt() → system/user prompt construction
│       │           extractJsonObject() → locate JSON in freeform LLM output
│       ├── Phase 5: persist updated cache
│       └── Returns { blockedItemKeys[], usedModel }
│
├── OVERLAY  (runs inside the YouTube tab via executeScript)
│   └── applyOverlayFn(blockedVideos[], blockedKeys[])
│       ├── normalizeUrl() / videoIdFromUrl() / itemKeyFromUrl()
│       │   — same URL logic as scrapeFn, but in overlay context
│       ├── hostSelectors[]  — same CSS selectors as scrape side
│       ├── makeOverlay()    — create frosted-glass absolute-positioned div
│       └── maybeBlockHost() — walk links in a tile, match against block lists,
│                              attach overlay if matched, track with data-yt-blocked
│
├── RECEIVER BRIDGE
│   └── postTilesToReceiver(tiles, blockedItemKeys, blockedVideoIds, settings)
│       — Fire-and-forget POST to DEFAULT_ENDPOINT
│       — Parses { ok, received, accepted } response
│
├── ORCHESTRATION
│   └── scrapeAndSend()
│       ├── loadSettings()
│       ├── getYouTubeTab()    — find active or first YT tab
│       ├── executeScript(scrapeFn)    → tiles[], counts{}
│       ├── classifyTilesLocally()     → blockedItemKeys[]
│       ├── executeScript(applyOverlayFn) → blockedOnPage count
│       ├── postTilesToReceiver()      → receiver result
│       └── Return unified result object to caller (popup / alarm)
│
└── MESSAGE & ALARM HANDLERS
    ├── chrome.runtime.onMessage
    │   ├── "scrape"        → scrapeAndSend()
    │   ├── "watch-start"   → create chrome.alarms, immediate scrape
    │   ├── "watch-stop"    → clear chrome.alarms
    │   └── "suggest-tags"  → suggestTags() via model
    └── chrome.alarms.onAlarm
        └── ALARM_NAME fires → scrapeAndSend() + update storage
```

---

### 5.3 `popup.html` / `popup.js`

A compact **320 px** dark-themed control panel rendered when the user clicks the toolbar icon.

**UI Elements:**

| Element | Type | Purpose |
|---|---|---|
| `#profileSummary` | `div` | Shows active profile name + tag list |
| `#mode` | `select` | Scrape mode: `lookahead` / `strict` / `all` |
| `#endpoint` | `text input` | Java receiver URL |
| `#topic` | `text input` | Ad-hoc focus topic override |
| `#scrape` | `button` | Trigger one immediate scrape cycle |
| `#watch` | `button` (toggle) | Start / stop Watch mode alarm |
| `#settings` | `button` | Open `options.html` in a new tab |
| `#status` | `div` | Human-readable result of last scrape |

**State:** Reads from `chrome.storage.local` on `DOMContentLoaded`. Writes back on `change` events. All heavy work is delegated to the background service worker via `chrome.runtime.sendMessage`.

---

### 5.4 `options.html` / `options.js`

A full-page settings dashboard with a two-column grid layout.

**Left panel — Model Access:**
- Enable / disable LLM filtering
- Configure Gemini endpoint URL + model name + API key (stored locally, never sent anywhere except the configured endpoint)
- "Allow endpoint" button — requests `chrome.permissions` for the custom origin at runtime
- "Save model" button — persists `modelSettings` to `chrome.storage.local`

**Right panel — Profiles:**
- Create, select, delete, and activate profiles
- Each profile has: `id`, `name`, `description`, `tags[]`, `active`
- "Suggest tags from model" → sends `suggest-tags` message to background worker → calls Gemini → merges returned tags into profile

**Bottom panel — Active Profile Tags:**
- Renders each tag as a removable chip
- Free-text tag input (comma or Enter to add)

**State object (`state`):**
```
{
  profiles: Profile[],
  activeProfileId: string,
  modelSettings: {
    enabled: boolean,
    endpoint: string,
    model: string,
    apiKey: string
  },
  permissionStatus: string
}
```
All mutations call `persistProfiles()` or `persistModelSettings()` which write back to `chrome.storage.local` and re-render the UI.

---

## 6. Java Backend

### 6.1 `TileReceiver`

**Entry point:** `./gradlew runReceiver` (or `java … com.youtubewatch.TileReceiver`)  
**Listens:** `http://127.0.0.1:8765`

#### Endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/tiles` | Accept a tile batch from the extension |
| `GET` | `/health` | Liveness probe + running stats |
| `OPTIONS` | `*` | CORS preflight — responds `204` |
| `GET` | `/` | Alias for `/health` |

#### POST `/tiles` — Request Schema

```json
{
  "tiles": [
    {
      "video_id":      "dQw4w9WgXcQ",
      "title":         "...",
      "channel":       "...",
      "description":   "...",
      "thumbnail_url": "...",
      "url":           "https://www.youtube.com/watch?v=...",
      "tile_type":     "ytd-rich-item-renderer",
      "scraped_at":    "2024-07-15T10:30:00.000Z",
      "page_url":      "https://www.youtube.com/",
      "item_key":      "..."
    }
  ],
  "page_url":          "https://www.youtube.com/",
  "blocked_item_keys": ["...", "..."],
  "blocked_video_ids": ["...", "..."]
}
```

#### POST `/tiles` — Processing Pipeline

```
Request arrives
    │
    ├─ CORS preflight? → 204
    ├─ Not POST?       → 405
    │
    ▼
Read body → Jackson parse → extract tiles[] array
    │
    ▼
For each tile (ObjectNode):
    ├─ Stamp received_at = Instant.now()
    ├─ Compute deduplication key (video_id preferred, URL fallback)
    ├─ seenKeys.contains(key)?
    │     YES → skip (duplicate)
    │     NO  → seenKeys.add(key)  → accepted tile
    │
    ▼  (accepted tiles only)
needsEnrichment(tile)?  — channel or description blank?
    YES → enricher.enrichAsync(video_id)
              .thenAccept(data → applyEnrichment(tile, data))
    NO  → use tile as-is
    │
    ▼
saveBatch(acceptedTiles, pageUrl, receivedAt)
    → logs/tiles_<epoch-ms>.json  (pretty JSON)
    │
    ▼
Response: { "ok": true, "received": N, "accepted": M }
```

#### Thread Model

- **4-thread fixed pool** (`tile-receiver-worker`) handles concurrent HTTP requests
- `seenKeys` is a `Collections.synchronizedSet(LinkedHashSet)` — thread-safe dedup
- `VideoEnricher` runs its own `CachedThreadPool` (`enricher-worker` threads) — enrichment is non-blocking from the receiver's perspective

---

### 6.2 `VideoEnricher`

Silently fetches additional metadata for each accepted tile **without opening a browser**.

#### Concurrency Model

```
For video_id = "XYZ":

Thread A (enricher-worker)          Thread B (enricher-worker)
───────────────────────────         ────────────────────────────
fetchChannel("XYZ")                 fetchDescription("XYZ")
  GET youtube.com/oembed?v=XYZ        GET youtube.com/watch?v=XYZ
  parse JSON → "author_name"          parseShortDescription(html)
      │                                   │
      └──── thenCombine ──────────────────┘
                 │
                 ▼
           EnrichedData(channel, description)
```

- **HTTP client:** `HttpClient` with `HTTP_1_1` forced (one TCP connection per request, avoids YouTube HTTP/2 per-connection flow control)
- **Thread pool:** `Executors.newCachedThreadPool()` — creates one thread per in-flight request
- **Timeouts:** connect 8 s, oEmbed 10 s, watch page 12 s
- **GDPR bypass:** `Cookie: CONSENT=YES+cb; SOCS=CAI` header on watch-page requests

#### Channel Resolution — Fallback Chain

```
1. YouTube oEmbed JSON API  →  "author_name" field  (fast, reliable, no auth)
        │  4xx response?
        ▼
2. Regex on watch page HTML →  "ownerChannelName" in ytInitialPlayerResponse
```

#### Description Resolution — Fallback Chain

```
1. char-by-char walk of ytInitialPlayerResponse.shortDescription (full text)
        │  not found?
        ▼
2. <meta name="description" content="...">  (truncated ~160 chars)
```

The char-by-char walk handles all JSON escape sequences (`\n`, `\t`, `\"`, `\\`, `\/`, `\uXXXX`) and is capped at 60 KB to prevent runaway reads on very long descriptions.

---

### 6.3 `YouTubeMonitor` (Selenium)

An **independent** alternative monitor. It does not interact with the extension at all.

#### Lifecycle

```
main()
  └─ new YouTubeMonitor()
       ├─ Resolve forbidden.tags (system property or hardcoded defaults)
       ├─ WebDriverManager.chromedriver().setup()   ← auto-download ChromeDriver
       ├─ new ChromeDriver(options)                  ← real Chrome window
       └─ new JsonLogger()

  └─ monitor.start(startUrl)
       ├─ driver.get(startUrl)
       ├─ Runtime.addShutdownHook()                  ← graceful quit on Ctrl+C
       ├─ handleConsentScreen()
       └─ watchLoop()  [infinite]
            │
            every POLL_INTERVAL_MS (1 500 ms):
            ├─ read window.location.href
            ├─ isVideoUrl()?  and  new videoId?
            │     NO  → sleep, continue
            │     YES ↓
            ├─ sleep PAGE_SETTLE_MS (2 500 ms)       ← let SPA render <head>
            ├─ processVideoPage(url)
            │    ├─ scrapeTitle()   → WebDriverWait on <yt-formatted-string>
            │    ├─ scrapeTags()    → JS eval document.querySelector('meta[name=keywords]')
            │    ├─ evaluateAndApplyBlackout(tags)
            │    │    ├─ any forbidden tag in tags?  → applyBlackout()
            │    │    │      inject #yt-monitor-blackout div + pause player via JS
            │    │    └─ no match?                  → removeBlackout()
            │    └─ logger.log(new VideoMetadata(title, url, tags, blocked, timestamp))
            └─ update lastProcessedVideoId
```

#### Forbidden Tags (defaults)

`gaming`, `violence`, `gambling`, `nsfw`, `adult content`, `horror`, `drugs`, `explicit`, `18+`, `gore`

Override at runtime: `./gradlew run -Ptags="gaming,violence"`

---

### 6.4 `JsonLogger`

Minimal utility used exclusively by `YouTubeMonitor`.

- Configured with `SerializationFeature.INDENT_OUTPUT` for human-readable output
- Creates `logs/` directory on construction (idempotent)
- Output filename: `logs/video_<System.currentTimeMillis()>.json`
- Wraps `IOException` in `JsonLoggerException` (unchecked) to keep call sites clean

---

### 6.5 Data Models

#### `TileRecord` — Extension tile (used by TileReceiver path)

```
TileRecord
├── video_id      String   YouTube video ID (e.g. "dQw4w9WgXcQ")
├── title         String
├── channel       String
├── description   String
├── thumbnail_url String
├── url           String   Full watch URL
├── tile_type     String   CSS selector that matched this tile
├── scraped_at    String   ISO-8601, set by extension
├── page_url      String   YouTube page that was open during scrape
└── received_at   String   ISO-8601, stamped by TileReceiver on arrival

deduplicationKey() → video_id  if present, else  url
```

Annotated with `@JsonIgnoreProperties(ignoreUnknown = true)` — extension schema changes don't break deserialization.

#### `VideoMetadata` — Selenium monitor visit (used by YouTubeMonitor path)

```
VideoMetadata
├── title      String
├── url        String
├── tags       List<String>   from <meta name="keywords">
├── blocked    boolean        was the blackout overlay applied?
└── timestamp  String         ISO-8601 UTC

@JsonPropertyOrder: title, url, tags, blocked, timestamp
```

---

## 7. Storage & Persistence

### 7.1 `chrome.storage.local` (Extension)

| Key | Type | Written by | Read by |
|---|---|---|---|
| `profiles` | `Profile[]` | options.js | background.js, popup.js |
| `activeProfileId` | `string` | options.js | background.js, popup.js |
| `modelSettings` | `object` | options.js | background.js |
| `endpoint` | `string` | popup.js | background.js |
| `mode` | `string` | popup.js | background.js (scrape mode) |
| `topic` | `string` | popup.js | background.js |
| `watchOn` | `boolean` | background.js | popup.js |
| `lastResult` | `object` | background.js | popup.js (display) |
| `lastAt` | `string` | background.js | — |
| `classifyCache` | `object` | background.js | background.js |
| `classifyBackoff` | `object` | background.js | background.js |

### 7.2 Classification Cache Schema

```json
{
  "classifyCache": {
    "<item_key>": {
      "v": true,          // true = irrelevant (blocked)
      "exp": 1720000000000  // expiry timestamp ms
    }
  }
}
```

- Relevant tiles: TTL = 30 minutes
- Irrelevant tiles: TTL = 20 minutes (re-evaluate sooner in case profile changes)
- Failed tiles (API error): TTL = 20 minutes

### 7.3 `logs/` Directory (Java)

| File pattern | Producer | Contents |
|---|---|---|
| `tiles_<ms>.json` | `TileReceiver.saveBatch()` | Array of `TileRecord`-shaped objects (enriched) |
| `video_<ms>.json` | `JsonLogger.log()` | Single `VideoMetadata` object |

Both are pretty-printed JSON. The `logs/` directory is created at startup if absent.

---

## 8. Classification Pipeline

```
tiles[]  ──────────────────────────────────────────────────────────────┐
                                                                       │
┌──────────────────────── classifyTilesLocally() ──────────────────────┤
│                                                                       │
│  ① CACHE PHASE                                                       │
│     for each tile:                                                    │
│       key = tile.item_key or normalizedUrl                           │
│       entry = cache[key]                                              │
│       if entry && entry.exp > now:                                    │
│         use cached verdict → add to blocked list if entry.v == true  │
│       else: → uncached[]                                              │
│                                                                       │
│  ② BACKOFF GUARD                                                      │
│     if backoff.until > now:                                           │
│       log "API cooling down"                                          │
│       heuristic ALL uncached tiles → return early                    │
│                                                                       │
│  ③ HEURISTIC PRE-FILTER  (always runs on uncached tiles)             │
│     heuristicIrrelevant(tile, profile):                               │
│       text = title + channel + description (lowercased)              │
│       words = text.split(/\W+/)                                       │
│       for tag in profile.tags:                                        │
│         if text.includes(tag) OR words overlap topicWords(tag):      │
│           → RELEVANT (keep)                                           │
│       → IRRELEVANT (block)                                            │
│                                                                       │
│  ④ LLM PHASE  (if modelSettings.enabled && !backoff)                 │
│     chunk uncached tiles into groups of MODEL_CHUNK_SIZE (24)        │
│     per chunk:                                                        │
│       build tileSummary[] (item_key, video_id, title, channel,       │
│                             description, url)                         │
│       build profilePrompt() system + user messages                   │
│       callChatCompletion(messages, modelSettings)                    │
│         → raw LLM text                                                │
│       extractJsonObject(text)  → parse { "irrelevant": [...] }       │
│       for each item_key in irrelevant[]:                              │
│         mark tile as blocked                                          │
│     on API error:                                                     │
│       recordBackoff() → exponential delay before next LLM call       │
│       fall back to heuristic for remaining tiles                      │
│                                                                       │
│  ⑤ CACHE UPDATE                                                      │
│     for each classified tile:                                         │
│       cache[key] = { v: isIrrelevant, exp: now + TTL }               │
│     saveCache()                                                       │
│                                                                       │
└──────────────────────────────────────────────────────────────────────┘
          │
          ▼
  { blockedItemKeys[], usedModel: "gemini-2.5-flash" | "heuristic" }
```

---

## 9. Overlay & Blocking Mechanism

`applyOverlayFn` is serialised as a string and injected into the live YouTube tab via `chrome.scripting.executeScript`. It runs entirely in the page's JavaScript context.

### Overlay Style

```
position: absolute
inset: 0
z-index: 9999
background: rgba(0,0,0,0.85)
color: white
display: grid / place-items: center
text: "NOT RELEVANT"
backdrop-filter: blur(4px)
pointer-events: none
```

### Matching Logic

For every tile host element (matched by CSS selectors):
1. Find all `<a>` links inside the element
2. For each link, extract `videoId` and `itemKey` via URL normalisation
3. If `videoId` ∈ `blockedVideos[]` OR `itemKey` ∈ `blockedKeys[]`:
   - `element.style.position = 'relative'` (establish stacking context)
   - Append the overlay `<div>` as a child
   - Set `data-yt-blocked="1"` to prevent re-processing
4. Already-marked elements (`data-yt-blocked`) are skipped on re-runs

The overlay is **non-destructive** — it does not remove tiles from the DOM, it only covers them visually. This ensures YouTube's own lazy-loading and scroll logic is not disrupted.

---

## 10. Build System

**Tool:** Gradle 8+ (wrapper committed)  
**Language:** Java 24 (source + target compatibility)

### Dependencies

| Artifact | Version | Purpose |
|---|---|---|
| `selenium-java` | 4.21.0 | Browser automation for `YouTubeMonitor` |
| `webdrivermanager` | 5.9.2 | Auto-download matching ChromeDriver binary |
| `jackson-databind` | 2.17.1 | JSON serialisation / deserialisation |
| `slf4j-simple` | 2.0.13 | Suppress verbose WebDriverManager log noise |

### Gradle Tasks

| Task | Main Class | Description |
|---|---|---|
| `./gradlew run` | `YouTubeMonitor` | Launch Selenium monitor (opens Chrome) |
| `./gradlew runMonitor` | `YouTubeMonitor` | Explicit alias for `run` |
| `./gradlew runReceiver` | `TileReceiver` | Start HTTP tile receiver on :8765 |
| `./gradlew jar` | — | Build fat-jar with all dependencies |

### Task Parameters

```bash
# Override forbidden tags for the Selenium monitor
./gradlew run -Ptags="gaming,violence"

# Override the start URL
./gradlew run -Purl="https://www.youtube.com/watch?v=dQw4w9WgXcQ"

# Override the receiver port
./gradlew runReceiver -Pport=9000
```

### Fat-Jar Usage

```bash
# Build
./gradlew jar

# Run monitor
java -cp build/libs/tab-scrapper-1.0.0.jar com.youtubewatch.YouTubeMonitor

# Run receiver
java -cp build/libs/tab-scrapper-1.0.0.jar com.youtubewatch.TileReceiver
```

---

## 11. Key Design Decisions

### D1 — MV3 Service Worker (stateless between events)
The extension uses Chrome Manifest V3, where background pages are service workers that can be terminated at any time. All persistent state (profiles, cache, backoff, watch status) lives in `chrome.storage.local` and is loaded fresh at the start of every handler. This makes the extension fully restartable without data loss.

### D2 — Heuristic-first, LLM-second Classification
The heuristic pass is free (no network, no latency) and catches the majority of obvious irrelevant content. The LLM is invoked only when heuristics are inconclusive and the user has opted in. This keeps the extension fast and functional even without an API key.

### D3 — Exponential Backoff for API Calls
When the LLM endpoint returns an error, the extension enters a backoff period before retrying. Delays escalate through `[60, 120, 300, 600]` seconds to avoid hammering a quota-limited API or flooding a local endpoint that is temporarily down.

### D4 — Per-Tile Result Cache with TTL
Classification results are cached in `chrome.storage.local` to avoid re-classifying the same video tile on every Watch-mode scrape. TTL varies by verdict: relevant tiles expire in 30 minutes, irrelevant and error tiles in 20 minutes. This balances accuracy (the user's profile may change) against efficiency.

### D5 — HTTP/1.1 Forced in VideoEnricher
Java's `HttpClient` defaults to HTTP/2, which multiplexes all requests over a single TCP connection. YouTube applies per-connection flow control that makes concurrent HTTP/2 requests appear sequential. Forcing `HTTP_1_1` gives each `sendAsync` call its own socket, so a batch of tiles is enriched truly in parallel.

### D6 — Non-Destructive DOM Overlays
The overlay approach covers tiles with a frosted glass div rather than removing them from the DOM. Removing tiles breaks YouTube's internal component state (intersection observers, lazy loaders, playlist counters). The overlay approach is safe and reversible.

### D7 — Java Backend is Fully Optional
The extension never blocks on the Java receiver. `postTilesToReceiver` is fire-and-forget; if the server is down, the extension logs a soft warning and continues. All filtering and blocking happens entirely in the browser, making the Java backend a pure archival facility.

### D8 — Selenium Monitor as an Independent Path
`YouTubeMonitor` predates the extension and remains a fully self-contained alternative. It is useful in environments where extension installation is not possible (e.g., managed ChromeBooks, kiosk modes). Both paths share the `logs/` directory convention but are otherwise independent.

### D9 — Profile-Centric Design
The user's interest profile (name, description, tags) is the single source of truth for all classification decisions. Tags drive both the heuristic keyword matcher and the LLM prompt. The LLM is also used to *generate* tags from a profile description, creating a self-reinforcing system where the profile grows more accurate over time.

### D10 — GDPR Consent Bypass via Cookie Header
`VideoEnricher` sets `Cookie: CONSENT=YES+cb; SOCS=CAI` on all watch-page HTTP requests. Without this, YouTube's consent interstitial replaces the normal page HTML, yielding no video metadata at all. The Selenium monitor handles this differently — it clicks the Accept button in the real browser UI via `handleConsentScreen()`.

---

## 12. Component Interaction Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          User Actions                                   │
│          (click Scrape / toggle Watch / open Settings)                  │
└──────────────────────┬──────────────────┬───────────────────────────────┘
                       │                  │
              ┌────────▼────────┐  ┌──────▼──────────┐
              │   popup.js      │  │   options.js     │
              │  (toolbar UI)   │  │  (settings page) │
              └────────┬────────┘  └──────┬───────────┘
                       │                  │
          sendMessage  │                  │  storage.local read/write
                       ▼                  ▼
              ┌─────────────────────────────────────────┐
              │          background.js                  │
              │         (Service Worker)                │
              │                                         │
              │  ┌─────────────┐  chrome.storage.local  │
              │  │loadSettings │◄──────────────────────►│
              │  └─────────────┘                        │
              │                                         │
              │  ┌─────────────────────────────────┐    │
              │  │       scrapeAndSend()           │    │
              │  │                                 │    │
              │  │  executeScript(scrapeFn)        │    │
              │  │       │                         │    │
              │  │       ▼                         │    │
              │  │  ┌─────────────────────────┐    │    │
              │  │  │  YouTube Tab DOM        │    │    │
              │  │  │  scrapeFn() runs here   │    │    │
              │  │  └──────────┬──────────────┘    │    │
              │  │             │ tiles[]            │    │
              │  │             ▼                   │    │
              │  │  classifyTilesLocally()          │    │
              │  │       │                  ┌──────►─►  Gemini API
              │  │       │  (if LLM on)    │       │    (cloud)
              │  │       │                 │       │
              │  │       ▼                 │       │
              │  │  blockedItemKeys[]      │       │
              │  │       │                         │
              │  │  executeScript(applyOverlayFn)  │
              │  │       │                         │
              │  │       ▼                         │
              │  │  ┌─────────────────────────┐    │
              │  │  │  YouTube Tab DOM        │    │
              │  │  │  overlays applied here  │    │
              │  │  └─────────────────────────┘    │
              │  │       │                         │
              │  │  postTilesToReceiver() ──────────┼──────────────────────┐
              │  └─────────────────────────────────┘    │                  │
              │                                         │                  │
              └─────────────────────────────────────────┘                  │
                                                                           │
                                                    HTTP POST /tiles       │
                                          ┌────────────────────────────────┘
                                          ▼
                              ┌───────────────────────┐
                              │    TileReceiver        │
                              │    :8765               │
                              │                        │
                              │  deduplicate           │
                              │       │                │
                              │       ▼                │
                              │  VideoEnricher         │
                              │  (parallel HTTP)       │
                              │    ├─ oEmbed → channel │
                              │    └─ watch  → desc    │
                              │       │                │
                              │       ▼                │
                              │  logs/tiles_<t>.json   │
                              └───────────────────────┘

                              ┌───────────────────────┐
                              │  YouTubeMonitor        │ (independent)
                              │  (Selenium)            │
                              │                        │
                              │  ChromeDriver          │
                              │    ↓ navigate YT       │
                              │    ↓ scrape title/tags │
                              │    ↓ blackout overlay  │
                              │    ↓ JsonLogger         │
                              │  logs/video_<t>.json   │
                              └───────────────────────┘
```

---

*Document generated from source analysis of `LockIn/tab-scrapper` — extension version 0.1.0, Java backend version 1.0.0.*