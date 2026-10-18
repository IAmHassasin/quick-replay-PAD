/**
 * PAD solver runner for the dawnGlare simulator (https://pad.dawnglare.com/): reads the board
 * from the page DOM and performs a solved path as one continuous mouse drag through
 * `chrome.debugger` (trusted CDP input, like the script player).
 *
 * dawnGlare facts this relies on (read from its pad.js): every orb is a `#tiles .tile` element
 * with a `tilecolor` attribute; dragging uses jQuery UI draggable/droppable, and the held orb
 * swaps with every tile the dragged orb's centre enters. Small `.cornerblock` areas at the tile
 * corners suspend swapping, so a diagonal move through a corner does not swap a side neighbour.
 */

const PROTOCOL_VERSION = '1.3';
/** dawnGlare `tilecolor` → solver orb letter. */
const COLOR_LETTERS = {
  red: 'R', blue: 'B', green: 'G', light: 'L', dark: 'D', heart: 'H', jammer: 'J', poison: 'P', mortalpoison: 'M',
};
/**
 * Max distance in CSS pixels between two mouse events of a drag: 2 events per straight move,
 * 3 per diagonal one (90 px cells). Each event costs dawnGlare ~15-25 ms (jQuery UI re-measures
 * every tile on mousemove), so fewer events is what makes the drag fast. Measured on the live
 * simulator: 8 px steps took ~1 s for 10 moves, 45 px ~0.23 s at 10 ms per move, all correct.
 */
const DRAG_STEP_PX = 45;
const PRESS_SETTLE_MS = 30;
const RELEASE_SETTLE_MS = 40;
/** Time for the "started debugging" info bar to appear and move the page. */
const ATTACH_SETTLE_MS = 300;

/**
 * Runs inside the page (serialized). Scrolls the board into view and returns its tiles
 * ordered by their position on screen.
 */
function pageReadBoard() {
  const board = document.getElementById('board');
  const tiles = [...document.querySelectorAll('#tiles .tile')];
  if (!board || tiles.length === 0) return { found: false };
  board.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' });
  const items = tiles.map((tile) => {
    const r = tile.getBoundingClientRect();
    return {
      color: tile.getAttribute('tilecolor') ?? '',
      x: r.left + r.width / 2,
      y: r.top + r.height / 2,
      size: r.width,
      busy: tile.style.opacity !== '' && tile.style.opacity !== '1',
    };
  });
  return { found: true, items };
}

/**
 * Reads the dawnGlare board of a tab.
 * @returns {Promise<{text: string, rows: number, cols: number, centers: Array<{x: number, y: number}>}>}
 *   `text` is the board as letter rows; `centers[i]` is the viewport position of cell i.
 * @throws {Error} When the page has no dawnGlare board, orbs are still moving or an orb is
 *   not supported by the solver.
 */
export async function readBoard(tabId) {
  const [injection] = await chrome.scripting.executeScript({ target: { tabId, frameIds: [0] }, func: pageReadBoard });
  const page = injection?.result;
  if (!page?.found) throw new Error('No dawnGlare board found in this tab. Open https://pad.dawnglare.com/ first.');
  if (page.items.some((t) => t.busy)) throw new Error('The board is still resolving combos. Wait until it settles and try again.');
  if (page.items.some((t) => t.color === 'black')) {
    // Without skyfall, matched orbs stay empty ("black") until the board is reset.
    throw new Error('The board has empty cells. Press Reset (or Random) in dawnGlare, then solve again.');
  }
  return gridFromTiles(page.items);
}

/** Orders tiles into rows and columns by screen position. Exported for tests. */
export function gridFromTiles(items) {
  const size = items[0].size || 1;
  const near = (a, b) => Math.abs(a - b) < size / 2;
  const ys = distinct(items.map((t) => t.y), near);
  const xs = distinct(items.map((t) => t.x), near);
  const rows = ys.length;
  const cols = xs.length;
  if (rows * cols !== items.length) throw new Error(`The board tiles do not form a grid (${items.length} tiles, ${cols}x${rows}).`);
  const cells = new Array(rows * cols);
  const unsupported = new Set();
  for (const t of items) {
    const r = ys.findIndex((y) => near(y, t.y));
    const c = xs.findIndex((x) => near(x, t.x));
    const letter = COLOR_LETTERS[t.color];
    if (!letter) unsupported.add(t.color || '(none)');
    cells[r * cols + c] = { letter: letter ?? '?', x: t.x, y: t.y };
  }
  if (unsupported.size) throw new Error(`Unsupported orbs on the board: ${[...unsupported].join(', ')}.`);
  const text = Array.from({ length: rows }, (_, r) => cells.slice(r * cols, (r + 1) * cols).map((c) => c.letter).join('')).join('\n');
  return { text, rows, cols, centers: cells.map(({ x, y }) => ({ x, y })) };
}

