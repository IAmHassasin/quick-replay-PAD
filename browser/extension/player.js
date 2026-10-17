/**
 * Quick Replay player: replays a `quickreplay/v1` browser script in one tab through
 * `chrome.debugger` (CDP 1.3) so that every input event is trusted (`isTrusted === true`).
 *
 * Steps are executed strictly one after another; there is no limit on the number of steps
 * or loops and no per-step state is accumulated, so infinite loops run in constant memory.
 */

const PROTOCOL_VERSION = '1.3';
const SELECTOR_TIMEOUT_MS = 5000;
const LOAD_TIMEOUT_MS = 30000;
const POLL_MS = 100;
// How long a navigate step waits for a navigation started by the previous click/key to land
// before it navigates by itself (avoids loading the page twice or replacing a form POST).
const NAVIGATION_GRACE_MS = 3000;

/** CDP key definitions for keys that need more than `key` (subset used by the recorder). */
const KEY_DEFS = {
  Enter: { code: 'Enter', windowsVirtualKeyCode: 13, text: '\r' },
  Tab: { code: 'Tab', windowsVirtualKeyCode: 9 },
  Escape: { code: 'Escape', windowsVirtualKeyCode: 27 },
  Backspace: { code: 'Backspace', windowsVirtualKeyCode: 8 },
  Delete: { code: 'Delete', windowsVirtualKeyCode: 46 },
  ArrowUp: { code: 'ArrowUp', windowsVirtualKeyCode: 38 },
  ArrowDown: { code: 'ArrowDown', windowsVirtualKeyCode: 40 },
  ArrowLeft: { code: 'ArrowLeft', windowsVirtualKeyCode: 37 },
  ArrowRight: { code: 'ArrowRight', windowsVirtualKeyCode: 39 },
  ' ': { code: 'Space', windowsVirtualKeyCode: 32, text: ' ' },
};

/** Error used to unwind the run loop when playback is stopped. */
export class StoppedError extends Error {
  constructor() {
    super('Playback stopped');
    this.name = 'StoppedError';
  }
}

/**
 * Runs inside the page (serialized with `Function.prototype.toString`).
 * Finds the element, scrolls it into view and either returns its center point
 * (`mode === 'point'`) or focuses it and selects its content (`mode === 'focus'`).
 * Returns null while the element is missing or not ready yet.
 */
function pageLocate(selector, mode) {
  let el;
  try {
    el = document.querySelector(selector);
  } catch {
    return { invalid: true };
  }
  if (!el) return null;
  el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' });
  if (mode === 'focus') {
    el.focus();
    const active = el.getRootNode().activeElement;
    if (active !== el && !el.contains(active)) return null;
    if (typeof el.select === 'function') el.select();
    else if (el.isContentEditable) getSelection().selectAllChildren(el);
    return { focused: true };
  }
  const r = el.getBoundingClientRect();
  if (r.width <= 0 || r.height <= 0) return null;
  return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
}

/**
 * Plays one script in one tab.
 *
 * Usage: `const p = new Player(tabId, script, opts); await p.run();` and `p.stop()` from
 * anywhere to interrupt (waits and delays are interrupted immediately).
 */
export class Player {
  /**
   * @param {number} tabId Target tab.
   * @param {object} script Normalized, validated browser script.
   * @param {{
   *   repeat?: number,
   *   loopDelayMs?: number,
   *   onProgress?: (p: {loop: number, repeat: number, step: number, stepCount: number, stepType: string}) => void,
   *   onWarning?: (message: string) => void,
   * }} [options] `repeat`/`loopDelayMs` override the script values; `repeat: 0` loops forever.
   */
  constructor(tabId, script, options = {}) {
    this.tabId = tabId;
    this.target = { tabId };
    this.script = script;
    this.repeat = options.repeat ?? script.repeat ?? 1;
    this.loopDelayMs = options.loopDelayMs ?? script.loopDelayMs ?? 0;
    this.onProgress = options.onProgress ?? (() => {});
    this.onWarning = options.onWarning ?? (() => {});
    this.controller = new AbortController();
    this.loadWaiters = new Set();
    this.attached = false;
  }

  /** Requests the run loop to stop as soon as possible. */
  stop(reason) {
    if (!this.controller.signal.aborted) this.controller.abort(reason ?? new StoppedError());
  }

  /** True once {@link stop} was called or the debugger got detached. */
  get stopped() {
    return this.controller.signal.aborted;
  }

  /** Must be wired to `chrome.debugger.onEvent` by the owner. */
  handleDebuggerEvent(method) {
    if (method === 'Page.loadEventFired') for (const resolve of this.loadWaiters) resolve();
  }

