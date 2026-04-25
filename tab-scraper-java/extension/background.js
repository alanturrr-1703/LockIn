// ── Tab Scraper → Java  |  background.js (service worker) ─────────────────
//
// Scrapes YouTube tiles using the exact same JS selectors as yt_scraper.py
// and POSTs them to the Java Spring Boot backend on port 8080.
//
// Architecture:
//   [YouTube Tab]
//        ↓  chrome.scripting.executeScript  (EXTRACT_JS — same as yt_scraper.py)
//   [This service worker]
//        ↓  POST /tiles  { tiles: [...], page_url: "..." }
//   [Java Spring Boot :8080]
//        ↓
//   [H2 database]  +  [tiles.jsonl  — NDJSON, one line per tile]

const DEFAULT_TILES_ENDPOINT = "http://localhost:8080/tiles";
const DEFAULT_HEALTH_ENDPOINT = "http://localhost:8080/health";
const DEFAULT_MODE = "lookahead";
const DEFAULT_LOOKAHEAD = 3000;
const ALARM_NAME = "yt-tile-watch";

// ── EXTRACT_JS ────────────────────────────────────────────────────────────────
// Injected verbatim into the YouTube page — identical logic to yt_scraper.py's
// EXTRACT_JS.  Returns an array of tile objects.