/** Error for a drag stopped by the user (`name === 'DragStopped'`). */
function stoppedError() {
  const err = new Error('Drag stopped');
  err.name = 'DragStopped';
  return err;
}

function distinct(values, near) {
  const out = [];
  for (const v of [...values].sort((a, b) => a - b)) if (!out.some((o) => near(o, v))) out.push(v);
  return out;
}

/**
 * A debugger session on one tab for dragging. Attach before reading positions: Chrome's
 * "started debugging" bar can shift the page when the session starts.
 */
export class Dragger {
  constructor(tabId) {
    this.target = { tabId };
    this.controller = new AbortController();
    this.attached = false;
  }

  async attach() {
    await chrome.debugger.attach(this.target, PROTOCOL_VERSION);
    this.attached = true;
    this.attachedAt = performance.now();
  }

  /** Waits until the debugging bar had time to appear (no wait if solving took long enough). */
  async settled() {
    const left = this.attachedAt + ATTACH_SETTLE_MS - performance.now();
    if (left > 0) await this.sleep(left);
  }

  async detach() {
    if (!this.attached) return;
    this.attached = false;
    try {
      await chrome.debugger.detach(this.target);
    } catch {
      // Already detached (tab closed or the user dismissed the debugging bar).
    }
  }

  stop() {
    this.controller.abort(stoppedError());
  }

  /** Must be wired to `chrome.debugger.onDetach` by the owner. */
  handleDetach(reason) {
    this.attached = false;
    // "canceled_by_user": the user dismissed Chrome's debugging bar, i.e. a normal stop.
    this.controller.abort(reason === 'canceled_by_user'
      ? stoppedError()
      : new Error(`Debugger detached (${reason}). Drag cancelled.`));
  }

  /**
   * Presses on the first cell, moves through every cell centre of the path and releases.
   * @param {Array<{x: number, y: number}>} centers Viewport position of each cell.
   * @param {number[]} path Cell indices.
   * @param {number} msPerMove Time to move from one cell to the next.
   */
  async drag(centers, path, msPerMove) {
    const points = path.map((i) => centers[i]);
    const base = { button: 'left', buttons: 1, pointerType: 'mouse' };
    let pressed = false;
    // Where the pointer is; a stopped drag is released there, not at the end of the path.
    let at = points[0];
    try {
      await this.mouse({ type: 'mouseMoved', x: points[0].x, y: points[0].y, button: 'none', buttons: 0 });
      await this.mouse({ ...base, type: 'mousePressed', x: points[0].x, y: points[0].y, clickCount: 1 });
      pressed = true;
      await this.sleep(PRESS_SETTLE_MS);
      // Events follow a timeline: move i ends at start + i * msPerMove. When the page is slower
      // than that, events are sent back to back without sleeping.
      const start = performance.now();
      for (let i = 1; i < points.length; i++) {
        const from = points[i - 1];
        const to = points[i];
        const steps = Math.max(1, Math.ceil(Math.hypot(to.x - from.x, to.y - from.y) / DRAG_STEP_PX));
        for (let s = 1; s <= steps; s++) {
          const x = from.x + ((to.x - from.x) * s) / steps;
          const y = from.y + ((to.y - from.y) * s) / steps;
          await this.mouse({ ...base, type: 'mouseMoved', x, y });
          at = { x, y };
          const wait = start + (i - 1 + s / steps) * msPerMove - performance.now();
          if (wait > 0) await this.sleep(wait);
        }
      }
      await this.sleep(RELEASE_SETTLE_MS);
    } finally {
      if (pressed && this.attached) {
        await this.mouse({ ...base, type: 'mouseReleased', x: at.x, y: at.y, buttons: 0, clickCount: 1 }, true);
      }
    }
  }

  async mouse(params, evenIfStopped = false) {
    if (!evenIfStopped) this.throwIfStopped();
    await chrome.debugger.sendCommand(this.target, 'Input.dispatchMouseEvent', params);
  }

  throwIfStopped() {
    const { signal } = this.controller;
    if (signal.aborted) throw signal.reason;
  }

  sleep(ms) {
    return new Promise((resolve, reject) => {
      const { signal } = this.controller;
      if (signal.aborted) {
        reject(signal.reason);
        return;
      }
      const timer = setTimeout(() => {
        signal.removeEventListener('abort', onAbort);
        resolve();
      }, ms);
      const onAbort = () => {
        clearTimeout(timer);
        reject(signal.reason);
      };
      signal.addEventListener('abort', onAbort, { once: true });
    });
  }
}
