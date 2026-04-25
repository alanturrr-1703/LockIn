// =============================================================================
// Tab Stream — background.js (Service Worker, Manifest V3)
// =============================================================================

'use strict';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const API = {
  REST:    'http://localhost:8081/api/scrape',
  WS:      'ws://localhost:8081/ws/stream',
  HEALTH:  'http://localhost:8081/api/health',
  HISTORY: 'http://localhost:8081/api/history',
};

const ALARM_NAME               = 'tab-stream-live';
const MUTATION_POLL_INTERVAL_MS = 1500;
const WS_RECONNECT_DELAY_MS    = 3000;

// ---------------------------------------------------------------------------
// WebSocket state
// ---------------------------------------------------------------------------

/** @type {WebSocket|null} */
let ws                = null;
let wsReconnectEnabled = false;

// ---------------------------------------------------------------------------
// WebSocket helpers
// ---------------------------------------------------------------------------

/**
 * Open a WebSocket connection to the backend.
 * Resolves when the socket is OPEN; rejects on the first error before open.
 * @returns {Promise<void>}
 */
function connectWS() {
  return new Promise((resolve, reject) => {
    console.log('[Tab Stream] WS connecting to', API.WS);

    // Close any stale socket first
    if (ws && ws.readyState !== WebSocket.CLOSED) {
      ws.close();
    }

    wsReconnectEnabled = true;
    ws = new WebSocket(API.WS);

    ws.onopen = () => {
      console.log('[Tab Stream] WS connection opened');
      resolve();
    };

    ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        console.log('[Tab Stream] WS message received — type:', msg.type, '| payload:', msg.payload ?? msg);
      } catch (_) {
        console.log('[Tab Stream] WS raw message:', event.data);
      }
    };

    ws.onerror = (err) => {
      console.error('[Tab Stream] WS error:', err);
      reject(new Error('WebSocket error — see console for details'));
    };

    ws.onclose = (event) => {
      console.log(`[Tab Stream] WS closed — code: ${event.code}, reason: "${event.reason}"`);
      if (wsReconnectEnabled) {
        console.log(`[Tab Stream] WS scheduling reconnect in ${WS_RECONNECT_DELAY_MS}ms`);
        setTimeout(() => {
          if (wsReconnectEnabled) {
            connectWS().catch((e) =>
              console.error('[Tab Stream] WS reconnect failed:', e.message)
            );
          }
        }, WS_RECONNECT_DELAY_MS);
      }
    };
  });
}

/**
 * Gracefully close the WebSocket and disable auto-reconnect.
 */
function disconnectWS() {
  console.log('[Tab Stream] WS disconnecting');
  wsReconnectEnabled = false;
  if (ws) {
    ws.close();
    ws = null;
  }
}

/**
 * Send a PAGE_DATA frame over the open WebSocket.
 * @param {object} data
 */
function sendWS(data) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    const frame = JSON.stringify({ type: 'PAGE_DATA', payload: data });
    ws.send(frame);
    console.log('[Tab Stream] WS frame sent — url:', data.url);
  } else {
    throw new Error(
      `WebSocket is not open (readyState=${ws ? ws.readyState : 'null'})`
    );
  }
}

/**
 * Returns the current WebSocket readyState, or -1 if no socket exists.
 * @returns {number}
 */
function getWSState() {
  return ws?.readyState ?? -1;
}

// ---------------------------------------------------------------------------
// Injected functions (run inside the tab via chrome.scripting.executeScript)
// ---------------------------------------------------------------------------

/**
 * Capture key page data. Executed in the tab's context.
 * @returns {{title:string, url:string, html:string, text:string, timestamp:string}}
 */
function capturePageData() {
  return {
    title:     document.title || '',
    url:       window.location.href || '',
    html:      document.documentElement.outerHTML || '',
    text:      document.body?.innerText || '',
    timestamp: new Date().toISOString(),
  };
}

/**
 * Install a MutationObserver that sets window.__tabStreamChanged when the DOM
 * changes. Debounced to 600 ms. Idempotent — safe to call multiple times.
 * @returns {{installed:boolean}|{alreadyInstalled:boolean}}
 */
function installMutationObserver() {
  if (window.__tabStreamObserver) {
    return { alreadyInstalled: true };
  }

  let debounceTimer = null;

  window.__tabStreamObserver = new MutationObserver(() => {
    if (debounceTimer) clearTimeout(debounceTimer);
    debounceTimer = setTimeout(() => {
      window.__tabStreamChanged = true;
    }, 600);
  });

  window.__tabStreamObserver.observe(document.body, {
    childList:     true,
    subtree:       true,
    attributes:    true,
    characterData: true,
  });

  return { installed: true };
}