function extractTiles({ selectors, lookahead, mode }) {
  const out = [];

  const TITLE_SEL = [
    "#video-title-link",
    "a#video-title",
    "#video-title",
    "h3 a#video-title-link",
    "h3 #video-title",
    "yt-formatted-string#video-title",
    "a.yt-lockup-metadata-view-model-wiz__title",
  ];
  const CHANNEL_SEL = [
    "ytd-channel-name #text a",
    "ytd-channel-name #text",
    "ytd-channel-name a",
    "#channel-name #text",
    "#channel-name a",
    "#byline a",
    "#byline",
    ".ytd-channel-name",
    ".yt-content-metadata-view-model-wiz__metadata-row a",
    ".yt-content-metadata-view-model-wiz__metadata-text",
  ];
  const DESC_SEL = [
    "#description-text",
    "yt-formatted-string#description-text",
    ".metadata-snippet-text",
    "#description",
  ];

  const firstText = (el, list) => {
    for (const sel of list) {
      const node = el.querySelector(sel);
      if (!node) continue;
      const t =
        (node.getAttribute && node.getAttribute("title")) ||
        node.textContent ||
        "";
      const cleaned = t.replace(/\s+/g, " ").trim();
      if (cleaned) return cleaned;
    }
    return "";
  };

  const firstHref = (el) => {
    const cand = el.querySelector(
      "a#thumbnail[href], a#video-title-link[href], a#video-title[href], " +
        "a.yt-lockup-metadata-view-model-wiz__title[href], " +
        "a[href*='/watch?v='], a[href*='/shorts/']",
    );
    if (!cand) return "";
    const h = cand.getAttribute("href") || "";
    if (!h) return "";
    try {
      return new URL(h, location.origin).toString();
    } catch (_) {
      return h;
    }
  };

  const videoIdFromUrl = (url) => {
    if (!url) return "";
    try {
      const u = new URL(url);
      const v = u.searchParams.get("v");
      if (v) return v;
      const m = u.pathname.match(/\/shorts\/([^/?#]+)/);
      if (m) return m[1];
    } catch (_) {}
    return "";
  };

  const firstThumb = (el) => {
    const img = el.querySelector("img#img, img.yt-core-image, img");
    if (!img) return "";
    return img.getAttribute("src") || img.getAttribute("data-thumb") || "";
  };

  const isoNow = () => new Date().toISOString().replace(/\.\d{3}Z$/, "Z");

  const vpH = window.innerHeight || document.documentElement.clientHeight;
  const seen = new Set();
  const seenIds = new Set();
  const ts = isoNow();

  for (const sel of selectors) {
    const nodes = document.querySelectorAll(sel);
    for (const el of nodes) {
      if (seen.has(el)) continue;
      seen.add(el);

      const rect = el.getBoundingClientRect();
      if (rect.width === 0 && rect.height === 0) continue;

      let include = true;
      if (mode === "strict") {
        include = rect.bottom > 0 && rect.top < vpH;
      } else if (mode === "lookahead") {
        include = rect.bottom > 0 && rect.top < vpH + lookahead;
      } else {
        // "all" — everything materialised in DOM
        include = true;
      }
      if (!include) continue;

      const title = firstText(el, TITLE_SEL);
      const channel = firstText(el, CHANNEL_SEL);
      if (!title && !channel) continue; // skip skeleton loaders

      const url = firstHref(el);
      const video_id = videoIdFromUrl(url);

      // Deduplicate within a single scrape pass
      if (video_id) {
        if (seenIds.has(video_id)) continue;
        seenIds.add(video_id);
      }

      out.push({
        video_id,
        title,
        channel,
        description: firstText(el, DESC_SEL),
        thumbnail_url: firstThumb(el),
        url,
        tile_type: sel,
        scraped_at: ts,
      });
    }
  }

  // Fallback: if structured selectors found nothing, scan all video links
  if (out.length === 0) {
    const links = document.querySelectorAll(
      "a[href*='/watch?v='], a[href*='/shorts/']",
    );
    for (const a of links) {
      const rawHref = a.getAttribute("href") || "";
      let url = "";
      try {
        url = new URL(rawHref, location.origin).toString();
      } catch (_) {
        url = a.href || "";
      }
      const video_id = videoIdFromUrl(url);
      if (!video_id || seenIds.has(video_id)) continue;

      let host = a;
      for (let i = 0; i < 6 && host && host !== document.body; i++)
        host = host.parentElement;
      const card = host || a.parentElement || a;

      if (mode !== "all") {
        const rect = card.getBoundingClientRect();
        const limit = mode === "strict" ? vpH : vpH + lookahead;
        if (!(rect.bottom > 0 && rect.top < limit)) continue;
      }

      const title = (a.getAttribute("title") || a.textContent || "")
        .replace(/\s+/g, " ")
        .trim();
      const channel = firstText(card, CHANNEL_SEL);
      if (!title && !channel) continue;

      seenIds.add(video_id);
      out.push({
        video_id,
        title,
        channel,
        description: firstText(card, DESC_SEL),
        thumbnail_url: firstThumb(card),
        url,
        tile_type: "fallback:link",
        scraped_at: ts,
      });
    }
  }

  try {
    console.log(
      "[Tab Scraper → Java] extracted",
      out.length,
      "tiles from",
      location.href,
    );
  } catch (_) {}

  return out;
}

// ── Selectors (same list as TILE_SELECTORS in yt_scraper.py) ─────────────────

const TILE_SELECTORS = [
  "ytd-rich-item-renderer",
  "ytd-video-renderer",
  "ytd-grid-video-renderer",
  "ytd-compact-video-renderer",
  "ytd-playlist-video-renderer",
  "ytd-reel-item-renderer",
  "yt-lockup-view-model",
  "ytm-shorts-lockup-view-model",
];

// ── Core scrape + send ────────────────────────────────────────────────────────

async function scrapeAndSend({ endpoint, mode, lookahead }) {
  const target = (endpoint || DEFAULT_TILES_ENDPOINT).trim();

  // Find the best YouTube tab
  const tab = await findYouTubeTab();
  if (!tab) {
    return { ok: false, reason: "No open YouTube tab found." };
  }

  // Inject the extractor
  let injectionResults;
  try {
    injectionResults = await chrome.scripting.executeScript({
      target: { tabId: tab.id },
      func: extractTiles,
      args: [
        {
          selectors: TILE_SELECTORS,
          lookahead: lookahead || DEFAULT_LOOKAHEAD,
          mode: mode || DEFAULT_MODE,
        },
      ],
    });
  } catch (e) {
    return {
      ok: false,
      reason: "Script injection failed: " + e.message,
    };
  }

  const tiles =
    injectionResults && injectionResults[0] && injectionResults[0].result;

  if (!tiles) {
    return { ok: false, reason: "Injection returned no data." };
  }

  if (tiles.length === 0) {
    return {
      ok: true,
      sent: 0,
      accepted: 0,
      reason: "0 tiles found. Is this a YouTube page with videos?",
    };
  }

  // POST to Java backend — same payload format as what the existing
  // scrapper/extension sends to yt_receiver.py
  let response;
  try {
    response = await fetch(target, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        tiles,
        page_url: tab.url,
      }),
    });
  } catch (e) {
    return {
      ok: false,
      reason:
        `Backend unreachable at ${target}\n` +
        `Is  ./gradlew bootRun  running?\nError: ${e.message}`,
    };
  }

  if (!response.ok) {
    let body = "";
    try {
      body = await response.text();
    } catch (_) {}
    return {
      ok: false,
      reason: `Backend returned HTTP ${response.status}.\n${body}`.trim(),
    };
  }

  const json = await response.json().catch(() => ({}));
  // json = { ok: true, received: N, accepted: M }

  return {
    ok: true,
    sent: json.received ?? tiles.length,
    accepted: json.accepted ?? tiles.length,
    pageUrl: tab.url,
  };
}

