// =============================================================================
// Tab Stream — popup.js
// =============================================================================

'use strict';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const HISTORY_URL = 'http://localhost:8081/api/history';

const WS_STATE_LABELS = {
  0: 'CONNECTING',
  1: 'OPEN',
  2: 'CLOSING',
  3: 'CLOSED',
};

const WS_STATE_CLASSES = {
  0: 'state-connecting',
  1: 'state-open',
  2: 'state-closing',
  3: 'state-closed',
};

// ---------------------------------------------------------------------------
// DOM references
// ---------------------------------------------------------------------------

const dom = {
  dot:           () => document.getElementById('status-dot'),
  pillRest:      () => document.getElementById('pill-rest'),
  pillWS:        () => document.getElementById('pill-ws'),
  btnSendOnce:   () => document.getElementById('btn-send-once'),
  btnStream:     () => document.getElementById('btn-stream'),
  intervalSelect:() => document.getElementById('interval-select'),
  mutationCheck: () => document.getElementById('mutation-checkbox'),
  wsSection:     () => document.getElementById('ws-section'),
  wsBadge:       () => document.getElementById('ws-badge'),
  statusBar:     () => document.getElementById('status-bar'),
  storedCount:   () => document.getElementById('stored-count'),
  linkHistory:   () => document.getElementById('link-history'),
};

// ---------------------------------------------------------------------------
// Local state
// ---------------------------------------------------------------------------

let currentMode   = 'rest';
let isStreaming   = false;
let streamCount   = 0;
let wsPollTimer   = null;

// ---------------------------------------------------------------------------
// Utility: send a message to background.js
// ---------------------------------------------------------------------------

function sendMessage(payload) {
  return new Promise((resolve, reject) => {
    chrome.runtime.sendMessage(payload, (response) => {
      if (chrome.runtime.lastError) {
        return reject(new Error(chrome.runtime.lastError.message));
      }
      resolve(response);
    });
  });
}

// ---------------------------------------------------------------------------
// Status bar
// ---------------------------------------------------------------------------

/**
 * @param {string} msg
 * @param {'ok'|'error'|'busy'|''} type
 */
function setStatus(msg, type = '') {
  const bar = dom.statusBar();
  bar.textContent = msg;
  bar.className   = type || 'empty';
}

// ---------------------------------------------------------------------------
// Backend dot indicator
// ---------------------------------------------------------------------------

/**
 * @param {'online'|'offline'|'checking'} state
 */
function setDot(state) {
  const dot = dom.dot();
  dot.className = `dot ${state}`;
}

// ---------------------------------------------------------------------------
// Mode selector
// ---------------------------------------------------------------------------

/**
 * @param {'rest'|'websocket'} mode
 */
function setMode(mode) {
  currentMode = mode;

  dom.pillRest().classList.toggle('active', mode === 'rest');
  dom.pillWS().classList.toggle('active',   mode === 'websocket');

  const wsSection = dom.wsSection();
  if (mode === 'websocket') {
    wsSection.style.display = '';
    startWSPolling();
  } else {
    wsSection.style.display = 'none';
    stopWSPolling();
  }
}

// ---------------------------------------------------------------------------
// Settings persistence
// ---------------------------------------------------------------------------

function saveSettings() {
  const settings = {
    mode:        currentMode,
    intervalMs:  parseInt(dom.intervalSelect().value, 10),
    useMutation: dom.mutationCheck().checked,
  };
  chrome.storage.local.set(settings);
  console.log('[Tab Stream] Settings saved:', settings);
}

// ---------------------------------------------------------------------------
// Health check + stored count
// ---------------------------------------------------------------------------

async function refreshHealth() {
  setDot('checking');
  try {
    const res = await sendMessage({ command: 'HEALTH_CHECK' });
    if (res && res.ok) {
      setDot('online');
      // Backend may return { count, stored, total, size } — try common keys
      const data  = res.data || {};
      const count = data.count ?? data.stored ?? data.total ?? data.size ?? '?';
      dom.storedCount().textContent = count;
      console.log('[Tab Stream] Health OK — data:', data);
    } else {
      setDot('offline');
      dom.storedCount().textContent = '—';
      console.warn('[Tab Stream] Health check failed:', res?.error);
    }
  } catch (e) {
    setDot('offline');
    dom.storedCount().textContent = '—';
    console.error('[Tab Stream] Health check error:', e.message);
  }
}

// ---------------------------------------------------------------------------
// WebSocket badge
// ---------------------------------------------------------------------------

/**
 * @param {number} state  WebSocket.readyState (0-3) or -1 for unknown
 */
