/**
 * Puzzle & Dragons board model shared by the browser extension and (later) the Android app.
 * Pure JavaScript: no DOM, no `chrome.*`, no dependencies.
 *
 * A board is `{ rows, cols, cells }` where `cells` is a `Uint8Array` of orb codes in row-major
 * order (index = row * cols + col, row 0 at the top).
 */

/** Orb letters, indexed by orb code. R Fire, B Water, G Wood, L Light, D Dark, H Heart, J Jammer, P Poison, M Mortal poison. */
export const ORB_LETTERS = ['R', 'B', 'G', 'L', 'D', 'H', 'J', 'P', 'M'];
export const ORB_NAMES = ['Fire', 'Water', 'Wood', 'Light', 'Dark', 'Heart', 'Jammer', 'Poison', 'Mortal poison'];
export const ORB_COUNT = ORB_LETTERS.length;
/** Code of a cell emptied by a match (only used while simulating). */
export const EMPTY = 255;

const CODE_BY_LETTER = new Map(ORB_LETTERS.map((letter, code) => [letter, code]));

export const MIN_SIZE = 3;
export const MAX_SIZE = 9;

/** Returns the orb code of a letter (case-insensitive), or -1. */
export function orbCode(letter) {
  return CODE_BY_LETTER.get(String(letter).toUpperCase()) ?? -1;
}

/**
 * Parses a board from letters, rows top to bottom. Whitespace and newlines are ignored when
 * `rows`/`cols` are given; otherwise each non-empty line is one row.
 * @param {string} text e.g. "LGHLBB\nLRDDGL\n..." or "LGHLBB LRDDGL ..."
 * @param {{rows?: number, cols?: number}} [size]
 * @returns {{rows: number, cols: number, cells: Uint8Array}}
 * @throws {Error} On unknown letters or a size mismatch.
 */
export function parseBoard(text, size = {}) {
  let { rows, cols } = size;
  const lines = String(text).split(/\r?\n/).map((l) => l.replace(/\s+/g, '')).filter(Boolean);
  const letters = lines.join('');
  if (rows === undefined || cols === undefined) {
    if (lines.length === 1) throw new Error('Board size is required for single-line input');
    rows = lines.length;
    cols = lines[0].length;
    if (lines.some((l) => l.length !== cols)) throw new Error('All board rows must have the same length');
  }
  checkSize(rows, cols);
  if (letters.length !== rows * cols) {
    throw new Error(`Expected ${rows * cols} orbs for a ${cols}x${rows} board, got ${letters.length}`);
  }
  const cells = new Uint8Array(rows * cols);
  for (let i = 0; i < letters.length; i++) {
    const code = orbCode(letters[i]);
    if (code < 0) throw new Error(`Unknown orb letter "${letters[i]}" at position ${i + 1}`);
    cells[i] = code;
  }
  return { rows, cols, cells };
}

/** Formats a board (or a cells array of the same size) as letter rows; empty cells are ".". */
export function formatBoard(board, cells = board.cells) {
  const lines = [];
  for (let r = 0; r < board.rows; r++) {
    let line = '';
    for (let c = 0; c < board.cols; c++) {
      const code = cells[r * board.cols + c];
      line += code === EMPTY ? '.' : ORB_LETTERS[code];
    }
    lines.push(line);
  }
  return lines.join('\n');
}

/** Number of orbs of each code. */
export function countOrbs(cells) {
  const counts = new Array(ORB_COUNT).fill(0);
  for (const code of cells) if (code !== EMPTY) counts[code]++;
  return counts;
}

export function checkSize(rows, cols) {
  for (const [name, value] of [['rows', rows], ['cols', cols]]) {
    if (!Number.isInteger(value) || value < MIN_SIZE || value > MAX_SIZE) {
      throw new Error(`Board ${name} must be an integer from ${MIN_SIZE} to ${MAX_SIZE}`);
    }
  }
}

/** "r1c1" style label (1-based) for a cell index. */
export function cellLabel(index, cols) {
  return `r${Math.floor(index / cols) + 1}c${(index % cols) + 1}`;
}

/**
 * Applies a drag path: the orb at `path[0]` is held and swapped with every following cell.
 * @param {Uint8Array} cells Not modified.
 * @param {number[]} path Cell indices; consecutive cells must be neighbours (not checked here).
 * @returns {Uint8Array} The board after the drag, before any match.
 */
export function applyPath(cells, path) {
  const out = cells.slice();
  for (let i = 1; i < path.length; i++) {
    const a = path[i - 1];
    const b = path[i];
    const held = out[a];
    out[a] = out[b];
    out[b] = held;
  }
  return out;
}
