// ─────────────────────────────────────────────────────────────────────────────
// YT Tile Scraper — background.js (Manifest V3 service worker)
//
// Responsibilities:
//   1. One-shot scrape on demand  (cmd: "scrape")
//   2. Recurring watch mode       (cmd: "watch-start" / "watch-stop")
//   3. Auto-scroll before scraping so YouTube lazy-loads more tiles
//   4. POST tile batches to the local Java receiver (default: localhost:8765)
//   5. Persist last result to chrome.storage.local for the popup to read
// ─────────────────────────────────────────────────────────────────────────────

const ALARM_NAME         = "yt-scrape-watch";
const DEFAULT_ENDPOINT   = "http://localhost:8765/tiles";
const DEFAULT_MODE       = "lookahead";
const DEFAULT_INTERVAL   = 0.05; // minutes (~3 seconds)

// ─── Scrape function (injected into the page via scripting.executeScript) ────
// Must be a pure, self-contained function — no closures over outer variables.

function scrapeFn({ mode, lookahead }) {
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

  const TITLE_SELECTORS = [
    "#video-title-link",
    "a#video-title",
    "#video-title",
    "h3 a#video-title-link",
    "h3 #video-title",
    "yt-formatted-string#video-title",
    "a.yt-lockup-metadata-view-model-wiz__title",
    ".yt-lockup-metadata-view-model-wiz__title span",
    "h3 .yt-core-attributed-string",
  ];

  const CHANNEL_SELECTORS = [
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

  const DESC_SELECTORS = [
    "#description-text",
    "yt-formatted-string#description-text",
    ".metadata-snippet-text",
    "#description",
  ];

  // Returns the first non-empty text match from a list of CSS selectors
  const firstText = (el, selectors) => {
    for (const sel of selectors) {
      const node = el.querySelector(sel);
      if (!node) continue;
      const raw =
        (node.getAttribute && node.getAttribute("title")) ||
        node.textContent ||
        "";
      const cleaned = raw.replace(/\s+/g, " ").trim();
      if (cleaned) return cleaned;
    }
    return "";
  };

  // Returns the first resolvable href from known anchor patterns
  const firstHref = (el) => {
    const anchor = el.querySelector(
      [
        "a#thumbnail[href]",
        "a#video-title-link[href]",
        "a#video-title[href]",
        "a.yt-lockup-metadata-view-model-wiz__title[href]",
        "a[href*='/watch?v=']",
        "a[href*='/shorts/']",
      ].join(", ")
    );
    if (!anchor) return "";
    const href = anchor.getAttribute("href") || "";
    if (!href) return "";
    try {
      return new URL(href, location.origin).toString();
    } catch (_) {
      return href;
    }
  };

  // Extracts the YouTube video ID from a URL string
  const videoIdFromUrl = (url) => {
    if (!url) return "";
    try {
      const u = new URL(url);
      const v = u.searchParams.get("v");
      if (v) return v;
      const shorts = u.pathname.match(/\/shorts\/([^/?#]+)/);
      if (shorts) return shorts[1];
    } catch (_) {}
    return "";
  };

  // Returns thumbnail src from common img selectors
  const firstThumb = (el) => {
    const img = el.querySelector("img#img, img.yt-core-image, img");
    if (!img) return "";
    return img.getAttribute("src") || img.getAttribute("data-thumb") || "";
  };

  const vpH     = window.innerHeight || document.documentElement.clientHeight;
  const ts      = new Date().toISOString();
  const seenEls = new Set();
  const seenIds = new Set();
  const out     = [];

  const counts = {
    viewport_h   : vpH,
    mode,
    lookahead,
    page_url     : location.href,
    by_selector  : {},
    kept         : 0,
    fallback_used: false,
  };

  // Viewport / lookahead filter
  const passesViewport = (rect) => {
    if (rect.width === 0 && rect.height === 0) return false;
    if (mode === "strict")    return rect.bottom > 0 && rect.top < vpH;
    if (mode === "lookahead") return rect.bottom > 0 && rect.top < vpH + lookahead;
    return true; // "all"
  };

  // ── Primary pass: structured tile selectors ─────────────────────────────
  for (const sel of TILE_SELECTORS) {
    const nodes = document.querySelectorAll(sel);
    counts.by_selector[sel] = nodes.length;

    for (const el of nodes) {
      if (seenEls.has(el)) continue;
      seenEls.add(el);

      if (!passesViewport(el.getBoundingClientRect())) continue;

      const title   = firstText(el, TITLE_SELECTORS);
      const channel = firstText(el, CHANNEL_SELECTORS);
      if (!title && !channel) continue;

      const url = firstHref(el);
      const vid = videoIdFromUrl(url);

      if (vid) {
        if (seenIds.has(vid)) continue;
        seenIds.add(vid);
      }

      out.push({
        video_id     : vid,
        title,
        channel,
        description  : firstText(el, DESC_SELECTORS),
        thumbnail_url: firstThumb(el),
        url,
        tile_type    : sel,
        scraped_at   : ts,
        page_url     : location.href,
      });
    }
  }

  // ── Fallback pass: raw anchor scan if no tiles found ────────────────────
  if (out.length === 0) {
    counts.fallback_used = true;

    const links = document.querySelectorAll(
      "a[href*='/watch?v='], a[href*='/shorts/']"
    );
    counts.fallback_links = links.length;

    for (const anchor of links) {
      let url = "";
      try {
        url = new URL(anchor.getAttribute("href"), location.origin).toString();
      } catch (_) {
        url = anchor.href || "";
      }

      const vid = videoIdFromUrl(url);
      if (!vid || seenIds.has(vid)) continue;

      // Walk up the DOM tree a few levels to find the containing card element
      let card = anchor;
      for (let i = 0; i < 6 && card && card !== document.body; i++) {
        card = card.parentElement;
      }
      card = card || anchor.parentElement || anchor;

      if (!passesViewport(card.getBoundingClientRect())) continue;

      const title   = (anchor.getAttribute("title") || anchor.textContent || "")
                        .replace(/\s+/g, " ")
                        .trim();
      const channel = firstText(card, CHANNEL_SELECTORS);
      if (!title && !channel) continue;

      seenIds.add(vid);
      out.push({
        video_id     : vid,
        title,
        channel,
        description  : firstText(card, DESC_SELECTORS),
        thumbnail_url: firstThumb(card),
        url,
        tile_type    : "fallback:link",
        scraped_at   : ts,
        page_url     : location.href,
      });
    }
  }

  counts.kept = out.length;

  try {
    console.log("[YT Tile Scraper] scrape counts:", counts);
  } catch (_) {}

  return { tiles: out, counts };
}

// ─── Auto-scroll function (injected into the page) ───────────────────────────
// Scrolls down in incremental steps so YouTube lazy-loads tiles below the fold.

function autoScrollFn({ steps, stepPx, delayMs }) {
  return new Promise((resolve) => {
    let i = 0;
    const step = () => {
      if (i >= steps) { resolve(); return; }
      window.scrollBy({ top: stepPx, behavior: "smooth" });
      i++;
      setTimeout(step, delayMs);
    };
    step();
  });
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

async function getYouTubeTab() {
  const allTabs = await chrome.tabs.query({});
  const ytTabs  = allTabs.filter((t) => t.url && /youtube\.com/.test(t.url));
  if (ytTabs.length === 0) return null;

  // Prefer the currently active YouTube tab
  const active = ytTabs.find((t) => t.active);
  if (active) return active;

  // Otherwise pick the most recently accessed one
  ytTabs.sort((a, b) => (b.lastAccessed || 0) - (a.lastAccessed || 0));
  return ytTabs[0];
}

// ─── Core scrape-and-send pipeline ───────────────────────────────────────────

async function scrapeAndSend() {
  const { endpoint = DEFAULT_ENDPOINT, mode = DEFAULT_MODE } =
    await chrome.storage.local.get(["endpoint", "mode"]);

  const tab = await getYouTubeTab();
  if (!tab) {
    return { ok: false, reason: "No YouTube tab is currently open." };
  }

  // Auto-scroll to trigger YouTube's lazy-loading before we scrape
  try {
    await chrome.scripting.executeScript({
      target: { tabId: tab.id },
      func  : autoScrollFn,
      args  : [{ steps: 6, stepPx: 1400, delayMs: 600 }],
    });
    // Give the last batch of lazy-loaded tiles time to render
    await new Promise((r) => setTimeout(r, 800));
  } catch (_) {
    // Non-fatal — fall through and scrape whatever is visible
  }

  // Execute the scraper in the page context
  let scrapeResult = { tiles: [], counts: {} };
  try {
    const [{ result }] = await chrome.scripting.executeScript({
      target: { tabId: tab.id },
      func  : scrapeFn,
      args  : [{ mode: "all", lookahead: 3000 }],
    });
    scrapeResult = result || { tiles: [], counts: {} };
  } catch (e) {
    return { ok: false, reason: "Scrape script failed: " + e.message };
  }

  const tiles  = scrapeResult.tiles  || [];
  const counts = scrapeResult.counts || {};

  if (tiles.length === 0) {
    const selectorBreakdown = Object.entries(counts.by_selector || {})
      .filter(([, n]) => n > 0)
      .map(([k, v]) => `${k}:${v}`)
      .join(", ");
    const fallbackNote = counts.fallback_used
      ? ` | fallback_links=${counts.fallback_links || 0}`
      : "";
    return {
      ok    : true,
      sent  : 0,
      accepted: 0,
      counts,
      reason: `0 tiles scraped. selectors=[${selectorBreakdown || "none matched"}]${fallbackNote}` +
              ` | mode=${counts.mode} | viewport_h=${counts.viewport_h}` +
              ` | page=${counts.page_url}`,
    };
  }

  // POST tiles to the Java receiver
  try {
    const response = await fetch(endpoint, {
      method : "POST",
      headers: { "Content-Type": "application/json" },
      body   : JSON.stringify({ tiles, page_url: tab.url }),
    });

    if (!response.ok) {
      return {
        ok    : false,
        reason: `Receiver responded with HTTP ${response.status}.`,
        counts,
      };
    }

    const data     = await response.json().catch(() => ({}));
    const accepted = typeof data.accepted === "number" ? data.accepted : tiles.length;

    return { ok: true, sent: tiles.length, accepted, counts };

  } catch (e) {
    return {
      ok    : false,
      reason: `Receiver unreachable (${e.message}). Is ./gradlew runReceiver running?`,
      counts,
    };
  }
}

// ─── Alarm listener (Watch mode) ─────────────────────────────────────────────

chrome.alarms.onAlarm.addListener(async (alarm) => {
  if (alarm.name !== ALARM_NAME) return;
  const result = await scrapeAndSend();
  await chrome.storage.local.set({ lastResult: result, lastAt: Date.now() });
});

// ─── Message listener (popup commands) ───────────────────────────────────────

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  (async () => {
    if (!msg) {
      sendResponse({ ok: false, reason: "Empty message." });
      return;
    }

    switch (msg.cmd) {

      case "scrape": {
        sendResponse(await scrapeAndSend());
        break;
      }

      case "watch-start": {
        const minutes = Math.max(
          0.05,
          Number(msg.intervalMinutes) || DEFAULT_INTERVAL
        );
        await chrome.alarms.create(ALARM_NAME, { periodInMinutes: minutes });
        const immediate = await scrapeAndSend();
        await chrome.storage.local.set({
          watchOn   : true,
          lastResult: immediate,
          lastAt    : Date.now(),
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

      default:
        sendResponse({ ok: false, reason: `Unknown command: "${msg.cmd}"` });
    }
  })();

  // Return true to keep the message channel open for the async response
  return true;
});
