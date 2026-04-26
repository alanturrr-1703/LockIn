# LockIn

A local-first YouTube content filter and desktop productivity guard. LockIn uses a locally running LLM (Ollama) to classify YouTube recommendations against your interest profile and block irrelevant content before you see it — with zero cloud dependency and zero API costs.

---

## What it does

| Feature | Description |
|---|---|
| **YouTube tile filtering** | Scrapes every video tile on YouTube, classifies it against your profile using a local LLM, and overlays a frosted-glass block on anything irrelevant |
| **Instant re-blocking** | A `MutationObserver` re-applies overlays the moment YouTube re-renders tiles (infinite scroll, SPA navigation) — no flash of unblocked content |
| **Profile-based classification** | You write a plain-English description of what you want to watch. The LLM uses that description. Your tags are a heuristic fallback when Ollama is down |
| **Desktop app control** | A JavaFX desktop app lets you enable/disable the extension, manage profiles, and see live stats — all without opening Chrome |
| **Profile sync** | Profiles created or activated on the desktop app automatically sync to the extension every 30 seconds |
| **Desktop file guard** | Scans `~/Desktop`, classifies every file and folder by name against your profile, and moves irrelevant items to a quarantine folder — restoring them instantly when LockIn is paused or the profile changes |
| **Fully local** | Everything runs on your machine. Ollama, the extension, and the desktop app communicate over `localhost` only |

---

## System Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Arc / Chrome                             │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              YouTube Tab                                 │   │
│  │   scrapeFn() ──► tiles[]                                 │   │
│  │   applyOverlayFn() ──► frosted overlays on blocked tiles │   │
│  │   MutationObserver ──► instant re-block on re-render     │   │
│  └────────────────────────┬─────────────────────────────────┘   │
│                           │ chrome.scripting                    │
│  ┌────────────────────────▼─────────────────────────────────┐   │
│  │               background.js  (Service Worker)            │   │
│  │  classifyTilesLocally() ──► Ollama llama3.2:3b           │   │
│  │  heuristicIrrelevant()  ──► profile tags (fallback)      │   │
│  │  syncProfilesWithDesktop() ──► POST /profiles every 30s  │   │
│  └────────────────────────┬─────────────────────────────────┘   │
└───────────────────────────│─────────────────────────────────────┘
                            │ HTTP  localhost:7432
┌───────────────────────────▼─────────────────────────────────────┐
│                  LockIn Desktop App (JavaFX)                    │
│                                                                 │
│  ControlServer :7432   ──► /status  /profiles  /stats          │
│  DesktopGuard          ──► scan ~/Desktop, quarantine files     │
│  OllamaMonitor         ──► poll localhost:11434 every 4s        │
└─────────────────────────────────────────────────────────────────┘
                            │
                    localhost:11434
┌───────────────────────────▼─────────────────────────────────────┐
│                        Ollama                                   │
│  llama3.2:3b  ──► KEEP/BLOCK labels for YouTube tiles          │
│               ──► KEEP/BLOCK labels for Desktop files          │
│  gemma4:e2b   ──► tag suggestions for profiles                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Repository Layout

```
LockIn/
├── README.md
├── ARCHITECTURE.md                    ← deep-dive component reference
├── desktop/                           ← JavaFX desktop control app
│   ├── build.gradle
│   ├── gradlew
│   └── src/main/java/com/lockin/
│       ├── App.java                   ← main window, all UI panels
│       ├── ControlServer.java         ← HTTP server on :7432
│       ├── DesktopGuard.java          ← ~/Desktop scanner + quarantine
│       └── OllamaMonitor.java         ← async Ollama health check
└── tab-scrapper/
    ├── build.gradle                   ← legacy Java backend (optional)
    ├── extension/                     ← Chrome Extension (MV3)
    │   ├── manifest.json
    │   ├── background.js              ← service worker, core logic
    │   ├── popup.html / popup.js      ← toolbar popup
    │   └── options.html / options.js  ← settings page
    └── src/main/java/com/youtubewatch/
        ├── TileReceiver.java          ← optional archival server :8765
        ├── VideoEnricher.java         ← async metadata fetcher
        ├── YouTubeMonitor.java        ← legacy Selenium monitor
        ├── JsonLogger.java
        ├── TileRecord.java
        └── VideoMetadata.java
```

---

## Requirements

| Requirement | Version |
|---|---|
| Java (JDK) | 21 or higher |
| Ollama | Latest |
| Arc or Chrome | Any recent version |
| macOS | Tested on macOS (Apple Silicon) |

---

## Quick Start

### 1. Clone