/**
 * Atomically read-and-reset the mutation flag.
 * @returns {boolean}
 */
function checkMutationFlag() {
  const changed = window.__tabStreamChanged || false;
  window.__tabStreamChanged = false;
  return changed;
}

// ---------------------------------------------------------------------------
// Core scrape helpers
// ---------------------------------------------------------------------------

/**
 * Execute capturePageData inside the given tab and return the result.
 * @param {number} tabId
 * @returns {Promise<object>}
 */
async function captureTab(tabId) {
  const results = await chrome.scripting.executeScript({
    target: { tabId },
    func:   capturePageData,
  });
  if (!results || results.length === 0) {
    throw new Error('capturePageData returned no results');
  }
  const result = results[0].result;
  console.log(
    `[Tab Stream] Captured tab ${tabId} — "${result.title}" (${result.text.length} chars)`
  );
  return result;
}

/**
 * POST page data to the REST endpoint.
 * @param {object} data
 * @returns {Promise<object>}
 */
async function sendREST(data) {
  const response = await fetch(API.REST, {
    method:  'POST',
    headers: { 'Content-Type': 'application/json' },
    body:    JSON.stringify(data),
  });

  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new Error(`REST ${response.status} ${response.statusText}: ${text}`);
  }

  const json = await response.json();
  console.log('[Tab Stream] REST response:', json);
  return json;
}

/**
 * Send page data through the WebSocket channel.
 * @param {object} data
 * @returns {Promise<{ok:boolean, mode:string}>}
 */
async function sendViaWS(data) {
  sendWS(data);
  return { ok: true, mode: 'websocket' };
}

/**
 * Capture the tab and dispatch the payload using the selected mode.
 * @param {number} tabId
 * @param {'rest'|'websocket'} mode
 * @returns {Promise<object>}
 */
async function scrapeAndSend(tabId, mode) {
  const data = await captureTab(tabId);

  if (mode === 'websocket') {
    return sendViaWS(data);
  }
  return sendREST(data);
}

// ---------------------------------------------------------------------------
// Active tab resolver
// ---------------------------------------------------------------------------

/**
 * Return the currently active tab, throwing if it is a privileged page.
 * @returns {Promise<chrome.tabs.Tab>}
 */
async function getActiveTab() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab) throw new Error('No active tab found');

  const url = tab.url || '';
  if (url.startsWith('chrome://') || url.startsWith('edge://') || url.startsWith('about:')) {
    throw new Error('Cannot scrape this page type');
  }

  return tab;
}

// ---------------------------------------------------------------------------
// Live stream (alarm-based — survives popup close)
// ---------------------------------------------------------------------------

/**
 * @typedef {{ tabId:number, mode:'rest'|'websocket', intervalMs:number, useMutation:boolean }} StreamConfig
 */

/**
 * Start the live stream alarm.
 * @param {{ tabId:number, mode:string, intervalMs:number, useMutation:boolean }} opts
 */
async function startStream(opts) {
  const { tabId, mode, intervalMs, useMutation } = opts;
  console.log('[Tab Stream] Starting live stream', opts);

  await chrome.storage.local.set({
    streamConfig: { tabId, mode, intervalMs, useMutation },
    streaming:    true,
  });

  if (mode === 'websocket') {
    await connectWS();
  }

  if (useMutation) {
    await chrome.scripting.executeScript({
      target: { tabId },
      func:   installMutationObserver,
    }).catch((e) =>
      console.warn('[Tab Stream] MutationObserver injection warning:', e.message)
    );
  }

  const periodInMinutes = Math.max(0.1, intervalMs / 60_000);
  chrome.alarms.create(ALARM_NAME, { periodInMinutes });
  console.log(`[Tab Stream] Alarm "${ALARM_NAME}" created — period: ${periodInMinutes.toFixed(3)} min`);

  // Immediate first capture
  await scrapeAndSend(tabId, mode).catch((e) =>
    console.error('[Tab Stream] Initial stream scrape failed:', e.message)
  );
}

/**
 * Stop the live stream alarm and clean up.
 */
async function stopStream() {
  console.log('[Tab Stream] Stopping live stream');
  chrome.alarms.clear(ALARM_NAME);

  const { streamConfig } = await chrome.storage.local.get('streamConfig');
  if (streamConfig?.mode === 'websocket') {
    disconnectWS();
  }

  await chrome.storage.local.set({ streaming: false });
}

