/**
 * Match, combo grouping, gravity and cascades for a PAD board (no skyfall).
 *
 * Rules (same as the dawnGlare simulator's `getMatches`): 3+ orbs of one colour in a straight
 * horizontal or vertical line are matched; matched orbs of the same colour that touch
 * orthogonally form one combo. Matched orbs are removed, the rest fall, and the board is matched
 * again until nothing matches. No new orbs fall in, so cascades are deterministic.
 */
import { EMPTY } from './board.js';

/**
 * Reusable simulator for one board size (keeps scratch buffers to avoid allocations in the
 * solver's hot loop).
 */
export class Matcher {
  constructor(rows, cols) {
    this.rows = rows;
    this.cols = cols;
    const n = rows * cols;
    this.work = new Uint8Array(n);
    this.matched = new Uint8Array(n);
    this.seen = new Uint8Array(n);
    this.stack = new Int32Array(n);
  }

  /**
   * Runs match → gravity → cascades on a copy of `cells`.
   * @param {Uint8Array} cells Board before matching; not modified.
   * @returns {{combos: Array<{color: number, cells: number[], pass: number}>, cells: Uint8Array}}
   *   Combos in match order (`pass` 0 = matched by the drag itself, 1+ = cascades), and the
   *   final board (`EMPTY` where orbs were removed). The returned `cells` is a scratch buffer,
   *   valid until the next call.
   */
  simulate(cells) {
    const { work } = this;
    work.set(cells);
    const combos = [];
    for (let pass = 0; ; pass++) {
      const found = this.findCombos(work, pass, combos);
      if (!found) break;
      this.removeMatched();
      this.applyGravity();
    }
    return { combos, cells: work };
  }

  /** Marks matched cells in `this.matched` and appends their combos; returns the number found. */
  findCombos(board, pass, out) {
    const { rows, cols, matched, seen, stack } = this;
    matched.fill(0);
    seen.fill(0);
    let any = false;
    for (let r = 0; r < rows; r++) {
      const base = r * cols;
      let c = 0;
      while (c < cols) {
        const color = board[base + c];
        let e = c + 1;
        while (e < cols && board[base + e] === color) e++;
        if (color !== EMPTY && e - c >= 3) {
          for (let k = c; k < e; k++) matched[base + k] = 1;
          any = true;
        }
        c = e;
      }
    }
    for (let c = 0; c < cols; c++) {
      let r = 0;
      while (r < rows) {
        const color = board[r * cols + c];
        let e = r + 1;
        while (e < rows && board[e * cols + c] === color) e++;
        if (color !== EMPTY && e - r >= 3) {
          for (let k = r; k < e; k++) matched[k * cols + c] = 1;
          any = true;
        }
        r = e;
      }
    }
    if (!any) return 0;

    let count = 0;
    const n = rows * cols;
    for (let start = 0; start < n; start++) {
      if (!matched[start] || seen[start]) continue;
      const color = board[start];
      const comboCells = [];
      let top = 0;
      stack[top++] = start;
      seen[start] = 1;
      while (top > 0) {
        const i = stack[--top];
        comboCells.push(i);
        const r = (i / cols) | 0;
        const c = i - r * cols;
        if (r > 0) top = this.visit(board, i - cols, color, top);
        if (r < rows - 1) top = this.visit(board, i + cols, color, top);
        if (c > 0) top = this.visit(board, i - 1, color, top);
        if (c < cols - 1) top = this.visit(board, i + 1, color, top);
      }
      comboCells.sort((a, b) => a - b);
      out.push({ color, cells: comboCells, pass });
      count++;
    }
    return count;
  }

  visit(board, j, color, top) {
    if (this.matched[j] && !this.seen[j] && board[j] === color) {
      this.seen[j] = 1;
      this.stack[top++] = j;
    }
    return top;
  }

  removeMatched() {
    const { work, matched } = this;
    for (let i = 0; i < work.length; i++) if (matched[i]) work[i] = EMPTY;
  }

  applyGravity() {
    const { rows, cols, work } = this;
    for (let c = 0; c < cols; c++) {
      let write = rows - 1;
      for (let r = rows - 1; r >= 0; r--) {
        const v = work[r * cols + c];
        if (v !== EMPTY) {
          work[write * cols + c] = v;
          write--;
        }
      }
      for (; write >= 0; write--) work[write * cols + c] = EMPTY;
    }
  }
}
