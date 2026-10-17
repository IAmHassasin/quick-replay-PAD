// End-to-end tests: load the unpacked extension in Chromium with Playwright, record real
// interactions on a local page, replay them through chrome.debugger and check the page state.
//
// Set QR_HEADED=1 to watch the browser.
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { extname, join, normalize } from 'node:path';
import { after, afterEach, before, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { FORMAT, validateScript } from '../../extension/lib/script.js';

const EXTENSION_DIR = fileURLToPath(new URL('../../extension', import.meta.url));
const FIXTURES_DIR = fileURLToPath(new URL('../fixtures', import.meta.url));
const TEST_TIMEOUT = 90_000;

let server;
let baseUrl;
let context;
let sw;
let extensionId;
let userDataDir;
// Lets a test simulate a slow server for page 2 and count how often it is requested.
let page2DelayMs = 0;
let page2Requests = 0;

function startServer() {
  const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript' };
  server = createServer((req, res) => {
    const path = normalize(decodeURIComponent(new URL(req.url, 'http://x').pathname)).replace(/^[/\\]+/, '');
    const file = join(FIXTURES_DIR, path);
    if (!file.startsWith(FIXTURES_DIR)) {
      res.writeHead(403).end();
      return;
    }
    const respond = () => {
      try {
        const body = readFileSync(file);
        res.writeHead(200, { 'content-type': types[extname(file)] ?? 'application/octet-stream' }).end(body);
      } catch {
        res.writeHead(404).end('not found');
      }
    };
    if (path === 'page2.html') {
      page2Requests++;
      setTimeout(respond, page2DelayMs);
    } else {
      respond();
    }
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      baseUrl = `http://127.0.0.1:${server.address().port}`;
      resolve();
    });
  });
}

/** Calls the extension message API inside the service worker. */
function api(message) {
  return sw.evaluate((m) => globalThis.QuickReplay.handle(m), message);
}

async function apiOk(message) {
  const res = await api(message);
  assert.equal(res.ok, true, `${message.type} failed: ${res.error}`);
  return res;
}

function tabIdFor(page) {
  return sw.evaluate(async (url) => (await chrome.tabs.query({})).find((t) => t.url === url)?.id, page.url());
}

async function waitFor(fn, { timeout = 30_000, interval = 100, message = 'condition' } = {}) {
  const deadline = Date.now() + timeout;
  for (;;) {
    const value = await fn();
    if (value) return value;
    if (Date.now() > deadline) throw new Error(`Timed out waiting for ${message}`);
    await new Promise((r) => setTimeout(r, interval));
  }
}

async function waitIdle() {
  return waitFor(async () => {
    const { state } = await apiOk({ type: 'qr:getState' });
    return state.mode === 'idle' ? state : null;
  }, { timeout: 60_000, message: 'playback to finish' });
}

/**
 * True when the extension itself holds a debugger session on the tab. (TargetInfo.attached
 * cannot be used: Playwright's own CDP session also counts as attached.) Probes by trying to
 * attach: Chrome refuses a second session from the same extension.
 */
function debuggerAttached(tabId) {
  return sw.evaluate(async (id) => {
    try {
      await chrome.debugger.attach({ tabId: id }, '1.3');
    } catch {
      return true;
    }
    await chrome.debugger.detach({ tabId: id });
    return false;
  }, tabId);
}

const openPages = new Set();

/** Opens the fixture in a new tab with a unique URL so its tab id can be found reliably. */
async function openPage(path, tag) {
  const page = await context.newPage();
  openPages.add(page);
  await page.goto(`${baseUrl}/${path}?test=${tag}`);
  const tabId = await tabIdFor(page);
  assert.ok(tabId, 'tab id found');
  return { page, tabId };
}

const text = (page, selector) => page.locator(selector).textContent();

before(async () => {
  await startServer();
  userDataDir = mkdtempSync(join(tmpdir(), 'quick-replay-e2e-'));
  context = await chromium.launchPersistentContext(userDataDir, {
    channel: 'chromium',
    headless: !process.env.QR_HEADED,
    args: [`--disable-extensions-except=${EXTENSION_DIR}`, `--load-extension=${EXTENSION_DIR}`],
  });
  [sw] = context.serviceWorkers();
  sw ??= await context.waitForEvent('serviceworker');
  extensionId = new URL(sw.url()).host;
  await waitFor(() => sw.evaluate(() => typeof globalThis.QuickReplay?.handle === 'function'), {
    message: 'extension service worker',
  });
});

afterEach(async () => {
  // Leave a clean state for the next test even when an assertion failed mid-way.
  const { state } = await api({ type: 'qr:getState' });
  if (state.mode === 'playing') await api({ type: 'qr:stopPlayback' });
  if (state.mode === 'recording') await api({ type: 'qr:stopRecording' });
  for (const page of openPages) await page.close().catch(() => {});
  openPages.clear();
});

