# LockIn — YouTube Tab Scrapper

A self-contained Java tool that does two things:

1. **YouTube Monitor** — Opens a real Chrome window via Selenium, watches for video changes, scrapes metadata, blacks out the screen if a forbidden tag is detected, and saves every video visit as a JSON file.
2. **Tile Receiver** — A lightweight HTTP server that receives tile data POSTed by the Chrome extension and saves each scrape batch as a JSON file.

---

## Project Structure

```
tab-scrapper/
├── build.gradle                            ← Gradle build file
├── settings.gradle
├── gradlew                                 ← Gradle wrapper (use this to run)
├── extension/                              ← Chrome extension (load unpacked)
│   ├── manifest.json
│   ├── background.js
│   ├── popup.html
│   └── popup.js
└── src/main/java/com/youtubewatch/
    ├── YouTubeMonitor.java                 ← Selenium monitor + blackout engine
    ├── TileReceiver.java                   ← HTTP server on localhost:8765
    ├── TileRecord.java                     ← Tile data model
    ├── VideoMetadata.java                  ← Video data model
    └── JsonLogger.java                     ← JSON file writer
```

---

## Requirements

| Requirement | Version |
|---|---|
| Java (JDK) | 17 or higher |
| Google Chrome | Any recent version |
| Gradle | Comes bundled via `./gradlew` — nothing to install |

> ChromeDriver is managed automatically by WebDriverManager. You do not need to download it manually.

---

## Quick Start

### 1. Clone the repo

```bash
git clone https://github.com/alanturrr-1703/LockIn.git
cd LockIn/tab-scrapper
```

---

### 2. Build the project

```bash
./gradlew build
```

---

## Running the YouTube Monitor (Selenium)

Opens a real Chrome window and watches whatever you browse on YouTube.
Automatically scrapes title and tags on every new video, blacks out the screen if a forbidden tag is matched, and saves a JSON file per video under `logs/`.

```bash
./gradlew run
```

**Start directly on a specific video:**

```bash
./gradlew run -Purl="https://www.youtube.com/watch?v=dQw4w9WgXcQ"
```

**Override the forbidden tag list:**

```bash
./gradlew run -Ptags="gaming,violence,gambling,horror"
```

Default forbidden tags: `gaming, violence, gambling, nsfw, adult content, horror, drugs, explicit, 18+, gore`

**What happens when a forbidden tag is detected:**
- The video is paused
- A black full-screen overlay is injected with the matched tag name
- A JSON log is saved to `logs/video_<timestamp>.json`

**To stop:** press `Ctrl+C` in the terminal. The browser closes automatically.

---

## Running the Tile Receiver (Chrome Extension Backend)

Starts a local HTTP server on `http://127.0.0.1:8765` that receives tile batches from the Chrome extension and saves them as JSON files under `logs/`.

```bash
./gradlew runReceiver
```

**Run on a custom port:**

```bash
./gradlew runReceiver -Pport=9000
```

You should see:

```
[TileReceiver] ✔  Listening on http://127.0.0.1:8765/tiles
[TileReceiver]    Health check : http://127.0.0.1:8765/health
[TileReceiver]    Logs folder  : .../tab-scrapper/logs
[TileReceiver]    Press Ctrl+C to stop.
```

**Verify it is running** (open in browser or run in a second terminal):

```bash
curl http://127.0.0.1:8765/health
```

Returns:

```json
{
  "ok": true,
  "status": "running",
  "received": 0,
  "accepted": 0,
  "unique": 0
}
```

**To stop:** press `Ctrl+C`.

---

## Loading the Chrome Extension

The receiver must be running before you use the extension.

1. Open Chrome and go to `chrome://extensions`
2. Toggle **Developer mode** ON (top-right corner)
3. Click **Load unpacked**
4. Select the folder:
   ```
   LockIn/tab-scrapper/extension
   ```
5. Click the 🧩 puzzle icon in Chrome's toolbar and **pin** YT Tile Scraper

