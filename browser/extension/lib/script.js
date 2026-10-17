/**
 * Shared helpers for Quick Replay scripts (`quickreplay/v1`).
 *
 * This module must stay free of `chrome.*` APIs so it can be imported both by the
 * extension (service worker, popup) and by Node unit tests.
 */

export const FORMAT = 'quickreplay/v1';
export const PLATFORMS = Object.freeze(['android', 'browser']);
export const BROWSER_STEP_TYPES = Object.freeze(['navigate', 'click', 'type', 'key', 'scroll', 'wait']);
export const ANDROID_STEP_TYPES = Object.freeze(['tap', 'longPress', 'swipe', 'wait']);
export const MOUSE_BUTTONS = Object.freeze(['left', 'middle', 'right']);

const DEFAULT_MAX_ERRORS = 50;

const isObject = (v) => typeof v === 'object' && v !== null && !Array.isArray(v);
const isNumber = (v) => typeof v === 'number' && Number.isFinite(v);
const isNonNegativeInt = (v) => Number.isInteger(v) && v >= 0;

/**
 * Per-step field checks, keyed by step type. Each entry is a list of
 * [field, required, check, description].
 */
const STEP_FIELDS = {
  tap: [
    ['x', true, isNumber, 'a number'],
    ['y', true, isNumber, 'a number'],
    ['durationMs', false, (v) => Number.isInteger(v) && v >= 1, 'an integer >= 1'],
  ],
  longPress: [
    ['x', true, isNumber, 'a number'],
    ['y', true, isNumber, 'a number'],
    ['durationMs', true, (v) => Number.isInteger(v) && v >= 1, 'an integer >= 1'],
  ],
  swipe: [], // points are checked separately
  wait: [['ms', true, isNonNegativeInt, 'an integer >= 0']],
  navigate: [['url', true, (v) => typeof v === 'string', 'a string']],
  click: [
    ['selector', false, (v) => typeof v === 'string', 'a string'],
    ['x', true, isNumber, 'a number'],
    ['y', true, isNumber, 'a number'],
    ['button', false, (v) => MOUSE_BUTTONS.includes(v), `one of ${MOUSE_BUTTONS.join(', ')}`],
    ['clickCount', false, (v) => Number.isInteger(v) && v >= 1, 'an integer >= 1'],
  ],
  type: [
    ['selector', true, (v) => typeof v === 'string', 'a string'],
    ['text', true, (v) => typeof v === 'string', 'a string'],
    ['sensitive', false, (v) => typeof v === 'boolean', 'a boolean'],
  ],
  key: [['key', true, (v) => typeof v === 'string' && v.length > 0, 'a non-empty string']],
  scroll: [
    ['x', true, isNumber, 'a number'],
    ['y', true, isNumber, 'a number'],
  ],
};

/**
 * Validates a script object against the essentials of `spec/quickreplay.schema.json`.
 *
 * In addition to the schema, step types are checked against the script platform
 * (e.g. a `tap` step is rejected in a `browser` script).
 *
 * @param {unknown} obj Parsed JSON value.
 * @param {{ platform?: 'android' | 'browser', maxErrors?: number }} [options]
 *   `platform`: also require this platform. `maxErrors`: stop collecting after this many errors.
 * @returns {string[]} Human-readable errors; an empty array means the script is valid.
 */
