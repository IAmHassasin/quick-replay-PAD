/**
 * Quick Replay service worker: owns the recording/playback state, script storage and the
 * message API used by the popup (and by the end-to-end tests through `globalThis.QuickReplay`).
 */
import { FORMAT, normalizeScript, validateScript } from './lib/script.js';
import { Player } from './player.js';
import * as store from './store.js';

/** A single recorded delay is capped so long idle pauses do not end up in the script. */
const MAX_RECORDED_DELAY_MS = 10000;
const DRAFT_KEY = 'qr:recordingDraft';
const DRAFT_SAVE_DEBOUNCE_MS = 500;
const RECORDABLE_URL = /^(https?|file):/i;

/** Public state, sent to the popup. */
const state = {
  /** @type {'idle' | 'recording' | 'playing'} */
  mode: 'idle',
  /** @type {null | {tabId: number, startUrl: string, startedAt: string, stepCount: number, injections: number}} */
  recording: null,
  /** @type {null | {tabId: number, name: string, loop: number, repeat: number, step: number, stepCount: number, stepType: string}} */
  playback: null,
  /** @type {null | {kind: 'recording' | 'playback', status: string, name: string, at: string}} */
  lastResult: null,
  /** @type {string | null} */
  lastError: null,
  /** @type {string | null} */
  lastWarning: null,
};

/** Recording in progress (persisted so a service worker restart does not lose it). */
let draft = null;
let draftTimer = null;
/** @type {Player | null} */
let player = null;
let playerRun = null;

const ready = restoreDraft();

// ------------------------------------------------------------------ state & broadcast

function broadcast() {
  chrome.runtime.sendMessage({ type: 'qr:state', state }).catch((err) => {
    // No popup open is the normal case; anything else is worth logging.
    if (!String(err?.message).includes('Receiving end does not exist')) {
      console.warn('[Quick Replay] broadcast failed:', err?.message ?? err);
    }
  });
}

function setError(message) {
  state.lastError = message;
  console.error('[Quick Replay]', message);
  broadcast();
}

// ------------------------------------------------------------------ recording

async function restoreDraft() {
  const { [DRAFT_KEY]: saved } = await chrome.storage.local.get(DRAFT_KEY);
  if (!saved) return;
  draft = saved;
  state.mode = 'recording';
  state.recording = recordingInfo();
}

function recordingInfo() {
  return {
    tabId: draft.tabId,
    startUrl: draft.startUrl,
    startedAt: draft.startedAt,
    stepCount: draft.steps.length,
    injections: draft.injections,
  };
}

function persistDraftSoon() {
  clearTimeout(draftTimer);
  draftTimer = setTimeout(() => {
    if (!draft) return;
    chrome.storage.local.set({ [DRAFT_KEY]: draft }).catch((err) => setError(`Could not save recording draft: ${err.message}`));
  }, DRAFT_SAVE_DEBOUNCE_MS);
}

function appendStep(step, ts) {
  const elapsed = Math.max(0, ts - draft.lastTs);
  draft.lastTs = Math.max(draft.lastTs, ts);
  const delayBeforeMs = Math.min(Math.round(elapsed), MAX_RECORDED_DELAY_MS);
  draft.steps.push(delayBeforeMs > 0 ? { ...step, delayBeforeMs } : { ...step });
  state.recording = recordingInfo();
  persistDraftSoon();
  broadcast();
}

async function injectRecorder(tabId) {
  await chrome.scripting.executeScript({ target: { tabId, frameIds: [0] }, files: ['recorder.js'] });
  if (draft && draft.tabId === tabId) {
    draft.injections += 1;
    state.recording = recordingInfo();
  }
}

async function activeTabId() {
  const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
  if (!tab?.id) throw new Error('No active tab');
  return tab.id;
}

