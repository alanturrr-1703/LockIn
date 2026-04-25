// ── Helpers ───────────────────────────────────────────────────────────────────

const $ = (id) => document.getElementById(id);

function setStatus(msg, cls = "") {
  const el = $("status");
  el.textContent = msg;
  el.className = cls;
}

function setDot(state) {
  const dot = $("health-dot");
  dot.className = "dot";
  if (state === "offline") dot.classList.add("offline");
  if (state === "checking") dot.classList.add("checking");
}

// ── Persist settings ──────────────────────────────────────────────────────────

async function loadSettings() {
  const s = await chrome.storage.local.get([
    "endpoint",
    "mode",
    "watchOn",
    "lastResult",
  ]);

  // Migrate: any stored endpoint that isn't /tiles → force to /tiles
  if (s.endpoint && !s.endpoint.endsWith("/tiles")) {
    s.endpoint = "http://localhost:8080/tiles";
    chrome.storage.local.set({ endpoint: s.endpoint });
  }

  if (s.endpoint) $("endpoint").value = s.endpoint;
  if (s.mode) $("mode").value = s.mode;
  setWatchButton(!!s.watchOn);
  if (s.lastResult && s.lastResult.ok) showStats(s.lastResult);
}

function saveSettings() {
  chrome.storage.local.set({
    endpoint: $("endpoint").value.trim(),
    mode: $("mode").value,
  });
}

// ── Health check ──────────────────────────────────────────────────────────────

// Extract just the origin (scheme + host + port) from any endpoint URL.
// Works regardless of whether the path is /tiles, /scrape, or anything else.
function getOrigin(endpoint) {
  try {
    return new URL(endpoint).origin; // e.g. "http://localhost:8080"
  } catch {
    // Fallback: strip everything after the last slash-segment
    return endpoint.replace(/\/[^/]*$/, "") || endpoint;
  }
}

async function checkHealth(endpoint) {
  const base = getOrigin(endpoint);
  try {
    const resp = await fetch(`${base}/health`, {
      signal: AbortSignal.timeout(3000),
    });
    if (!resp.ok) return { ok: false };
    const data = await resp.json().catch(() => ({}));
    return { ok: true, stored: data.stored ?? "?" };
  } catch {
    return { ok: false };
  }
}

async function refreshHealth() {
  const endpoint = $("endpoint").value.trim() || "http://localhost:8080/tiles";
  setDot("checking");
  const { ok, stored } = await checkHealth(endpoint);
  setDot(ok ? "online" : "offline");
  return { ok, stored };
}

// ── Stats card ────────────────────────────────────────────────────────────────

function showStats(result) {
  $("stat-sent").textContent = result.sent ?? "—";
  $("stat-accepted").textContent = result.accepted ?? "—";
  $("stat-dup").textContent =
    result.duplicate != null
      ? result.duplicate
      : result.sent != null && result.accepted != null
        ? result.sent - result.accepted
        : "—";

  const pageEl = $("stat-page");
  if (result.pageUrl) {
    pageEl.textContent = result.pageUrl;
    pageEl.title = result.pageUrl;
  } else {
    pageEl.textContent = "";
  }

  $("stats-card").classList.add("visible");
}

async function refreshStoredCount() {
  const endpoint = getOrigin($("endpoint").value.trim());
  try {
    const resp = await fetch(`${endpoint}/tiles/export`, {
      signal: AbortSignal.timeout(3000),
    });
    if (resp.ok) {
      const data = await resp.json().catch(() => ({}));
      $("stat-stored").textContent = data.h2Stored ?? "—";
    }
  } catch {
    // silently ignore
  }
}

// ── Watch button ──────────────────────────────────────────────────────────────

function setWatchButton(on) {
  const btn = $("btn-watch");
  if (on) {
    btn.textContent = "⏹ Watch On";
    btn.classList.add("active");
  } else {
    btn.textContent = "🕐 Watch Off";
    btn.classList.remove("active");
  }
  btn.dataset.on = on ? "1" : "";
}

// ── Core scrape ───────────────────────────────────────────────────────────────

async function scrape() {
  const endpoint = $("endpoint").value.trim() || "http://localhost:8080/tiles";
  const mode = $("mode").value || "lookahead";

  $("btn-scrape").disabled = true;
  setStatus("Scraping YouTube tiles…", "busy");

  const result = await chrome.runtime.sendMessage({
    cmd: "scrape",
    endpoint,
    mode,
  });

  $("btn-scrape").disabled = false;

  if (!result) {
    setStatus("No response from background worker.", "error");
    return;
  }

  if (!result.ok) {
    setStatus(result.reason || "Scrape failed.", "error");
    return;
  }

  if (result.sent === 0) {
    setStatus(result.reason || "0 tiles found on this page.", "error");
    return;
  }

  const dup = (result.sent ?? 0) - (result.accepted ?? 0);
  setStatus(
    `✓  Sent ${result.sent}  ·  Accepted ${result.accepted}  ·  Duplicate ${dup}`,
    "ok",
  );

  showStats({ ...result, duplicate: dup });
  await refreshStoredCount();
  chrome.storage.local.set({ lastResult: { ...result, duplicate: dup } });
}

// ── Watch toggle ──────────────────────────────────────────────────────────────

async function toggleWatch() {
  const on = $("btn-watch").dataset.on === "1";
  const endpoint = $("endpoint").value.trim() || "http://localhost:8080/tiles";
  const mode = $("mode").value || "lookahead";

  if (on) {
    const r = await chrome.runtime.sendMessage({ cmd: "watch-stop" });
    setWatchButton(false);
    setStatus(
      r?.ok ? "Watch stopped." : "Failed to stop watch.",
      r?.ok ? "" : "error",
    );
  } else {
    setStatus("Starting watch…", "busy");
    const r = await chrome.runtime.sendMessage({
      cmd: "watch-start",
      endpoint,
      mode,
      intervalMinutes: 2,
    });
    setWatchButton(true);
    if (r?.immediate?.ok) {
      const dup = (r.immediate.sent ?? 0) - (r.immediate.accepted ?? 0);
      setStatus(
        `Watch on  ·  Sent ${r.immediate.sent}  ·  Accepted ${r.immediate.accepted}  ·  Dup ${dup}`,
        "ok",
      );
      showStats({ ...r.immediate, duplicate: dup });
    } else {
      setStatus("Watch on — waiting for next poll…", "ok");
    }
  }
}

// ── View all tiles ────────────────────────────────────────────────────────────

function viewAll() {
  const base = getOrigin($("endpoint").value.trim());
  chrome.tabs.create({ url: `${base}/tiles` });
}

// ── Init ──────────────────────────────────────────────────────────────────────

document.addEventListener("DOMContentLoaded", async () => {
  await loadSettings();

  // Health check
  const { ok, stored } = await refreshHealth();
  if (ok) {
    setStatus(`Backend online · ${stored} general result(s) stored.`, "ok");
    await refreshStoredCount();
  } else {
    setStatus("Backend offline. Run:  ./gradlew bootRun", "error");
  }

  // Event listeners
  $("btn-scrape").addEventListener("click", scrape);
  $("btn-watch").addEventListener("click", toggleWatch);
  $("btn-view").addEventListener("click", viewAll);

  $("endpoint").addEventListener("change", () => {
    saveSettings();
    refreshHealth().then(({ ok }) => setDot(ok ? "online" : "offline"));
  });

  $("mode").addEventListener("change", saveSettings);
});
