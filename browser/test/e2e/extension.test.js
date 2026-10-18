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

// ------------------------------------------------------------------ PAD solver

const PAD_DOC_BOARD = 'LGHLBBLRDDGLBRRLRRHBRGBLLBBRRD';
const padSettings = (overrides = {}) => ({
  goals: [{ type: 'row', color: 'L' }],
  diagonals: true,
  maxMoves: 40,
  timeLimitMs: 1000,
  msPerMove: 20,
  ...overrides,
});
const padText = (page) => page.evaluate(() => window.boardText());

test('PAD solver reads the board, solves it and drags the path with trusted input', { timeout: TEST_TIMEOUT }, async () => {
  const { page, tabId } = await openPage(`pad.html?patt=${PAD_DOC_BOARD}`, 'pad-drag');
  const { result } = await apiOk({ type: 'qr:padSolve', tabId, settings: padSettings(), drag: true });

  assert.equal(result.status, 'solved');
  assert.equal(result.dragged, true);
  assert.deepEqual(result.goals, [{ label: 'Row of Light', met: true }]);
  assert.equal(result.before.replace(/\n/g, ''), PAD_DOC_BOARD);
  // The page saw exactly the solver's path, one press and one release, all trusted events.
  const log = await page.evaluate(() => window.padLog);
  const cols = 6;
  const expected = result.path.map((label) => {
    const [, r, c] = label.match(/^r(\d+)c(\d+)$/);
    return (Number(r) - 1) * cols + Number(c) - 1;
  });
  assert.deepEqual(log.path, expected);
  assert.equal(log.down, 1);
  assert.equal(log.up, 1);
  assert.equal(log.trusted, true);
  assert.equal(await padText(page), result.afterDrag.replace(/\n/g, ''));
  assert.match(await padText(page), /LLLLLL/);

  const state = await waitIdle();
  assert.equal(state.lastResult.kind, 'pad');
  assert.equal(state.lastResult.status, 'dragged');
  assert.equal(await debuggerAttached(tabId), false, 'debugger detached after the drag');
  // Settings are kept for the next popup.
  const { settings } = await apiOk({ type: 'qr:padGetSettings' });
  assert.deepEqual(settings, padSettings());
});

test('PAD solver: solve only, impossible goals and pages without a board', { timeout: TEST_TIMEOUT }, async () => {
  const { page, tabId } = await openPage(`pad.html?patt=${PAD_DOC_BOARD}`, 'pad-solve-only');
  const { result } = await apiOk({ type: 'qr:padSolve', tabId, settings: padSettings({ diagonals: false }), drag: false });
  assert.equal(result.status, 'solved');
  assert.equal(result.dragged, false);
  assert.equal(await padText(page), PAD_DOC_BOARD, 'solve only does not touch the page');
  assert.deepEqual((await page.evaluate(() => window.padLog)).path, []);

  const impossible = await apiOk({
    type: 'qr:padSolve', tabId, settings: padSettings({ goals: [{ type: 'square', color: 'D' }] }), drag: true,
  });
  assert.equal(impossible.result.status, 'impossible');
  assert.deepEqual(impossible.result.reasons, ['Dark: the goals need 9 orbs, the board has 3']);
  assert.equal(impossible.result.dragged, false);
  assert.equal(await debuggerAttached(tabId), false);

  const bad = await api({ type: 'qr:padSolve', tabId, settings: padSettings({ goals: [{ type: 'row', color: 'Q' }] }) });
  assert.equal(bad.ok, false);
  assert.match(bad.error, /unknown colour/);
  const noGoals = await api({ type: 'qr:padSolve', tabId, settings: padSettings({ goals: [] }), drag: true });
  assert.match(noGoals.error, /at least one goal/);
  assert.match((await api({ type: 'qr:padStop' })).error, /Not dragging/);

  const { tabId: emptyTab } = await openPage(`pad.html?patt=${'Z'.repeat(6)}${PAD_DOC_BOARD.slice(6)}`, 'pad-empty');
  const empty = await api({ type: 'qr:padSolve', tabId: emptyTab, settings: padSettings(), drag: true });
  assert.match(empty.error, /empty cells/);
  assert.equal(await debuggerAttached(emptyTab), false, 'debugger detached after an error');

  const { tabId: plainTab } = await openPage('page.html', 'pad-no-board');
  const none = await api({ type: 'qr:padSolve', tabId: plainTab, settings: padSettings() });
  assert.match(none.error, /No dawnGlare board/);
  assert.equal((await waitIdle()).mode, 'idle');
});

test('PAD panel in the popup solves the active tab and shows the result', { timeout: TEST_TIMEOUT }, async () => {
  const { page } = await openPage(`pad.html?patt=${PAD_DOC_BOARD}`, 'pad-popup');
  await apiOk({ type: 'qr:padSaveSettings', settings: padSettings() });
  const popup = await context.newPage();
  openPages.add(popup);
  await popup.goto(`chrome-extension://${extensionId}/popup.html`);
  await popup.locator('#padGoals li').first().waitFor();
  assert.equal(await popup.locator('#padGoals li').count(), 1);
  // Add a second goal: at least 4 combos.
  await popup.click('#padAddGoal');
  const second = popup.locator('#padGoals li').nth(1);
  await second.locator('.goal-type').selectOption('combos');
  await second.locator('.goal-count').fill('4');
  assert.equal(await second.locator('.goal-color').isHidden(), true);
  // The popup page is the active tab now; bring the board page to the front so it is solved.
  await page.bringToFront();
  await popup.click('#padSolveBtn');
  await popup.locator('#padResult').filter({ hasText: 'Solved' }).waitFor();
  const shown = await popup.locator('#padResult').textContent();
  assert.match(shown, /\[x\] Row of Light/);
  assert.match(shown, /\[x\] 4\+ combos/);
  assert.equal(await popup.locator('#error').isHidden(), true);
  const { settings } = await apiOk({ type: 'qr:padGetSettings' });
  assert.deepEqual(settings.goals, [{ type: 'row', color: 'L' }, { type: 'combos', count: 4 }]);
});

test('PAD drag can be stopped midway and the orb is released', { timeout: TEST_TIMEOUT }, async () => {
  const { page, tabId } = await openPage(`pad.html?patt=${PAD_DOC_BOARD}`, 'pad-stop');
  // Slow drag so there is time to stop it.
  const running = api({ type: 'qr:padSolve', tabId, settings: padSettings({ msPerMove: 400 }), drag: true });
  await waitFor(async () => (await apiOk({ type: 'qr:getState' })).state.pad?.canStop, { message: 'drag to start' });
  await waitFor(async () => (await page.evaluate(() => window.padLog.path.length)) >= 2, { message: 'first swap' });
  await apiOk({ type: 'qr:padStop' });
  const res = await running;
  assert.equal(res.ok, true, res.error);
  assert.equal(res.result.stopped, true);
  assert.equal(res.result.dragged, false);
  const log = await page.evaluate(() => window.padLog);
  assert.equal(log.up, 1, 'mouse released after stop');
  assert.ok(log.path.length < res.result.path.length, 'stopped before the end of the path');
  const state = await waitIdle();
  assert.equal(state.lastResult.status, 'stopped');
  assert.equal(await debuggerAttached(tabId), false);
});