// ── YouTube tab finder ────────────────────────────────────────────────────────

async function findYouTubeTab() {
  const tabs = await chrome.tabs.query({});
  const ytTabs = tabs.filter((t) => t.url && /youtube\.com/.test(t.url));
  if (ytTabs.length === 0) return null;
  // Prefer the active tab; fall back to most recently accessed
  const active = ytTabs.find((t) => t.active);
  if (active) return active;
  ytTabs.sort((a, b) => (b.lastAccessed || 0) - (a.lastAccessed || 0));
  return ytTabs[0];
}

// ── Health-check helper ───────────────────────────────────────────────────────

async function pingHealth() {
  try {
    const r = await fetch(DEFAULT_HEALTH_ENDPOINT, { method: "GET" });
    if (!r.ok) return { ok: false, status: r.status };
    const json = await r.json();
    return { ok: true, stored: json.stored ?? 0 };
  } catch (e) {
    return { ok: false, reason: e.message };
  }
}

// ── Watch mode via chrome.alarms ──────────────────────────────────────────────
// Alarms survive popup close — same pattern as the existing yt scraper extension.

chrome.alarms.onAlarm.addListener(async (alarm) => {
  if (alarm.name !== ALARM_NAME) return;
  const { endpoint, mode, lookahead } = await chrome.storage.local.get([
    "endpoint",
    "mode",
    "lookahead",
  ]);
  const result = await scrapeAndSend({
    endpoint: endpoint || DEFAULT_TILES_ENDPOINT,
    mode: mode || DEFAULT_MODE,
    lookahead: lookahead || DEFAULT_LOOKAHEAD,
  });
  await chrome.storage.local.set({
    lastResult: result,
    lastAt: Date.now(),
  });
});

// ── Message listener (called by popup.js) ────────────────────────────────────

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (!msg || !msg.cmd) {
    sendResponse({ ok: false, reason: "No command specified." });
    return false;
  }

  (async () => {
    switch (msg.cmd) {
      case "scrape": {
        const result = await scrapeAndSend({
          endpoint: msg.endpoint || DEFAULT_TILES_ENDPOINT,
          mode: msg.mode || DEFAULT_MODE,
          lookahead: msg.lookahead || DEFAULT_LOOKAHEAD,
        });
        await chrome.storage.local.set({
          lastResult: result,
          lastResultAt: Date.now(),
        });
        sendResponse(result);
        break;
      }

      case "watch-start": {
        const minutes = Math.max(0.05, Number(msg.intervalMinutes) || 2);
        await chrome.alarms.create(ALARM_NAME, {
          periodInMinutes: minutes,
        });
        const immediate = await scrapeAndSend({
          endpoint: msg.endpoint || DEFAULT_TILES_ENDPOINT,
          mode: msg.mode || DEFAULT_MODE,
          lookahead: msg.lookahead || DEFAULT_LOOKAHEAD,
        });
        await chrome.storage.local.set({
          watchOn: true,
          lastResult: immediate,
          lastAt: Date.now(),
        });
        sendResponse({ ok: true, watchOn: true, immediate });
        break;
      }

      case "watch-stop": {
        await chrome.alarms.clear(ALARM_NAME);
        await chrome.storage.local.set({ watchOn: false });
        sendResponse({ ok: true, watchOn: false });
        break;
      }

      case "ping": {
        sendResponse(await pingHealth());
        break;
      }

      default:
        sendResponse({ ok: false, reason: `Unknown command: "${msg.cmd}"` });
    }
  })();

  return true; // keep channel open for async sendResponse
});

// ── On install ────────────────────────────────────────────────────────────────

chrome.runtime.onInstalled.addListener(async () => {
  const stored = await chrome.storage.local.get(["endpoint", "mode"]);
  if (!stored.endpoint) {
    await chrome.storage.local.set({ endpoint: DEFAULT_TILES_ENDPOINT });
  }
  if (!stored.mode) {
    await chrome.storage.local.set({ mode: DEFAULT_MODE });
  }
  console.log(
    "[Tab Scraper → Java] service worker installed. Backend:",
    DEFAULT_TILES_ENDPOINT,
  );
});