### Using the extension

1. Go to any YouTube page (home, search, channel, watch page — all work)
2. Click the **📡 YT Tile Scraper** icon in the toolbar
3. Choose a scrape mode:
   - **Lookahead** *(default)* — scrapes tiles 3000 px below the visible fold
   - **Strict** — only tiles currently visible on screen
   - **All** — every tile in the DOM regardless of position
4. Click **▶ Scrape now** for a one-shot scrape
5. Click **⏺ Watch: off** to turn on auto-scrape every ~3 seconds

The popup status line will show:

```
✔ Sent 31 tiles. New: 31.
```

And the receiver terminal will print:

```
[TileReceiver] received=31  accepted=31  total_unique=31
[TileReceiver] Saved  ➜  .../logs/tiles_1718123456789.json
```

Deduplication is on by default — the same video will never be written twice across repeated scrapes of the same page.

---

## Output Files

All output lands in `tab-scrapper/logs/` (created automatically, excluded from Git).

### Video monitor log — `logs/video_<timestamp>.json`

```json
{
  "title"     : "Video Title Here",
  "url"       : "https://www.youtube.com/watch?v=xxxxxxx",
  "tags"      : ["music", "pop", "official video"],
  "blocked"   : false,
  "timestamp" : "2024-07-15T10:30:00.123456Z"
}
```

### Extension tile batch — `logs/tiles_<timestamp>.json`

```json
{
  "page_url"    : "https://www.youtube.com/",
  "received_at" : "2024-07-15T10:30:00.123Z",
  "count"       : 5,
  "tiles" : [
    {
      "video_id"     : "dQw4w9WgXcQ",
      "title"        : "Rick Astley - Never Gonna Give You Up",
      "channel"      : "Rick Astley",
      "description"  : "",
      "thumbnail_url": "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
      "url"          : "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
      "tile_type"    : "ytd-rich-item-renderer",
      "scraped_at"   : "2024-07-15T10:30:00.000Z",
      "page_url"     : "https://www.youtube.com/",
      "received_at"  : "2024-07-15T10:30:00.123Z"
    }
  ]
}
```

---

## Running Both Together

Open two terminals:

**Terminal 1 — Tile Receiver:**
```bash
cd LockIn/tab-scrapper
./gradlew runReceiver
```

**Terminal 2 — YouTube Monitor:**
```bash
cd LockIn/tab-scrapper
./gradlew run
```

Both write to the same `logs/` folder. The receiver handles extension batches (`tiles_*.json`) and the monitor handles per-video snapshots (`video_*.json`).

---

## Gradle Task Reference

| Command | Description |
|---|---|
| `./gradlew run` | Start the Selenium YouTube monitor |
| `./gradlew run -Purl="..."` | Monitor starting at a specific URL |
| `./gradlew run -Ptags="a,b,c"` | Monitor with custom forbidden tags |
| `./gradlew runReceiver` | Start the HTTP tile receiver on port 8765 |
| `./gradlew runReceiver -Pport=9000` | Receiver on a custom port |
| `./gradlew build` | Compile and package everything |
| `./gradlew compileJava` | Compile only (no jar) |

---

## Troubleshooting

| Problem | Fix |
|---|---|
| `Address already in use` on port 8765 | Another process is on that port. Run `lsof -i :8765` to find it and kill it, or use `-Pport=9000` |
| Chrome opens but immediately closes | ChromeDriver version mismatch — run `./gradlew build` again, WebDriverManager will re-resolve it |
| Extension shows `Receiver unreachable` | The Java receiver is not running — start it with `./gradlew runReceiver` first |
| `0 tiles scraped` in the extension popup | YouTube hasn't finished loading — scroll down a little and try again |
| Blackout doesn't appear | The video's tags don't match the forbidden list. Check what tags were scraped in the `logs/video_*.json` file and adjust with `-Ptags` |
| `./gradlew: Permission denied` | Run `chmod +x gradlew` once, then retry |