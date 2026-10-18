/**
 * Quick Replay service worker: owns the recording/playback state, script storage and the
 * message API used by the popup (and by the end-to-end tests through `globalThis.QuickReplay`).
 */
import { ORB_LETTERS, cellLabel, formatBoard, parseBoard } from './lib/pad/board.js';
import { normalizeGoals, solve } from './lib/pad/solver.js';
import { FORMAT, normalizeScript, validateScript } from './lib/script.js';
import { Dragger, readBoard } from './pad-runner.js';
import { Player } from './player.js';
import * as store from './store.js';

/** A single recorded delay is capped so long idle pauses do not end up in the script. */
const MAX_RECORDED_DELAY_MS = 10000;
const DRAFT_KEY = 'qr:recordingDraft';
const DRAFT_SAVE_DEBOUNCE_MS = 500;
const RECORDABLE_URL = /^(https?|file):/i;

/** Public state, sent to the popup. */
const state = {
  /** @type {'idle' | 'recording' | 'playing' | 'solving'} */
  mode: 'idle',
  /** @type {null | {tabId: number, phase: string}} */
  pad: null,
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
  if (padDragger && source.tabId === padDragger.target.tabId) padDragger.handleDetach(reason);
});

// ------------------------------------------------------------------ PAD solver (dawnGlare)

const PAD_SETTINGS_KEY = 'qr:padSettings';
const PAD_DEFAULT_SETTINGS = Object.freeze({
  goals: [{ type: 'row', color: 'L' }],
  diagonals: true,
  maxMoves: 40,
  timeLimitMs: 3000,
  msPerMove: 40,
});

/** @type {Dragger | null} */
let padDragger = null;

function intInRange(value, name, min, max) {
  if (!Number.isInteger(value) || value < min || value > max) throw new Error(`${name} must be a whole number from ${min} to ${max}`);
  return value;
}

/** Validates PAD settings from the popup; goals are stored with colour letters. */
function checkPadSettings(input) {
  const s = { ...PAD_DEFAULT_SETTINGS, ...input };
  const goals = normalizeGoals(s.goals).map((g) => (g.type === 'combos' ? g : { type: g.type, color: ORB_LETTERS[g.color] }));
  // Solve & drag must only run for goals the user chose.
  if (goals.length === 0) throw new Error('Add at least one goal');
  return {
    goals,
    diagonals: Boolean(s.diagonals),
    maxMoves: intInRange(s.maxMoves, 'Max moves', 1, 200),
    // The solve runs synchronously in the service worker and blocks its messages meanwhile.
    timeLimitMs: intInRange(s.timeLimitMs, 'Time limit', 100, 10000),
    msPerMove: intInRange(s.msPerMove, 'Drag time per move', 10, 2000),
  };
}

async function padGetSettings() {
  const { [PAD_SETTINGS_KEY]: saved } = await chrome.storage.local.get(PAD_SETTINGS_KEY);
  try {
    return { settings: checkPadSettings(saved ?? {}) };
  } catch {
    return { settings: { ...PAD_DEFAULT_SETTINGS } };
  }
}

async function padSaveSettings({ settings }) {
  const checked = checkPadSettings(settings);
  await chrome.storage.local.set({ [PAD_SETTINGS_KEY]: checked });
  return { settings: checked };
}

function setPadPhase(phase) {
  state.pad = { ...state.pad, phase };
  broadcast();
}

/** Serializable result for the popup. */
function summarizePad(board, result) {
  return {
    status: result.status,
    reasons: result.reasons,
    moves: result.moves,
    path: result.path.map((i) => cellLabel(i, board.cols)),
    goals: result.goals,
    combos: result.combos,
    before: formatBoard(board),
    afterDrag: result.afterDrag ? formatBoard(board, result.afterDrag) : '',
    stats: result.stats,
    dragged: false,
  };
}

/**
 * Reads the dawnGlare board of a tab, solves it and, with `drag`, performs the path when every
 * goal is met.
 */
async function padSolve({ tabId, settings: input, drag }) {
  if (state.mode !== 'idle') throw new Error(`Cannot solve while ${state.mode}`);
  const settings = checkPadSettings(input);
  // Claim the mode before the first await so a second request cannot start in parallel.
  state.mode = 'solving';
  state.pad = { tabId: tabId ?? null, phase: 'reading the board', canStop: false };
  state.lastError = null;
  state.lastWarning = null;
  broadcast();
  let status = 'failed';
  let dragger = null;
  try {
    await chrome.storage.local.set({ [PAD_SETTINGS_KEY]: settings });
    const id = tabId ?? (await activeTabId());
    state.pad.tabId = id;
    if (drag) {
      dragger = new Dragger(id);
      padDragger = dragger;
      await dragger.attach();
    }
    const read = await readBoard(id);
    const board = parseBoard(read.text);
    setPadPhase('solving');
    const result = solve(board, settings.goals, {
      diagonals: settings.diagonals,
      maxMoves: settings.maxMoves,
      timeLimitMs: settings.timeLimitMs,
    });
    const summary = summarizePad(board, result);
    status = result.status;
    if (dragger && result.status === 'solved') {
      // Positions are read again just before dragging (the debugging bar may have moved the
      // page since the first read); the orbs themselves must not have changed.
      await dragger.settled();
      const again = await readBoard(id);
      if (again.text !== read.text) throw new Error('The board changed while solving. Solve again.');
      state.pad = { ...state.pad, phase: `dragging ${result.moves} moves`, canStop: true };
      broadcast();
      try {
        await dragger.drag(again.centers, result.path, settings.msPerMove);
        summary.dragged = true;
        status = 'dragged';
      } catch (err) {
        if (err?.name !== 'DragStopped') throw err;
        summary.stopped = true;
        status = 'stopped';
      }
    }
    return { result: summary };
  } catch (err) {
    if (err?.name !== 'DragStopped') throw err;
    // The debugging bar was dismissed before the drag started.
    status = 'stopped';
    throw new Error('Stopped before the drag started.');
  } finally {
    if (dragger) await dragger.detach();
    padDragger = null;
    state.mode = 'idle';
    state.pad = null;
    state.lastResult = { kind: 'pad', status, name: 'PAD solver', at: new Date().toISOString() };
    broadcast();
  }
}

async function padStop() {
  if (!padDragger || !state.pad?.canStop) throw new Error('Not dragging');
  padDragger.stop();
  return {};
}

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
  'qr:padGetSettings': padGetSettings,
  'qr:padSaveSettings': padSaveSettings,
  'qr:padSolve': padSolve,
  'qr:padStop': padStop,
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