function updateWSBadge(state) {
  const badge = dom.wsBadge();
  const label = WS_STATE_LABELS[state] ?? 'UNKNOWN';
  const cls   = WS_STATE_CLASSES[state] ?? 'state-unknown';

  badge.textContent = label;
  badge.className   = `ws-badge ${cls}`;
}

// ---------------------------------------------------------------------------
// WebSocket state polling (every 2 s while WS mode is active)
// ---------------------------------------------------------------------------

function startWSPolling() {
  stopWSPolling();
  wsPollTimer = setInterval(async () => {
    try {
      const res = await sendMessage({ command: 'WS_STATUS' });
      if (res?.ok) updateWSBadge(res.state);
    } catch (_) {
      // ignore — popup may be closing
    }
  }, 2000);

  // Immediate first poll
  sendMessage({ command: 'WS_STATUS' })
    .then((res) => { if (res?.ok) updateWSBadge(res.state); })
    .catch(() => {});
}

function stopWSPolling() {
  if (wsPollTimer !== null) {
    clearInterval(wsPollTimer);
    wsPollTimer = null;
  }
}

// ---------------------------------------------------------------------------
// Streaming UI state
// ---------------------------------------------------------------------------

function setStreamingUI(active) {
  isStreaming = active;
  const btn = dom.btnStream();
  if (active) {
    btn.textContent = '■ Stop Streaming';
    btn.classList.add('active');
  } else {
    btn.textContent = '▶ Start Streaming';
    btn.classList.remove('active');
    streamCount = 0;
  }
}

// ---------------------------------------------------------------------------
// Send Once
// ---------------------------------------------------------------------------

async function sendOnce() {
  const btn = dom.btnSendOnce();
  btn.disabled = true;

  // Show spinner
  btn.innerHTML = '<span class="spinner"></span> Sending…';
  setStatus('Sending page data…', 'busy');

  try {
    const res = await sendMessage({ command: 'SEND_ONCE', mode: currentMode });

    if (res && res.ok) {
      const result = res.result || {};
      // Build a summary from whatever the backend echoes back, or from raw data
      const wordCount = estimateWords(result);
      const headings  = result.headings  ?? result.headingCount  ?? '?';
      const links     = result.links     ?? result.linkCount     ?? '?';

      setStatus(
        `✓ Sent · ${wordCount} words · ${headings} headings · ${links} links`,
        'ok'
      );
      console.log('[Tab Stream] SEND_ONCE success:', result);
      // Refresh stored count after successful send
      refreshHealth();
    } else {
      const errMsg = res?.error || 'Unknown error';
      setStatus(`✗ ${errMsg}`, 'error');
      console.error('[Tab Stream] SEND_ONCE failed:', errMsg);
    }
  } catch (e) {
    setStatus(`✗ ${e.message}`, 'error');
    console.error('[Tab Stream] SEND_ONCE exception:', e.message);
  } finally {
    btn.disabled = false;
    btn.innerHTML = '↑ Send Once';
  }
}

/**
 * Attempt to estimate a word count from the backend response.
 * Falls back to '?' if not determinable.
 * @param {object} result
 * @returns {string|number}
 */
function estimateWords(result) {
  if (typeof result.wordCount === 'number') return result.wordCount;
  if (typeof result.words     === 'number') return result.words;
  if (typeof result.text      === 'string') {
    return result.text.trim().split(/\s+/).filter(Boolean).length;
  }
  return '?';
}

// ---------------------------------------------------------------------------
// Toggle stream
// ---------------------------------------------------------------------------

