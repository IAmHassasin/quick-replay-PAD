/**
 * Quick Replay popup. All work happens in the service worker; the popup only sends
 * messages and renders state. User and script text is always rendered with
 * `textContent` (never `innerHTML`).
 */
import { parseScript } from './lib/script.js';

const $ = (id) => document.getElementById(id);
const ui = {
  mode: $('mode'),
  status: $('status'),
  warning: $('warning'),
  error: $('error'),
  recordName: $('recordName'),
  recordBtn: $('recordBtn'),
  stopRecordBtn: $('stopRecordBtn'),
  repeat: $('repeat'),
  loopDelay: $('loopDelay'),
  stopPlayBtn: $('stopPlayBtn'),
  importBtn: $('importBtn'),
  importFile: $('importFile'),
  list: $('scriptList'),
  emptyList: $('emptyList'),
  template: $('scriptItem'),
};

let currentState = null;
/** Error shown locally (e.g. import validation) until the next action. */
let localError = null;

/**
 * Sends an API message to the service worker.
 * @returns {Promise<object>} The response payload.
 * @throws {Error} When the service worker reports a failure.
 */
async function api(type, payload = {}) {
  const res = await chrome.runtime.sendMessage({ type, ...payload });
  if (!res) throw new Error('No response from the extension service worker');
  if (!res.ok) throw new Error(res.error);
  return res;
}

async function activeTabId() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab?.id) throw new Error('No active tab');
  return tab.id;
}

function showError(message) {
  localError = message;
  renderMessages();
}

/** Runs an action, surfacing any error in the popup. */
async function guarded(action) {
  localError = null;
  renderMessages();
  try {
    // A new action makes messages from a previous recording/playback obsolete.
    if (currentState?.lastError || currentState?.lastWarning) {
      renderState((await api('qr:clearMessages')).state);
    }
    await action();
  } catch (err) {
    showError(err?.message ?? String(err));
  }
}

function readInt(input, name) {
  const value = Number(input.value);
  if (!Number.isInteger(value) || value < 0) throw new Error(`${name} must be a whole number >= 0`);
  return value;
}

// ------------------------------------------------------------------ rendering

function describeState(s) {
  if (s.mode === 'recording') {
    return `Recording… ${s.recording?.stepCount ?? 0} step(s) captured.`;
  }
  if (s.mode === 'playing' && s.playback) {
    const p = s.playback;
    const loops = p.repeat === 0 ? '∞' : String(p.repeat);
    return `Playing "${p.name}" — loop ${p.loop}/${loops}, step ${p.step}/${p.stepCount}${p.stepType ? ` (${p.stepType})` : ''}`;
  }
  if (s.lastResult) {
    const r = s.lastResult;
    const what = r.kind === 'recording' ? 'Recording' : 'Playback';
    return `${what} "${r.name}": ${r.status}.`;
  }
  return 'Ready.';
}

function renderMessages() {
  const error = localError ?? currentState?.lastError ?? null;
  ui.error.hidden = !error;
  ui.error.textContent = error ?? '';
  const warning = currentState?.lastWarning ?? null;
  ui.warning.hidden = !warning;
  ui.warning.textContent = warning ?? '';
}

function renderState(s) {
  currentState = s;
  ui.mode.textContent = s.mode;
  ui.mode.className = `badge ${s.mode}`;
  ui.status.textContent = describeState(s);
  ui.recordBtn.disabled = s.mode !== 'idle';
  ui.stopRecordBtn.disabled = s.mode !== 'recording';
  ui.stopPlayBtn.disabled = s.mode !== 'playing';
  for (const btn of ui.list.querySelectorAll('[data-action="play"]')) btn.disabled = s.mode !== 'idle';
  renderMessages();
}

async function refreshList() {
  const { scripts } = await api('qr:listScripts');
  ui.list.replaceChildren();
  ui.emptyList.hidden = scripts.length > 0;
  for (const meta of scripts) {
    const item = ui.template.content.firstElementChild.cloneNode(true);
    item.dataset.id = meta.id;
    item.querySelector('.script-name').textContent = meta.name;
    const host = safeHost(meta.startUrl);
    item.querySelector('.script-meta').textContent = `${meta.stepCount} step(s)${host ? ` · ${host}` : ''}`;
    item.querySelector('[data-action="play"]').disabled = currentState?.mode !== 'idle';
    ui.list.append(item);
  }
}

function safeHost(url) {
  try {
    return url ? new URL(url).host || url : '';
  } catch {
    return '';
  }
}

// ------------------------------------------------------------------ actions

async function exportScript(id) {
  const { script } = await api('qr:getScript', { id });
  const blob = new Blob([`${JSON.stringify(script, null, 2)}\n`], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `${script.name.replace(/[^\w.-]+/g, '_').slice(0, 80) || 'script'}.quickreplay.json`;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
}

async function importFile(file) {
  const text = await file.text();
  const { errors } = parseScript(text, 'browser');
  if (errors.length) throw new Error(`Cannot import "${file.name}":\n- ${errors.join('\n- ')}`);
  await api('qr:importScript', { text });
  ui.status.textContent = `Imported "${file.name}".`;
  await refreshList();
}

ui.recordBtn.addEventListener('click', () => guarded(async () => {
  const { recording } = await api('qr:startRecording', { tabId: await activeTabId(), name: ui.recordName.value });
  ui.status.textContent = `Recording ${recording.startUrl}…`;
}));

ui.stopRecordBtn.addEventListener('click', () => guarded(async () => {
  await api('qr:stopRecording', { name: ui.recordName.value });
  ui.recordName.value = '';
  await refreshList();
}));

ui.stopPlayBtn.addEventListener('click', () => guarded(() => api('qr:stopPlayback')));

ui.importBtn.addEventListener('click', () => ui.importFile.click());
ui.importFile.addEventListener('change', () => guarded(async () => {
  const [file] = ui.importFile.files;
  ui.importFile.value = '';
  if (file) await importFile(file);
}));

ui.list.addEventListener('click', (event) => {
  const button = event.target.closest('button[data-action]');
  const item = button?.closest('li[data-id]');
  if (!item) return;
  const { id } = item.dataset;
  const renameBox = item.querySelector('.rename');
  const renameInput = item.querySelector('.rename-input');
  guarded(async () => {
    switch (button.dataset.action) {
      case 'play':
        await api('qr:play', {
          scriptId: id,
          tabId: await activeTabId(),
          repeat: readInt(ui.repeat, 'Repeat'),
          loopDelayMs: readInt(ui.loopDelay, 'Delay between loops'),
        });
        break;
      case 'rename':
        renameInput.value = item.querySelector('.script-name').textContent;
        renameBox.hidden = false;
        renameInput.focus();
        break;
      case 'rename-save':
        await api('qr:renameScript', { id, name: renameInput.value });
        await refreshList();
        break;
      case 'rename-cancel':
        renameBox.hidden = true;
        break;
      case 'export':
        await exportScript(id);
        break;
      case 'delete':
        // Two-step confirmation (dialogs are unreliable inside extension popups).
        if (button.dataset.confirm !== '1') {
          button.dataset.confirm = '1';
          button.textContent = 'Confirm delete';
          break;
        }
        await api('qr:deleteScript', { id });
        await refreshList();
        break;
      default:
        break;
    }
  });
});

chrome.runtime.onMessage.addListener((message) => {
  if (message?.type === 'qr:state') renderState(message.state);
});

guarded(async () => {
  const { state } = await api('qr:getState');
  renderState(state);
  await refreshList();
});