after(async () => {
  await context?.close();
  await new Promise((r) => (server ? server.close(r) : r()));
  if (userDataDir) rmSync(userDataDir, { recursive: true, force: true });
});

test('records real interactions and replays them with repeat 2', { timeout: TEST_TIMEOUT }, async () => {
  const { page, tabId } = await openPage('page.html', 'record');
  await apiOk({ type: 'qr:startRecording', tabId, name: 'E2E recording' });
  await page.fill('#text', 'hello');
  await page.fill('#secret', 'hunter2');
  await page.press('#text', 'Enter');
  await page.click('#add');
  await page.click('#add');
  await page.mouse.move(200, 200);
  await page.mouse.wheel(0, 900);
  await page.waitForFunction(() => window.scrollY > 0);
  await page.waitForTimeout(600); // let the scroll debounce settle
  const { script, meta } = await apiOk({ type: 'qr:stopRecording' });

  assert.deepEqual(validateScript(script, { platform: 'browser' }), []);
  assert.equal(script.format, FORMAT);
  assert.equal(script.platform, 'browser');
  assert.equal(script.name, 'E2E recording');
  assert.equal(script.startUrl, `${baseUrl}/page.html?test=record`);
  assert.ok(meta?.id, 'script saved');
  const types = script.steps.map((s) => s.type);
  assert.deepEqual(types, ['type', 'type', 'key', 'click', 'click', 'scroll'], JSON.stringify(script.steps));
  assert.deepEqual(script.steps[0], { ...script.steps[0], selector: '#text', text: 'hello' });
  // Password values are never recorded.
  assert.deepEqual(script.steps[1], { ...script.steps[1], selector: '#secret', text: '', sensitive: true });
  assert.equal(JSON.stringify(script).includes('hunter2'), false);
  assert.equal(script.steps[2].key, 'Enter');
  assert.equal(script.steps[3].selector, '#add');
  assert.ok(Number.isFinite(script.steps[3].x) && Number.isFinite(script.steps[3].y));
  assert.ok(script.steps[5].y > 0);
  assert.ok(script.steps.every((s) => (s.delayBeforeMs ?? 0) <= 10_000));
  assert.equal(await text(page, '#counter'), '2');

  // Fresh page state, then replay twice.
  await page.reload();
  await page.evaluate(() => window.scrollTo(0, 0));
  assert.equal(await text(page, '#counter'), '0');
  await apiOk({ type: 'qr:play', scriptId: meta.id, tabId, repeat: 2 });
  const final = await waitIdle();
  assert.equal(final.lastError, null);
  assert.equal(final.lastResult.status, 'completed');
  assert.match(final.lastWarning, /Skipped sensitive field/);
  assert.equal(await page.inputValue('#secret'), '');
  assert.equal(await text(page, '#counter'), '4');
  assert.deepEqual(await page.locator('#list li').allTextContents(), ['hello', 'hello', 'hello', 'hello']);
  assert.equal(await text(page, '#enterCount'), '2');
  assert.ok((await page.evaluate(() => window.scrollY)) > 0, 'scroll replayed');
  assert.equal(await debuggerAttached(tabId), false, 'debugger detached after playback');
});

test('stops an infinite (repeat 0) playback', { timeout: TEST_TIMEOUT }, async () => {
  const { page, tabId } = await openPage('page.html', 'infinite');
  const script = {
    format: FORMAT,
    name: 'Infinite clicks',
    platform: 'browser',
    steps: [{ type: 'click', selector: '#add', x: 0, y: 0, delayBeforeMs: 50 }],
  };
  await apiOk({ type: 'qr:play', script, tabId, repeat: 0 });
  await page.waitForFunction(() => Number(document.querySelector('#counter').textContent) >= 5);

  const { state } = await apiOk({ type: 'qr:getState' });
  assert.equal(state.mode, 'playing');
  assert.equal(state.playback.repeat, 0);
  assert.ok(state.playback.loop >= 2, `loop counter advanced (${state.playback.loop})`);
  assert.equal(await debuggerAttached(tabId), true);

  const { lastResult } = await apiOk({ type: 'qr:stopPlayback' });
  assert.equal(lastResult.status, 'stopped');
  const stoppedAt = await text(page, '#counter');
  await page.waitForTimeout(500);
  assert.equal(await text(page, '#counter'), stoppedAt, 'no more clicks after stop');
  const after = await apiOk({ type: 'qr:getState' });
  assert.equal(after.state.mode, 'idle');
  assert.equal(after.state.lastError, null);
  assert.equal(await debuggerAttached(tabId), false, 'debugger detached after stop');
});