```bash
git clone https://github.com/alanturrr-1703/LockIn.git
cd LockIn
```

### 2. Install and start Ollama

```bash
# Install from https://ollama.com or via Homebrew
brew install ollama

# Pull the models LockIn uses
ollama pull llama3.2:3b   # KEEP/BLOCK classification
ollama pull gemma4:e2b    # tag suggestion

# Start Ollama with extension CORS enabled
OLLAMA_ORIGINS="*" ollama serve
```

> `OLLAMA_ORIGINS="*"` is required because the Chrome extension sends requests with a `chrome-extension://` origin, which Ollama blocks by default.
>
> **To make this permanent on macOS (menu-bar app):**
> ```bash
> launchctl setenv OLLAMA_ORIGINS "*"
> # Then quit and reopen Ollama
> ```

### 3. Load the Chrome Extension

1. Open `arc://extensions` or `chrome://extensions`
2. Enable **Developer mode**
3. Click **Load unpacked**
4. Select `LockIn/tab-scrapper/extension`
5. Pin the extension to your toolbar

### 4. Launch the desktop app

```bash
cd LockIn/desktop
./gradlew run
```

The window appears in a few seconds. Keep the terminal open.

**To launch cleanly if port 7432 is already in use:**
```bash
lsof -ti :7432 | xargs kill -9 2>/dev/null; ./gradlew run
```

---

## Chrome Extension

### Popup controls

| Control | What it does |
|---|---|
| **Mode** select | `lookahead` — scrape 3000 px below fold (default) · `strict` — visible only · `all` — entire DOM |
| **Focus topic** | Ad-hoc override topic sent to the classifier |
| **Scrape now** | One-shot scrape + classify + overlay cycle |
| **Watch on/off** | Starts a repeating alarm (every 30 s) that scrapes automatically |
| **Open settings** | Opens the full profile management page |

### Settings page

- **Profiles** — create, rename, activate, delete profiles
- **Prompt** — plain-English description of what you want to watch (drives the LLM)
- **Tags** — comma-separated keywords used by the heuristic fallback when Ollama is unreachable
- **Suggest tags** — asks `gemma4:e2b` to generate 40–50 tags from your profile description

### Classification pipeline

```
For each fresh tile (not in cache):
  │
  ├── Ollama UP   →  llama3.2:3b classifies using your profile prompt
  │                  Result cached permanently
  │
  └── Ollama DOWN →  heuristic: check if title+channel+description contains
                     any of your profile tags
                     Result NOT cached (Ollama retries next cycle)
```

The prompt format is deliberately minimal to keep latency low:

```
System: You label YouTube videos as KEEP or BLOCK. Reply only with labels,
        one per line like "1:KEEP". No other text.

User:   User focus: Focus on algorithms, data structures, ML, system design.

        1. "Dynamic Programming Explained" – CS Dojo
        2. "IPL 2025 Highlights" – Cricket Max
        3. "LLM Fine-tuning in Python" – Andrej Karpathy
        4. "Bollywood Top 10" – T-Series
```

Response: `1:KEEP  2:BLOCK  3:KEEP  4:BLOCK`

### Caching

Classification results are stored in `chrome.storage.local` with a TTL so the same tile is never re-classified on every scrape:

| Result | Cache TTL |
|---|---|
| Ollama verdict (KEEP or BLOCK) | Permanent (until profile changes) |
| Heuristic verdict | Not cached — Ollama retries next cycle |

---

## Desktop App

The JavaFX app binds an HTTP server on `http://127.0.0.1:7432` that acts as a bridge between the desktop and the extension.

### Panels

**Runtime** — Shows Ollama status (green dot = running, red = stopped), the classify model (`llama3.2:3b`), and the tags model (`gemma4:e2b`).

**Session Stats** — Live counters updated by the extension after every scrape: tiles blocked, scrape cycles, active profile name, last scrape time.

**Profiles** — Full profile management without opening Chrome:
- See all profiles synced from the extension
- Click **Set Active** to switch profiles — the extension picks up the change within 30 seconds
- Click **＋ New Profile** to create a profile with a name, prompt, and tags — it syncs to the extension automatically

**Desktop Guard** — Scans `~/Desktop` and quarantines irrelevant files:
- Click **⟳ Scan Now** to classify all desktop files against the active profile
- Blocked items (red chips) are moved to `~/.lockin-quarantine/`
- Kept items (green chips) stay on the desktop
- Pausing LockIn restores all quarantined files instantly
- Switching profiles restores all files first, then re-classifies from scratch under the new profile