  /** Must be wired to `chrome.debugger.onDetach` by the owner. */
  handleDetach(reason) {
    this.attached = false;
    // "canceled_by_user": the user dismissed Chrome's debugging banner, i.e. a normal stop.
    this.stop(reason === 'canceled_by_user'
      ? new StoppedError()
      : new Error(`Debugger detached (${reason}). Playback cancelled.`));
  }

  /**
   * Runs the whole script (all loops).
   * @returns {Promise<'completed' | 'stopped'>}
   * @throws {Error} The first error that made playback fail.
   */
  async run() {
    const { steps } = this.script;
    if (!steps.length) throw new Error('Script has no steps');
    try {
      await chrome.debugger.attach(this.target, PROTOCOL_VERSION);
      this.attached = true;
      await this.send('Page.enable'); // for Page.loadEventFired
      for (let loop = 1; this.repeat === 0 || loop <= this.repeat; loop++) {
        this.throwIfStopped();
        if (loop > 1 && this.loopDelayMs > 0) await this.sleep(this.loopDelayMs);
        if (this.script.startUrl) await this.ensureUrl(this.script.startUrl);
        for (let i = 0; i < steps.length; i++) {
          const step = steps[i];
          this.onProgress({ loop, repeat: this.repeat, step: i + 1, stepCount: steps.length, stepType: step.type });
          if (step.delayBeforeMs > 0) await this.sleep(step.delayBeforeMs);
          this.throwIfStopped();
          try {
            await this.runStep(step, steps[i - 1]);
          } catch (err) {
            if (this.stopped) throw this.controller.signal.reason;
            throw new Error(`Step ${i + 1} (${step.type}) failed: ${err.message}`);
          }
        }
      }
      return 'completed';
    } catch (err) {
      // Once stopped, the stop reason wins over whatever error the interrupted call raised.
      const reason = this.stopped ? this.controller.signal.reason : err;
      if (reason instanceof StoppedError) return 'stopped';
      throw reason;
    } finally {
      for (const resolve of this.loadWaiters) resolve();
      if (this.attached) {
        this.attached = false;
        await chrome.debugger.detach(this.target).catch((err) => {
          console.warn('[Quick Replay] debugger detach failed:', err?.message ?? err);
        });
      }
    }
  }

  // ------------------------------------------------------------------ steps

  async runStep(step, previous) {
    switch (step.type) {
      case 'click': return this.click(step);
      case 'type': return this.type(step);
      case 'key': return this.pressKey(step.key);
      case 'scroll':
        return this.evaluate(`window.scrollTo(${Number(step.x)}, ${Number(step.y)})`);
      case 'navigate':
        if (previous?.type === 'click' || previous?.type === 'key') return this.followNavigation(step.url);
        return this.ensureUrl(step.url);
      case 'wait': return this.sleep(step.ms);
      default: throw new Error(`Unsupported step type "${step.type}"`);
    }
  }

  async click(step) {
    let point = null;
    if (step.selector) point = await this.locate(step.selector, 'point');
    if (!point) {
      if (step.selector) {
        this.onWarning(`Selector not found, using recorded coordinates: ${step.selector}`);
      }
      point = { x: step.x, y: step.y };
    }
    const base = { x: point.x, y: point.y, button: step.button ?? 'left' };
    const buttons = { left: 1, right: 2, middle: 4 }[base.button];
    const clickCount = step.clickCount ?? 1;
    await this.send('Input.dispatchMouseEvent', { ...base, type: 'mouseMoved', button: 'none' });
    await this.send('Input.dispatchMouseEvent', { ...base, type: 'mousePressed', buttons, clickCount });
    await this.send('Input.dispatchMouseEvent', { ...base, type: 'mouseReleased', buttons: 0, clickCount });
  }

  async type(step) {
    if (step.sensitive && step.text === '') {
      this.onWarning(`Skipped sensitive field with no stored text: ${step.selector}`);
      return;
    }
    const focused = await this.locate(step.selector, 'focus');
    if (!focused) throw new Error(`element not found or not focusable: ${step.selector}`);
    if (step.text === '') await this.pressKey('Delete'); // clears the selected content
    else await this.send('Input.insertText', { text: step.text });
  }