async function toggleStream() {
  const btn = dom.btnStream();
  btn.disabled = true;

  try {
    if (isStreaming) {
      // ── Stop ──────────────────────────────────────────────────────────
      setStatus('Stopping stream…', 'busy');
      const res = await sendMessage({ command: 'STOP_STREAM' });

      if (res && res.ok) {
        setStreamingUI(false);
        setStatus('Stream stopped', 'ok');
        chrome.storage.local.set({ streaming: false });
        console.log('[Tab Stream] Stream stopped');
      } else {
        setStatus(`✗ ${res?.error || 'Failed to stop'}`, 'error');
      }
    } else {
      // ── Start ─────────────────────────────────────────────────────────
      const intervalMs  = parseInt(dom.intervalSelect().value, 10);
      const useMutation = dom.mutationCheck().checked;

      setStatus('Starting stream…', 'busy');

      const res = await sendMessage({
        command:     'START_STREAM',
        mode:        currentMode,
        intervalMs,
        useMutation,
      });

      if (res && res.ok) {
        setStreamingUI(true);
        streamCount = 1;
        setStatus(
          `▶ Streaming (${currentMode}) · sent ${streamCount}`,
          'ok'
        );
        chrome.storage.local.set({ streaming: true });
        console.log('[Tab Stream] Stream started — mode:', currentMode, 'interval:', intervalMs);

        // Keep "sent N" counter incrementing visually each interval
        scheduleStreamCounter(intervalMs);

        if (currentMode === 'websocket') {
          // Poll immediately so badge reflects new connection
          const wsRes = await sendMessage({ command: 'WS_STATUS' }).catch(() => null);
          if (wsRes?.ok) updateWSBadge(wsRes.state);
        }
      } else {
        setStatus(`✗ ${res?.error || 'Failed to start'}`, 'error');
      }
    }
  } catch (e) {
    setStatus(`✗ ${e.message}`, 'error');
    console.error('[Tab Stream] toggleStream exception:', e.message);
  } finally {
    btn.disabled = false;
  }
}

// ---------------------------------------------------------------------------
// Stream counter (visual — popup-side; stops when streaming stops or closed)
// ---------------------------------------------------------------------------

let streamCounterTimer = null;

function scheduleStreamCounter(intervalMs) {
  if (streamCounterTimer) clearInterval(streamCounterTimer);
  streamCounterTimer = setInterval(() => {
    if (!isStreaming) {
      clearInterval(streamCounterTimer);
      streamCounterTimer = null;
      return;
    }
    streamCount += 1;
    setStatus(
      `▶ Streaming (${currentMode}) · sent ${streamCount}`,
      'ok'
    );
  }, intervalMs);
}

// ---------------------------------------------------------------------------
// View History
// ---------------------------------------------------------------------------

function viewHistory() {
  chrome.tabs.create({ url: HISTORY_URL });
  console.log('[Tab Stream] Opened history tab:', HISTORY_URL);
}

// ---------------------------------------------------------------------------
// Restore UI from storage
// ---------------------------------------------------------------------------

async function restoreUI() {
  return new Promise((resolve) => {
    chrome.storage.local.get(
      ['mode', 'intervalMs', 'useMutation', 'streaming'],
      (data) => {
        console.log('[Tab Stream] Restored settings from storage:', data);

        // Mode
        const savedMode = data.mode || 'rest';
        setMode(savedMode);

        // Interval
        const intervalMs = data.intervalMs || 5000;
        const select = dom.intervalSelect();
        const option = select.querySelector(`option[value="${intervalMs}"]`);
        if (option) option.selected = true;

        // Mutation observer
        if (data.useMutation) {
          dom.mutationCheck().checked = true;
        }

        // Streaming state
        if (data.streaming) {
          setStreamingUI(true);
          setStatus(`▶ Streaming (${savedMode}) · resumed`, 'ok');
          // Re-attach visual counter with stored interval
          scheduleStreamCounter(intervalMs);
        }

        resolve();
      }
    );
  });
}

// ---------------------------------------------------------------------------
// Event listeners
// ---------------------------------------------------------------------------

function attachListeners() {
  // Mode pills
  dom.pillRest().addEventListener('click', () => {
    setMode('rest');
    saveSettings();
  });

  dom.pillWS().addEventListener('click', () => {
    setMode('websocket');
    saveSettings();
    sendMessage({ command: 'WS_STATUS' })
      .then((res) => { if (res?.ok) updateWSBadge(res.state); })
      .catch(() => {});
  });

  // Send Once
  dom.btnSendOnce().addEventListener('click', sendOnce);

  // Stream toggle
  dom.btnStream().addEventListener('click', toggleStream);

  // Interval select
  dom.intervalSelect().addEventListener('change', saveSettings);

  // Mutation checkbox
  dom.mutationCheck().addEventListener('change', saveSettings);

  // History link
  dom.linkHistory().addEventListener('click', (e) => {
    e.preventDefault();
    viewHistory();
  });
}

// ---------------------------------------------------------------------------
// Init
// ---------------------------------------------------------------------------

document.addEventListener('DOMContentLoaded', async () => {
  console.log('[Tab Stream] Popup initialising');

  attachListeners();
  await restoreUI();
  await refreshHealth();

  console.log('[Tab Stream] Popup ready — mode:', currentMode, '| streaming:', isStreaming);
});

// Clean up polling when popup is closed
window.addEventListener('unload', () => {
  stopWSPolling();
  if (streamCounterTimer) {
    clearInterval(streamCounterTimer);
    streamCounterTimer = null;
  }
});