### HTTP endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/status` | Extension polls before every scrape — returns `{ "enabled": true/false }` |
| `POST` | `/profiles` | Extension pushes current profiles; server replies with pending desktop changes |
| `POST` | `/stats` | Extension reports blocked count, profile name, scrape timestamp |
| `POST` | `/toggle` | Flip enabled state programmatically |
| `GET` | `/health` | Liveness check + cumulative stats |

### Profile sync protocol

```
Extension (every 30s, independent of Watch mode)
  POST /profiles  { profiles: [...], activeProfileId: "..." }
      │
      ├── desktop made no changes →  { dirty: false }
      │                              extension keeps its current state
      │
      └── desktop created/activated →  { dirty: true, profiles: [...], activeProfileId: "..." }
                                        extension writes to chrome.storage.local
                                        changes take effect on next scrape
```

---

## Desktop File Guard

When LockIn is **active**, the Desktop Guard:

1. Calls `restoreAllSilently()` — returns any previously quarantined files to `~/Desktop` first (ensures a clean slate on every scan, especially after a profile switch)
2. Lists all non-hidden files and folders in `~/Desktop`
3. Sends them in batches of 10 to `llama3.2:3b` with the active profile's prompt
4. Moves anything labelled `BLOCK` to `~/.lockin-quarantine/`
5. Writes a manifest at `~/.lockin-quarantine/manifest.json` recording every moved item

When LockIn is **paused** (toggle off), all quarantined items are moved back to `~/Desktop` instantly.

### Example

```
Active profile: "Computer science student — focus on DSA, ML, system design"

~/Desktop before scan:
  Projects/         → KEEP  ✅
  Notes.md          → KEEP  ✅
  LeetCode.py       → KEEP  ✅
  Photos/           → BLOCK ❌  moved to ~/.lockin-quarantine/Photos/
  Netflix Downloads → BLOCK ❌  moved to ~/.lockin-quarantine/Netflix Downloads/
  Family Trip 2024/ → BLOCK ❌  moved to ~/.lockin-quarantine/Family Trip 2024/

Switch profile to: "Physics student — focus on quantum mechanics, thermodynamics"

Scan re-runs:
  restoreAllSilently() → Photos, Netflix, Family Trip back on Desktop
  Re-classify under Physics profile:
  Photos/           → KEEP  ✅  (personal life / study breaks — allowed)
  Netflix Downloads → BLOCK ❌
  Family Trip 2024/ → KEEP  ✅
```

---

## Troubleshooting

| Problem | Fix |
|---|---|
| Extension gets 403 from Ollama | Start Ollama with `OLLAMA_ORIGINS="*" ollama serve` |
| Desktop app fails with `Address already in use` | Run `lsof -ti :7432 \| xargs kill -9` then relaunch |
| Profiles not syncing to extension | Reload the extension in `arc://extensions` — this re-creates the `PROFILE_SYNC_ALARM` |
| Nothing is being blocked on YouTube | Check that Ollama is running (`curl localhost:11434`) and that your profile has a prompt set |
| Desktop guard not restoring files | Check `~/.lockin-quarantine/manifest.json` — if it exists, run the app and toggle LockIn off |
| `./gradlew: Permission denied` | Run `chmod +x gradlew` once then retry |
| Ollama takes too long per tile | Make sure you're using `llama3.2:3b` not `gemma4:e2b` for classification — gemma4 is much slower |

---

## Models

| Model | Role | Why |
|---|---|---|
| `llama3.2:3b` | YouTube tile classification, Desktop file classification | Fast, accurate KEEP/BLOCK at 3B params (~5–8 s per batch of 4 tiles) |
| `gemma4:e2b` | Profile tag suggestion | Larger model gives richer, more contextual tag sets |

To change models, edit the constants in `background.js` (extension) and `DesktopGuard.java` (desktop):

```js
// background.js
const OLLAMA_MODEL_CLASSIFY = "llama3.2:3b";
const OLLAMA_MODEL_TAGS     = "gemma4:e2b";
```

```java
// DesktopGuard.java
private static final String MODEL = "llama3.2:3b";
```

---

## Legacy Java Backend (optional)

`tab-scrapper/src/` contains an older Java HTTP server (`TileReceiver`) and a Selenium-based monitor (`YouTubeMonitor`). These are **not required** for normal use — the extension works entirely without them.

| Command | What it runs |
|---|---|
| `cd tab-scrapper && ./gradlew runReceiver` | Tile archival server on `:8765` |
| `cd tab-scrapper && ./gradlew run` | Selenium YouTube monitor (opens real Chrome window) |

---

## License

MIT — see [LICENSE](LICENSE).