async function startRecording({ tabId, name }) {
  if (state.mode !== 'idle') throw new Error(`Cannot start recording while ${state.mode}`);
  const id = tabId ?? (await activeTabId());
  const tab = await chrome.tabs.get(id);
  if (!RECORDABLE_URL.test(tab.url ?? '')) {
    throw new Error(`This page cannot be recorded (${tab.url || 'unknown URL'}). Open a http(s) or file page.`);
  }
  const now = Date.now();
  draft = {
    tabId: id,
    name: typeof name === 'string' ? name.trim() : '',
    startUrl: tab.url,
    lastUrl: tab.url,
    startedAt: new Date(now).toISOString(),
    lastTs: now,
    injections: 0,
    steps: [],
  };
  state.mode = 'recording';
  state.recording = recordingInfo();
  state.lastError = null;
  state.lastWarning = null;
  try {
    await injectRecorder(id);
  } catch (err) {
    draft = null;
    state.mode = 'idle';
    state.recording = null;
    throw new Error(`Could not start the recorder in this tab: ${err.message}`);
  }
  await chrome.storage.local.set({ [DRAFT_KEY]: draft });
  broadcast();
  return { recording: state.recording };
}

async function stopRecording({ name }) {
  if (state.mode !== 'recording' || !draft) throw new Error('Not recording');
  try {
    const res = await chrome.tabs.sendMessage(draft.tabId, { type: 'qr:recorderStop' }, { frameId: 0 });
    for (const { step, ts } of res?.pending ?? []) appendStep(step, ts);
  } catch (err) {
    // The tab may be closed or showing a page where the recorder could not run.
    state.lastWarning = `Recorder in the tab did not answer (${err.message}); saved what was recorded.`;
  }
  clearTimeout(draftTimer);
  const finished = draft;
  draft = null;
  state.mode = 'idle';
  state.recording = null;
  await chrome.storage.local.remove(DRAFT_KEY);

  const scriptName = (typeof name === 'string' && name.trim()) || finished.name
    || `Recording ${finished.startedAt.slice(0, 19).replace('T', ' ')}`;
  const script = {
    format: FORMAT,
    name: scriptName,
    platform: 'browser',
    repeat: 1,
    loopDelayMs: 0,
    startUrl: finished.startUrl,
    createdAt: finished.startedAt,
    steps: finished.steps,
  };
  let meta = null;
  if (script.steps.length === 0) {
    state.lastWarning = 'Nothing was recorded, so no script was saved.';
  } else {
    const errors = validateScript(script, { platform: 'browser' });
    if (errors.length) throw new Error(`Recorded script is invalid: ${errors.join('; ')}`);
    meta = await store.saveScript(script);
  }
  state.lastResult = { kind: 'recording', status: meta ? 'saved' : 'empty', name: scriptName, at: new Date().toISOString() };
  broadcast();
  return { script, meta };
}

chrome.tabs.onUpdated.addListener((tabId, changeInfo) => {
  ready.then(async () => {
    if (!draft || tabId !== draft.tabId) return;
    if (changeInfo.url && changeInfo.url !== draft.lastUrl) {
      draft.lastUrl = changeInfo.url;
      if (RECORDABLE_URL.test(changeInfo.url)) appendStep({ type: 'navigate', url: changeInfo.url }, Date.now());
    }
    if (changeInfo.status === 'complete') {
      try {
        const tab = await chrome.tabs.get(tabId);
        if (!RECORDABLE_URL.test(tab.url ?? '')) return;
        await injectRecorder(tabId);
        broadcast();
      } catch (err) {
        state.lastWarning = `Recorder could not be injected after navigation: ${err.message}`;
        broadcast();
      }
    }
  });
});

// ------------------------------------------------------------------ playback

async function play({ scriptId, script: inline, tabId, repeat, loopDelayMs }) {
  if (state.mode !== 'idle') throw new Error(`Cannot play while ${state.mode}`);
  const raw = inline ?? (await store.getScript(scriptId));
  const errors = validateScript(raw, { platform: 'browser' });
  if (errors.length) throw new Error(`Invalid script: ${errors.join('; ')}`);
  const script = normalizeScript(raw);
  if (repeat !== undefined && !(Number.isInteger(repeat) && repeat >= 0)) throw new Error('repeat must be an integer >= 0');
  if (loopDelayMs !== undefined && !(Number.isInteger(loopDelayMs) && loopDelayMs >= 0)) {
    throw new Error('loopDelayMs must be an integer >= 0');
  }
  const id = tabId ?? (await activeTabId());

  const current = new Player(id, script, {
    repeat,
    loopDelayMs,
    onProgress: (p) => {
      state.playback = { tabId: id, name: script.name, ...p };
      broadcast();
    },
    onWarning: (message) => {
      state.lastWarning = message;
      broadcast();
    },
  });
  player = current;
  state.mode = 'playing';
  state.playback = {
    tabId: id, name: script.name, loop: 0, repeat: current.repeat, step: 0, stepCount: script.steps.length, stepType: '',
  };
  state.lastError = null;
  state.lastWarning = null;
  broadcast();

  playerRun = current.run().then(
    (status) => finishPlayback(script.name, status, null),
    (err) => finishPlayback(script.name, 'failed', err),
  );
  return { playback: state.playback };
}