test('records navigation across pages and replays it', { timeout: TEST_TIMEOUT }, async () => {
  const { page, tabId } = await openPage('page.html', 'navigation');
  await apiOk({ type: 'qr:startRecording', tabId, name: 'Two pages' });
  await page.click('#next');
  await page.waitForURL('**/page2.html');
  await waitFor(async () => (await apiOk({ type: 'qr:getState' })).state.recording?.injections >= 2, {
    message: 'recorder re-injection after navigation',
  });
  await page.click('[data-testid="page2-button"]');
  const { script } = await apiOk({ type: 'qr:stopRecording' });

  assert.deepEqual(validateScript(script, { platform: 'browser' }), []);
  assert.deepEqual(script.steps.map((s) => s.type), ['click', 'navigate', 'click'], JSON.stringify(script.steps));
  assert.equal(script.steps[0].selector, '#next');
  assert.equal(script.steps[1].url, `${baseUrl}/page2.html`);
  assert.equal(script.steps[2].selector, '[data-testid="page2-button"]');

  await page.goto(script.startUrl);
  // The link click already navigates; the recorded navigate step must not load page 2 again,
  // even when it runs immediately and the server is slow.
  script.steps[1].delayBeforeMs = 0;
  page2DelayMs = 800;
  page2Requests = 0;
  await apiOk({ type: 'qr:play', script, tabId, repeat: 1 });
  const final = await waitIdle();
  assert.equal(final.lastError, null);
  assert.equal(final.lastResult.status, 'completed');
  assert.equal(page.url(), `${baseUrl}/page2.html`);
  assert.equal(await text(page, '#presses'), '1');
  page2DelayMs = 0;
  assert.equal(page2Requests, 1, 'page 2 should be requested exactly once');
});

test('surfaces playback errors and detaches the debugger', { timeout: TEST_TIMEOUT }, async () => {
  const { tabId } = await openPage('page.html', 'error');
  const script = {
    format: FORMAT,
    name: 'Broken',
    platform: 'browser',
    steps: [{ type: 'type', selector: '#does-not-exist', text: 'x' }],
  };
  await apiOk({ type: 'qr:play', script, tabId });
  const final = await waitIdle();
  assert.equal(final.lastResult.status, 'failed');
  assert.match(final.lastError, /Step 1 \(type\) failed: element not found/);
  assert.equal(await debuggerAttached(tabId), false);
});

test('popup import rejects bad scripts and escapes script names', { timeout: TEST_TIMEOUT }, async () => {
  const popup = await context.newPage();
  openPages.add(popup);
  await popup.goto(`chrome-extension://${extensionId}/popup.html`);
  const items = popup.locator('#scriptList li');
  await popup.waitForFunction(() => document.querySelector('#status').textContent !== '');
  const before = await items.count();

  const upload = (name, content) => popup.setInputFiles('#importFile', {
    name, mimeType: 'application/json', buffer: Buffer.from(content),
  });

  const android = { format: FORMAT, name: 'Android', platform: 'android', screen: { width: 1, height: 1 }, steps: [] };
  await upload('android.json', JSON.stringify(android));
  await popup.locator('#error').filter({ hasText: 'platform must be "browser"' }).waitFor();

  await upload('broken.json', '{ not json');
  await popup.locator('#error').filter({ hasText: 'Invalid JSON' }).waitFor();

  await upload('nosteps.json', JSON.stringify({ format: FORMAT, name: 'x', platform: 'browser' }));
  await popup.locator('#error').filter({ hasText: 'steps must be an array' }).waitFor();
  assert.equal(await items.count(), before, 'nothing imported');

  // The service worker validates too (popup validation cannot be bypassed).
  const direct = await api({ type: 'qr:importScript', text: JSON.stringify(android) });
  assert.equal(direct.ok, false);
  assert.match(direct.error, /platform must be "browser"/);

  const evilName = '<img src=x onerror="window.__xss = 1">';
  const valid = { format: FORMAT, name: evilName, platform: 'browser', steps: [{ type: 'wait', ms: 1 }] };
  await upload('valid.json', JSON.stringify(valid));
  await waitFor(async () => (await items.count()) === before + 1, { message: 'imported script in list' });
  assert.equal(await popup.locator('#error').isHidden(), true);
  const item = items.filter({ hasText: evilName });
  assert.equal(await item.locator('.script-name').textContent(), evilName);
  assert.equal(await popup.locator('#scriptList img').count(), 0);
  assert.equal(await popup.evaluate(() => window.__xss), undefined);

  // Rename and delete through the UI.
  await item.locator('[data-action="rename"]').click();
  await item.locator('.rename-input').fill('Renamed script');
  await item.locator('[data-action="rename-save"]').click();
  const renamed = items.filter({ hasText: 'Renamed script' });
  await renamed.waitFor();
  await renamed.locator('[data-action="delete"]').click();
  await renamed.locator('[data-action="delete"]').click(); // confirm
  await waitFor(async () => (await items.count()) === before, { message: 'script deleted' });
});
