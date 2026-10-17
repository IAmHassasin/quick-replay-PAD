/*
 * Quick Replay recorder (content script).
 *
 * Injected on demand into the top frame of the recorded tab by the service worker
 * (`chrome.scripting.executeScript`) and re-injected after every navigation while
 * recording. It turns trusted user events into `quickreplay/v1` browser steps and sends
 * each one to the service worker together with a timestamp; the service worker computes
 * `delayBeforeMs`.
 */
(() => {
  if (globalThis.__quickReplayRecorder) return; // already active in this document

  const TYPE_DEBOUNCE_MS = 800;
  const SCROLL_DEBOUNCE_MS = 400;
  const RECORDED_KEYS = new Set(['Enter', 'Tab', 'Escape']);
  const NON_TEXT_INPUTS = new Set([
    'button', 'checkbox', 'color', 'date', 'datetime-local', 'file', 'hidden', 'image',
    'month', 'radio', 'range', 'reset', 'submit', 'time', 'week',
  ]);
  const CLICKABLE = 'a[href],button,input,select,textarea,label,summary,[role="button"],[role="link"],'
    + '[role="checkbox"],[role="tab"],[role="menuitem"],[role="option"],[onclick]';

  /** @type {{ el: Element, step: object, ts: number, timer: number } | null} */
  let pendingType = null;
  /** @type {{ step: object, ts: number, timer: number } | null} */
  let pendingScroll = null;
  /** Last text recorded per element, to avoid duplicate `type` steps (input + change). */
  const lastTyped = new WeakMap();

  // ---------------------------------------------------------------- selectors

  function isUnique(selector, el) {
    try {
      const found = document.querySelectorAll(selector);
      return found.length === 1 && found[0] === el;
    } catch {
      return false;
    }
  }

  function attr(name, value) {
    return `[${name}="${CSS.escape(value)}"]`;
  }

  /**
   * Builds a short selector that uniquely matches `el` in the document:
   * `#id`, `[data-testid]`, `[name]`, `[aria-label]`, then a short CSS path.
   * Returns undefined when no unique selector exists (shadow DOM, detached node...).
   */
  function selectorFor(el) {
    if (!el || el.nodeType !== Node.ELEMENT_NODE || el.getRootNode() !== document) return undefined;
    const tag = el.localName;
    const candidates = [];
    if (el.id) candidates.push(`#${CSS.escape(el.id)}`);
    for (const name of ['data-testid', 'data-test-id', 'data-test']) {
      const v = el.getAttribute(name);
      if (v) candidates.push(attr(name, v));
    }
    const nameAttr = el.getAttribute('name');
    if (nameAttr) candidates.push(`${tag}${attr('name', nameAttr)}`);
    const label = el.getAttribute('aria-label');
    if (label) candidates.push(`${tag}${attr('aria-label', label)}`);
    for (const c of candidates) if (isUnique(c, el)) return c;
    return cssPath(el);
  }

  function cssPath(el) {
    const parts = [];
    let cur = el;
    while (cur && cur.nodeType === Node.ELEMENT_NODE && cur !== document.documentElement) {
      if (cur !== el && cur.id && isUnique(`#${CSS.escape(cur.id)}`, cur)) {
        parts.unshift(`#${CSS.escape(cur.id)}`);
        break;
      }
      let seg = cur.localName;
      const parent = cur.parentElement;
      if (parent) {
        const same = Array.from(parent.children).filter((c) => c.localName === cur.localName);
        if (same.length > 1) seg += `:nth-of-type(${same.indexOf(cur) + 1})`;
      }
      parts.unshift(seg);
      const selector = parts.join(' > ');
      if (isUnique(selector, el)) return selector;
      cur = parent;
    }
    const selector = parts.join(' > ');
    return isUnique(selector, el) ? selector : undefined;
  }

  // ---------------------------------------------------------------- sending

  function send(step, ts) {
    chrome.runtime.sendMessage({ type: 'qr:step', step, ts }).catch((err) => {
      // The service worker is the only receiver; log so failures are visible in DevTools.
      console.warn('[Quick Replay] could not send recorded step:', err?.message ?? err);
    });
  }

  function flushType(sink = send) {
    if (!pendingType) return;
    const { el, step, ts, timer } = pendingType;
    clearTimeout(timer);
    pendingType = null;
    if (lastTyped.get(el) === step.text && !step.sensitive) return;
    lastTyped.set(el, step.text);
    sink(step, ts);
  }

  function flushScroll(sink = send) {
    if (!pendingScroll) return;
    const { step, ts, timer } = pendingScroll;
    clearTimeout(timer);
    pendingScroll = null;
    sink(step, ts);
  }

  /**
   * Flushes debounced steps so that ordering matches what the user did.
   * @param {(step: object, ts: number) => void} [sink] Receiver; defaults to messaging the service worker.
   */
  function flushAll(sink = send) {
    flushType(sink);
    flushScroll(sink);
  }

  // ---------------------------------------------------------------- handlers

  function editableTarget(target) {
    if (!(target instanceof Element)) return null;
    if (target instanceof HTMLTextAreaElement) return target;
    if (target instanceof HTMLInputElement) return NON_TEXT_INPUTS.has(target.type) ? null : target;
    if (target.isContentEditable) {
      let host = target;
      while (host.parentElement && host.parentElement.isContentEditable) host = host.parentElement;
      return host;
    }
    return null;
  }

  function onInput(event, immediate) {
    if (!event.isTrusted) return;
    const el = editableTarget(event.composedPath()[0] ?? event.target);
    if (!el) return;
    const selector = selectorFor(el);
    if (!selector) return; // cannot be replayed without a selector
    if (pendingType && pendingType.el !== el) flushType();
    flushScroll();
    const sensitive = el instanceof HTMLInputElement && el.type === 'password';
    const text = sensitive ? '' : el.isContentEditable ? el.innerText : el.value;
    const step = sensitive ? { type: 'type', selector, text: '', sensitive: true } : { type: 'type', selector, text };
    if (pendingType) clearTimeout(pendingType.timer);
    pendingType = { el, step, ts: Date.now(), timer: setTimeout(flushType, TYPE_DEBOUNCE_MS) };
    if (immediate) flushType();
  }

  function onClick(event) {
    if (!event.isTrusted || event.button !== 0) return;
    flushAll();
    const deepTarget = event.composedPath()[0] ?? event.target;
    const base = deepTarget instanceof Element ? deepTarget : event.target;
    const el = base instanceof Element ? (base.closest(CLICKABLE) ?? base) : null;
    const step = { type: 'click' };
    const selector = selectorFor(el);
    if (selector) step.selector = selector;
    step.x = Math.round(event.clientX);
    step.y = Math.round(event.clientY);
    step.button = 'left';
    step.clickCount = Math.max(1, event.detail || 1);
    send(step, Date.now());
  }

  function onKeyDown(event) {
    if (!event.isTrusted || !RECORDED_KEYS.has(event.key)) return;
    if (event.ctrlKey || event.altKey || event.metaKey || event.shiftKey || event.isComposing) return;
    flushAll();
    send({ type: 'key', key: event.key }, Date.now());
  }

  function onScroll(event) {
    if (event.target !== document) return; // only the window scroll position is recorded
    flushType();
    const step = { type: 'scroll', x: Math.round(window.scrollX), y: Math.round(window.scrollY) };
    if (pendingScroll) clearTimeout(pendingScroll.timer);
    pendingScroll = { step, ts: Date.now(), timer: setTimeout(flushScroll, SCROLL_DEBOUNCE_MS) };
  }

  const onInputEvent = (e) => onInput(e, false);
  const onChangeEvent = (e) => onInput(e, true);
  const listeners = [
    [document, 'click', onClick, true],
    [document, 'input', onInputEvent, true],
    [document, 'change', onChangeEvent, true],
    [document, 'keydown', onKeyDown, true],
    [window, 'scroll', onScroll, false],
    // Flush before the document goes away so the last typed text is not lost.
    [window, 'pagehide', () => flushAll(), true],
  ];
  for (const [target, type, fn, capture] of listeners) target.addEventListener(type, fn, capture);

  /**
   * Stops recording in this document.
   * @returns {Array<{step: object, ts: number}>} Steps that were still pending (debounced).
   */
  function stop() {
    const pending = [];
    flushAll((step, ts) => pending.push({ step, ts }));
    for (const [target, type, fn, capture] of listeners) target.removeEventListener(type, fn, capture);
    chrome.runtime.onMessage.removeListener(onMessage);
    delete globalThis.__quickReplayRecorder;
    return pending;
  }

  function onMessage(message, _sender, sendResponse) {
    if (message?.type === 'qr:recorderStop') {
      // Pending steps travel in the response so they are guaranteed to arrive before the
      // service worker finalizes the script.
      sendResponse({ ok: true, pending: stop() });
    }
  }
  chrome.runtime.onMessage.addListener(onMessage);

  globalThis.__quickReplayRecorder = { stop };
})();