export function validateScript(obj, options = {}) {
  const maxErrors = options.maxErrors ?? DEFAULT_MAX_ERRORS;
  const errors = [];
  const add = (msg) => {
    if (errors.length < maxErrors) errors.push(msg);
  };

  if (!isObject(obj)) return ['Script must be a JSON object'];

  if (obj.format !== FORMAT) add(`format must be "${FORMAT}"`);
  if (typeof obj.name !== 'string' || obj.name.length === 0) add('name must be a non-empty string');
  if (!PLATFORMS.includes(obj.platform)) {
    add(`platform must be one of ${PLATFORMS.join(', ')}`);
  } else if (options.platform && obj.platform !== options.platform) {
    add(`platform must be "${options.platform}" (got "${obj.platform}")`);
  }
  if (obj.repeat !== undefined && !isNonNegativeInt(obj.repeat)) add('repeat must be an integer >= 0');
  if (obj.loopDelayMs !== undefined && !isNonNegativeInt(obj.loopDelayMs)) {
    add('loopDelayMs must be an integer >= 0');
  }
  if (obj.screen !== undefined) {
    if (!isObject(obj.screen)) {
      add('screen must be an object');
    } else {
      for (const k of ['width', 'height']) {
        if (!(Number.isInteger(obj.screen[k]) && obj.screen[k] >= 1)) add(`screen.${k} must be an integer >= 1`);
      }
    }
  } else if (obj.platform === 'android') {
    add('screen is required for android scripts');
  }
  if (obj.startUrl !== undefined && typeof obj.startUrl !== 'string') add('startUrl must be a string');
  if (obj.createdAt !== undefined && typeof obj.createdAt !== 'string') add('createdAt must be a string');

  if (!Array.isArray(obj.steps)) {
    add('steps must be an array');
    return errors;
  }

  const allowed = obj.platform === 'android' ? ANDROID_STEP_TYPES
    : obj.platform === 'browser' ? BROWSER_STEP_TYPES
      : [...new Set([...ANDROID_STEP_TYPES, ...BROWSER_STEP_TYPES])];

  for (let i = 0; i < obj.steps.length && errors.length < maxErrors; i++) {
    validateStep(obj.steps[i], `steps[${i}]`, allowed, add);
  }
  if (errors.length >= maxErrors) errors.push('(further errors omitted)');
  return errors;
}

function validateStep(step, path, allowedTypes, add) {
  if (!isObject(step)) {
    add(`${path} must be an object`);
    return;
  }
  if (!Object.hasOwn(STEP_FIELDS, step.type)) {
    add(`${path}.type is unknown: ${JSON.stringify(step.type)}`);
    return;
  }
  if (!allowedTypes.includes(step.type)) {
    add(`${path}.type "${step.type}" is not allowed for this platform`);
    return;
  }
  if (step.delayBeforeMs !== undefined && !isNonNegativeInt(step.delayBeforeMs)) {
    add(`${path}.delayBeforeMs must be an integer >= 0`);
  }
  for (const [field, required, check, desc] of STEP_FIELDS[step.type]) {
    const value = step[field];
    if (value === undefined) {
      if (required) add(`${path}.${field} is required for "${step.type}" steps`);
    } else if (!check(value)) {
      add(`${path}.${field} must be ${desc}`);
    }
  }
  if (step.type === 'swipe') {
    if (!Array.isArray(step.points) || step.points.length < 2) {
      add(`${path}.points must be an array of at least 2 points`);
      return;
    }
    step.points.forEach((p, j) => {
      if (!isObject(p) || !isNumber(p.x) || !isNumber(p.y) || !isNonNegativeInt(p.t)) {
        add(`${path}.points[${j}] must be { x: number, y: number, t: integer >= 0 }`);
      }
    });
  }
}

/**
 * Returns a deep copy of a (valid) script with documented defaults filled in:
 * `repeat` (1), `loopDelayMs` (0), each step's `delayBeforeMs` (0), and for `click`
 * steps `button` ("left") and `clickCount` (1). Unknown fields are preserved.
 *
 * @param {object} script A script that passed {@link validateScript}.
 * @returns {object} Normalized copy.
 */
export function normalizeScript(script) {
  const copy = structuredClone(script);
  copy.name = String(copy.name ?? '').trim() || 'Untitled';
  copy.repeat ??= 1;
  copy.loopDelayMs ??= 0;
  copy.steps = (copy.steps ?? []).map((step) => {
    const s = { ...step, delayBeforeMs: step.delayBeforeMs ?? 0 };
    if (s.type === 'click') {
      s.button ??= 'left';
      s.clickCount ??= 1;
    }
    return s;
  });
  return copy;
}

/**
 * Parses JSON text and validates it as a script for the given platform.
 *
 * @param {string} text JSON text.
 * @param {'android' | 'browser'} [platform] Required platform, if any.
 * @returns {{ script?: object, errors: string[] }} `script` is set (normalized) only when valid.
 */
export function parseScript(text, platform) {
  let obj;
  try {
    obj = JSON.parse(text);
  } catch (err) {
    return { errors: [`Invalid JSON: ${err.message}`] };
  }
  const errors = validateScript(obj, { platform });
  return errors.length ? { errors } : { script: normalizeScript(obj), errors };
}