  async pressKey(key) {
    const def = KEY_DEFS[key] ?? (key.length === 1 ? { text: key } : {});
    const params = { key, code: def.code, windowsVirtualKeyCode: def.windowsVirtualKeyCode };
    if (def.text) {
      await this.send('Input.dispatchKeyEvent', { ...params, type: 'keyDown', text: def.text, unmodifiedText: def.text });
    } else {
      await this.send('Input.dispatchKeyEvent', { ...params, type: 'rawKeyDown' });
    }
    await this.send('Input.dispatchKeyEvent', { ...params, type: 'keyUp' });
  }

  /** Waits briefly for a navigation triggered by the previous step; navigates only if it never lands. */
  async followNavigation(url) {
    const deadline = Date.now() + NAVIGATION_GRACE_MS;
    while (Date.now() < deadline) {
      if ((await this.retryEvaluate('location.href')) === url) {
        await this.waitForReadyState();
        return;
      }
      await this.sleep(POLL_MS);
    }
    await this.ensureUrl(url);
  }

  /** Navigates to `url` unless the tab is already there, then waits for the page to load. */
  async ensureUrl(url) {
    const current = await this.retryEvaluate('location.href');
    if (current !== url) {
      const loaded = this.waitForLoadEvent();
      const result = await this.send('Page.navigate', { url });
      if (result.errorText) {
        loaded.cancel();
        throw new Error(`navigation to ${url} failed: ${result.errorText}`);
      }
      if (result.loaderId) await loaded.promise;
      else loaded.cancel(); // same-document navigation: no load event
    }
    await this.waitForReadyState();
  }

  // ---------------------------------------------------------------- helpers

  send(method, params) {
    this.throwIfStopped();
    return chrome.debugger.sendCommand(this.target, method, params);
  }

  throwIfStopped() {
    if (this.stopped) throw this.controller.signal.reason;
  }

  async evaluate(expression) {
    const res = await this.send('Runtime.evaluate', { expression, returnByValue: true, userGesture: true });
    if (res.exceptionDetails) {
      throw new Error(res.exceptionDetails.exception?.description ?? res.exceptionDetails.text);
    }
    return res.result?.value;
  }

  /** Evaluates, retrying while the page is between documents (navigation in progress). */
  async retryEvaluate(expression, timeoutMs = LOAD_TIMEOUT_MS) {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      try {
        return await this.evaluate(expression);
      } catch (err) {
        if (this.stopped || Date.now() > deadline) throw err;
        await this.sleep(POLL_MS);
      }
    }
  }

  /** Polls for an element; returns the `pageLocate` result or null after the timeout. */
  async locate(selector, mode) {
    const expression = `(${pageLocate})(${JSON.stringify(selector)}, ${JSON.stringify(mode)})`;
    const deadline = Date.now() + SELECTOR_TIMEOUT_MS;
    for (;;) {
      let result = null;
      try {
        result = await this.evaluate(expression);
      } catch (err) {
        if (this.stopped) throw err;
        // Context destroyed by a navigation: keep polling.
      }
      if (result?.invalid) throw new Error(`invalid selector: ${selector}`);
      if (result) return result;
      if (Date.now() > deadline) return null;
      await this.sleep(POLL_MS);
    }
  }

  async waitForReadyState() {
    const deadline = Date.now() + LOAD_TIMEOUT_MS;
    while ((await this.retryEvaluate('document.readyState')) !== 'complete') {
      if (Date.now() > deadline) throw new Error('timed out waiting for the page to load');
      await this.sleep(POLL_MS);
    }
  }

  /** Returns a promise resolved by the next `Page.loadEventFired` (or on stop / timeout). */
  waitForLoadEvent() {
    let resolveFn;
    let timer;
    const promise = new Promise((resolve, reject) => {
      resolveFn = () => {
        clearTimeout(timer);
        this.loadWaiters.delete(resolveFn);
        resolve();
      };
      timer = setTimeout(() => {
        this.loadWaiters.delete(resolveFn);
        reject(new Error('timed out waiting for the page load event'));
      }, LOAD_TIMEOUT_MS);
      this.loadWaiters.add(resolveFn);
    });
    return {
      promise: promise.then(() => this.throwIfStopped()),
      cancel: () => {
        resolveFn();
        promise.catch(() => {});
      },
    };
  }

  /** Sleeps `ms`, rejecting with the stop reason as soon as playback is stopped. */
  sleep(ms) {
    const signal = this.controller.signal;
    if (signal.aborted) return Promise.reject(signal.reason);
    return new Promise((resolve, reject) => {
      const onAbort = () => {
        clearTimeout(timer);
        reject(signal.reason);
      };
      const timer = setTimeout(() => {
        signal.removeEventListener('abort', onAbort);
        resolve();
      }, ms);
      signal.addEventListener('abort', onAbort, { once: true });
    });
  }
}
