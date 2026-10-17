import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { test } from 'node:test';
import { FORMAT, normalizeScript, parseScript, validateScript } from '../../extension/lib/script.js';

const browserScript = () => ({
  format: FORMAT,
  name: 'Sample',
  platform: 'browser',
  startUrl: 'http://localhost/',
  steps: [
    { type: 'navigate', url: 'http://localhost/a' },
    { type: 'click', selector: '#a', x: 1, y: 2 },
    { type: 'click', x: 10.5, y: 20, button: 'right', clickCount: 2 },
    { type: 'type', selector: '#t', text: 'hi', delayBeforeMs: 100 },
    { type: 'type', selector: '#p', text: '', sensitive: true },
    { type: 'key', key: 'Enter' },
    { type: 'scroll', x: 0, y: 500 },
    { type: 'wait', ms: 0 },
  ],
});

const androidScript = () => ({
  format: FORMAT,
  name: 'Android',
  platform: 'android',
  repeat: 0,
  screen: { width: 1080, height: 2400 },
  steps: [
    { type: 'tap', x: 1, y: 2 },
    { type: 'longPress', x: 1, y: 2, durationMs: 800 },
    { type: 'swipe', points: [{ x: 0, y: 0, t: 0 }, { x: 5, y: 5, t: 100 }] },
    { type: 'wait', ms: 10 },
  ],
});

test('accepts valid browser and android scripts', () => {
  assert.deepEqual(validateScript(browserScript()), []);
  assert.deepEqual(validateScript(androidScript()), []);
});

test('accepts an empty step list and unknown extra fields', () => {
  assert.deepEqual(validateScript({ ...browserScript(), steps: [], extra: true }), []);
});

test('rejects non-objects', () => {
  for (const v of [null, 1, 'x', [], undefined]) {
    assert.deepEqual(validateScript(v), ['Script must be a JSON object']);
  }
});

test('reports top-level errors', () => {
  const errors = validateScript({
    format: 'other', name: '', platform: 'ios', repeat: -1, loopDelayMs: 1.5, startUrl: 3, steps: {},
  });
  assert.ok(errors.some((e) => e.startsWith('format')));
  assert.ok(errors.some((e) => e.startsWith('name')));
  assert.ok(errors.some((e) => e.startsWith('platform')));
  assert.ok(errors.some((e) => e.startsWith('repeat')));
  assert.ok(errors.some((e) => e.startsWith('loopDelayMs')));
  assert.ok(errors.some((e) => e.startsWith('startUrl')));
  assert.ok(errors.some((e) => e.startsWith('steps must be an array')));
});

test('android scripts require a valid screen', () => {
  const s = androidScript();
  delete s.screen;
  assert.deepEqual(validateScript(s), ['screen is required for android scripts']);
  assert.deepEqual(validateScript({ ...androidScript(), screen: { width: 0, height: 2.5 } }), [
    'screen.width must be an integer >= 1',
    'screen.height must be an integer >= 1',
  ]);
});

test('enforces the required platform option', () => {
  assert.deepEqual(validateScript(browserScript(), { platform: 'browser' }), []);
  const errors = validateScript(androidScript(), { platform: 'browser' });
  assert.deepEqual(errors, ['platform must be "browser" (got "android")']);
});

test('rejects step types that do not belong to the platform', () => {
  const s = browserScript();
  s.steps.push({ type: 'tap', x: 1, y: 1 });
  assert.deepEqual(validateScript(s), ['steps[8].type "tap" is not allowed for this platform']);
});

test('reports per-step field errors with paths', () => {
  const s = browserScript();
  s.steps = [
    { type: 'click', selector: 5, x: '1', y: 2, button: 'side', clickCount: 0 },
    { type: 'type', selector: '#a' },
    { type: 'key', key: '' },
    { type: 'scroll', x: 0 },
    { type: 'wait', ms: -5, delayBeforeMs: -1 },
    { type: 'navigate' },
    { type: 'nope' },
    'string',
  ];
  assert.deepEqual(validateScript(s), [
    'steps[0].selector must be a string',
    'steps[0].x must be a number',
    'steps[0].button must be one of left, middle, right',
    'steps[0].clickCount must be an integer >= 1',
    'steps[1].text is required for "type" steps',
    'steps[2].key must be a non-empty string',
    'steps[3].y is required for "scroll" steps',
    'steps[4].delayBeforeMs must be an integer >= 0',
    'steps[4].ms must be an integer >= 0',
    'steps[5].url is required for "navigate" steps',
    'steps[6].type is unknown: "nope"',
    'steps[7] must be an object',
  ]);
});

test('checks swipe points', () => {
  const s = androidScript();
  s.steps = [{ type: 'swipe', points: [{ x: 0, y: 0, t: 0 }] }, { type: 'swipe', points: [{ x: 0, y: 0, t: 0 }, { x: 1 }] }];
  assert.deepEqual(validateScript(s), [
    'steps[0].points must be an array of at least 2 points',
    'steps[1].points[1] must be { x: number, y: number, t: integer >= 0 }',
  ]);
});

test('caps the number of reported errors', () => {
  const s = browserScript();
  s.steps = Array.from({ length: 500 }, () => ({ type: 'wait' }));
  const errors = validateScript(s, { maxErrors: 10 });
  assert.equal(errors.length, 11);
  assert.equal(errors.at(-1), '(further errors omitted)');
});

test('validates very large scripts (no step limit)', () => {
  const s = browserScript();
  s.steps = Array.from({ length: 200000 }, (_, i) => ({ type: 'scroll', x: 0, y: i }));
  assert.deepEqual(validateScript(s), []);
});

test('normalizeScript fills defaults without mutating the input', () => {
  const input = browserScript();
  const snapshot = structuredClone(input);
  const out = normalizeScript(input);
  assert.deepEqual(input, snapshot);
  assert.equal(out.repeat, 1);
  assert.equal(out.loopDelayMs, 0);
  assert.ok(out.steps.every((st) => st.delayBeforeMs >= 0));
  assert.equal(out.steps[1].button, 'left');
  assert.equal(out.steps[1].clickCount, 1);
  assert.equal(out.steps[2].button, 'right');
  assert.equal(out.steps[2].clickCount, 2);
  assert.equal(out.steps[4].sensitive, true);
  assert.deepEqual(validateScript(out), []);
  assert.equal(normalizeScript({ ...input, repeat: 0 }).repeat, 0);
});

test('parseScript handles invalid JSON, platform mismatch and success', () => {
  assert.match(parseScript('{nope', 'browser').errors[0], /^Invalid JSON/);
  assert.deepEqual(parseScript(JSON.stringify(androidScript()), 'browser').errors, ['platform must be "browser" (got "android")']);
  const ok = parseScript(JSON.stringify(browserScript()), 'browser');
  assert.deepEqual(ok.errors, []);
  assert.equal(ok.script.repeat, 1);
});

test('bundled examples are valid browser scripts', () => {
  const dir = new URL('../../examples/', import.meta.url);
  const files = readdirSync(dir).filter((f) => f.endsWith('.json'));
  assert.ok(files.length > 0);
  for (const f of files) {
    const script = JSON.parse(readFileSync(new URL(f, dir), 'utf8'));
    assert.deepEqual(validateScript(script, { platform: 'browser' }), [], f);
  }
});