// ---------------------------------------------------------------------------
// Alarm listener (core of the live stream loop)
// ---------------------------------------------------------------------------

chrome.alarms.onAlarm.addListener(async (alarm) => {
  if (alarm.name !== ALARM_NAME) return;

  const { streamConfig } = await chrome.storage.local.get('streamConfig');
  if (!streamConfig) {
    console.warn('[Tab Stream] Alarm fired but no streamConfig in storage — clearing');
    chrome.alarms.clear(ALARM_NAME);
    return;
  }

  const { tabId, mode, useMutation } = streamConfig;

  try {
    // If using MutationObserver, skip the send unless the DOM actually changed
    if (useMutation) {
      const flagResults = await chrome.scripting.executeScript({
        target: { tabId },
        func:   checkMutationFlag,
      }).catch(() => null);

      const changed = flagResults?.[0]?.result ?? true; // default true if inject fails
      if (!changed) {
        console.log('[Tab Stream] MutationObserver: no DOM change since last tick — skipping');
        return;
      }
    }

    // Try to scrape the configured tab; fall back to the active tab
    const tabs = await chrome.tabs.query({});
    const targetTab = tabs.find((t) => t.id === tabId) ?? (await getActiveTab().catch(() => null));

    if (!targetTab) {
      console.warn('[Tab Stream] Alarm tick: target tab no longer available');
      return;
    }

    await scrapeAndSend(targetTab.id, mode);
  } catch (e) {
    console.error('[Tab Stream] Alarm tick error:', e.message);
  }
});

// ---------------------------------------------------------------------------
// Message listener
// ---------------------------------------------------------------------------

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  const { command } = message;
  console.log('[Tab Stream] Message received:', command, message);

  // All handlers are async; we return `true` to keep the channel open.
  (async () => {
    try {
      switch (command) {
        // ----------------------------------------------------------------
        case 'SEND_ONCE': {
          const tab    = await getActiveTab();
          const result = await scrapeAndSend(tab.id, message.mode || 'rest');
          sendResponse({ ok: true, result });
          break;
        }

        // ----------------------------------------------------------------
        case 'START_STREAM': {
          const tab = await getActiveTab();
          await startStream({
            tabId:       tab.id,
            mode:        message.mode       || 'rest',
            intervalMs:  message.intervalMs || 5000,
            useMutation: message.useMutation || false,
          });
          sendResponse({ ok: true });
          break;
        }

        // ----------------------------------------------------------------
        case 'STOP_STREAM': {
          await stopStream();
          sendResponse({ ok: true });
          break;
        }

        // ----------------------------------------------------------------
        case 'WS_CONNECT': {
          await connectWS();
          sendResponse({ ok: true, state: getWSState() });
          break;
        }

        // ----------------------------------------------------------------
        case 'WS_DISCONNECT': {
          disconnectWS();
          sendResponse({ ok: true });
          break;
        }

        // ----------------------------------------------------------------
        case 'WS_STATUS': {
          sendResponse({ ok: true, state: getWSState() });
          break;
        }

        // ----------------------------------------------------------------
        case 'HEALTH_CHECK': {
          const res = await fetch(API.HEALTH);
          if (!res.ok) throw new Error(`Health endpoint returned ${res.status}`);
          const data = await res.json();
          sendResponse({ ok: true, data });
          break;
        }

        // ----------------------------------------------------------------
        case 'GET_HISTORY': {
          const res = await fetch(API.HISTORY);
          if (!res.ok) throw new Error(`History endpoint returned ${res.status}`);
          const data = await res.json();
          sendResponse({ ok: true, data });
          break;
        }

        // ----------------------------------------------------------------
        default:
          sendResponse({ ok: false, error: `Unknown command: ${command}` });
      }
    } catch (e) {
      console.error(`[Tab Stream] Command "${command}" failed:`, e.message);
      sendResponse({ ok: false, error: e.message });
    }
  })();

  return true; // keep the message channel open for async response
});

// ---------------------------------------------------------------------------
// Extension install — set default storage values
// ---------------------------------------------------------------------------

chrome.runtime.onInstalled.addListener(({ reason }) => {
  console.log('[Tab Stream] onInstalled — reason:', reason);
  chrome.storage.local.set({
    mode:        'rest',
    intervalMs:  5000,
    useMutation: false,
    streaming:   false,
  });
});