function finishPlayback(name, status, err) {
  player = null;
  playerRun = null;
  state.mode = 'idle';
  state.playback = null;
  state.lastResult = { kind: 'playback', status, name, at: new Date().toISOString() };
  if (err) setError(err.message ?? String(err));
  else broadcast();
}

async function stopPlayback() {
  if (!player) throw new Error('Not playing');
  const run = playerRun;
  player.stop();
  await run;
  return { lastResult: state.lastResult };
}

chrome.debugger.onEvent.addListener((source, method) => {
  if (player && source.tabId === player.tabId) player.handleDebuggerEvent(method);
});

chrome.debugger.onDetach.addListener((source, reason) => {
  if (player && source.tabId === player.tabId) player.handleDetach(reason);
});

// ------------------------------------------------------------------ scripts

async function importScript({ script, text }) {
  let obj = script;
  if (typeof text === 'string') {
    try {
      obj = JSON.parse(text);
    } catch (err) {
      throw new Error(`Invalid JSON: ${err.message}`);
    }
  }
  const errors = validateScript(obj, { platform: 'browser' });
  if (errors.length) throw new Error(`Invalid script: ${errors.join('; ')}`);
  const meta = await store.saveScript(normalizeScript(obj));
  return { meta };
}

// ------------------------------------------------------------------ message API

const handlers = {
  'qr:getState': async () => ({ state }),
  'qr:startRecording': startRecording,
  'qr:stopRecording': stopRecording,
  'qr:play': play,
  'qr:stopPlayback': stopPlayback,
  'qr:listScripts': async () => ({ scripts: await store.listScripts() }),
  'qr:getScript': async ({ id }) => ({ script: await store.getScript(id) }),
  'qr:renameScript': async ({ id, name }) => ({ meta: await store.renameScript(id, name) }),
  'qr:deleteScript': async ({ id }) => {
    await store.deleteScript(id);
    return {};
  },
  'qr:importScript': importScript,
  'qr:clearMessages': async () => {
    state.lastError = null;
    state.lastWarning = null;
    broadcast();
    return { state };
  },
};

/**
 * Handles one API message.
 * @param {{type: string}} message
 * @returns {Promise<{ok: true} & object | {ok: false, error: string}>}
 */
async function handleMessage(message) {
  await ready;
  const handler = Object.hasOwn(handlers, message?.type) ? handlers[message.type] : null;
  if (!handler) return { ok: false, error: `Unknown message type: ${message?.type}` };
  try {
    return { ok: true, ...(await handler(message)) };
  } catch (err) {
    return { ok: false, error: err?.message ?? String(err) };
  }
}

const extensionOrigin = chrome.runtime.getURL('');

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (sender.id !== chrome.runtime.id || typeof message?.type !== 'string') return false;
  if (message.type === 'qr:step') {
    // Steps come from the recorder content script in the top frame of the recorded tab.
    ready.then(() => {
      if (draft && sender.tab?.id === draft.tabId && sender.frameId === 0 && message.step) {
        appendStep(message.step, Number(message.ts) || Date.now());
      }
    });
    sendResponse({ ok: true });
    return false;
  }
  // Everything else is the privileged API, only for extension pages (popup).
  if (!sender.url?.startsWith(extensionOrigin)) return false;
  handleMessage(message).then(sendResponse);
  return true;
});

/** Debug / test hook: `await QuickReplay.handle({ type: 'qr:getState' })` from the service worker console. */
globalThis.QuickReplay = { handle: handleMessage };
