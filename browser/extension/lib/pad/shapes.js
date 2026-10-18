/**
 * Combo shapes. Each shape is one combo of a single colour:
 *
 * | type     | definition                                         |
 * |----------|----------------------------------------------------|
 * | `row`    | contains a full horizontal line (may have extras)  |
 * | `column` | contains a full vertical line (may have extras)    |
 * | `square` | exactly 9 orbs in a 3x3 block                      |
 * | `cross`  | exactly 5 orbs in a plus shape                     |
 * | `lshape` | exactly 5 orbs: 3 + 3 in lines sharing a corner    |
 * | `tpa`    | exactly 4 orbs in a straight line                  |
 */

export const SHAPE_TYPES = ['row', 'column', 'square', 'cross', 'lshape', 'tpa'];

export const SHAPE_LABELS = {
  row: 'Row',
  column: 'Column',
  square: '3x3 square',
  cross: 'Cross',
  lshape: 'L shape',
  tpa: '4 in a line',
};

/** Minimum number of orbs of the colour needed on the board for one shape. */
export function shapeSize(type, rows, cols) {
  switch (type) {
    case 'row': return cols;
    case 'column': return rows;
    case 'square': return 9;
    case 'cross':
    case 'lshape': return 5;
    case 'tpa': return 4;
    default: throw new Error(`Unknown shape: ${type}`);
  }
}

/** True when the shape can exist on a board of this size at all. */
export function shapeFits(type, rows, cols) {
  if (type === 'tpa') return rows >= 4 || cols >= 4;
  return rows >= 3 && cols >= 3;
}

/**
 * Tests whether a combo has a shape.
 * @param {string} type One of {@link SHAPE_TYPES}.
 * @param {number[]} cells Sorted cell indices of one combo.
 */
export function comboHasShape(type, cells, rows, cols) {
  const n = cells.length;
  switch (type) {
    case 'row': return n >= cols && hasFullLine(cells, rows, cols, true);
    case 'column': return n >= rows && hasFullLine(cells, rows, cols, false);
    case 'square': return n === 9 && boxSize(cells, cols) === '3x3';
    case 'cross': return n === 5 && boxSize(cells, cols) === '3x3' && isCross(cells, cols);
    case 'lshape': return n === 5 && boxSize(cells, cols) === '3x3' && isL(cells, cols);
    case 'tpa': {
      if (n !== 4) return false;
      const box = boxSize(cells, cols);
      return box === '1x4' || box === '4x1';
    }
    default: return false;
  }
}

function hasFullLine(cells, rows, cols, horizontal) {
  const lines = horizontal ? rows : cols;
  const per = horizontal ? cols : rows;
  const counts = new Array(lines).fill(0);
  for (const i of cells) counts[horizontal ? Math.floor(i / cols) : i % cols]++;
  return counts.some((count) => count === per);
}

/** "<height>x<width>" of the bounding box. */
function boxSize(cells, cols) {
  let r0 = Infinity;
  let r1 = -1;
  let c0 = Infinity;
  let c1 = -1;
  for (const i of cells) {
    const r = Math.floor(i / cols);
    const c = i % cols;
    r0 = Math.min(r0, r);
    r1 = Math.max(r1, r);
    c0 = Math.min(c0, c);
    c1 = Math.max(c1, c);
  }
  return `${r1 - r0 + 1}x${c1 - c0 + 1}`;
}

function isCross(cells, cols) {
  // In a 3x3 box, a plus is the middle row and middle column; sorted cells give the centre third.
  const set = new Set(cells);
  const centre = cells[2];
  return [centre - cols, centre + cols, centre - 1, centre + 1].every((j) => set.has(j));
}

function isL(cells, cols) {
  const set = new Set(cells);
  const top = Math.min(...cells.map((i) => Math.floor(i / cols)));
  const left = Math.min(...cells.map((i) => i % cols));
  for (const [cr, cc] of [[top, left], [top, left + 2], [top + 2, left], [top + 2, left + 2]]) {
    const dr = cr === top ? 1 : -1;
    const dc = cc === left ? 1 : -1;
    const want = [[0, 0], [dr, 0], [2 * dr, 0], [0, dc], [0, 2 * dc]];
    if (want.every(([r, c]) => set.has((cr + r) * cols + cc + c))) return true;
  }
  return false;
}

/**
 * All placements of a shape on the board: the target cells the solver tries to fill with the
 * goal colour. `exact` placements must not touch other matched orbs of the same colour.
 * @returns {Array<{cells: number[], exact: boolean}>}
 */
export function shapePlacements(type, rows, cols) {
  const out = [];
  const at = (r, c) => r * cols + c;
  const add = (cells, exact) => out.push({ cells: cells.sort((a, b) => a - b), exact });
  switch (type) {
    case 'row':
      for (let r = 0; r < rows; r++) add(Array.from({ length: cols }, (_, c) => at(r, c)), false);
      break;
    case 'column':
      for (let c = 0; c < cols; c++) add(Array.from({ length: rows }, (_, r) => at(r, c)), false);
      break;
    case 'square':
      for (let r = 0; r + 3 <= rows; r++) {
        for (let c = 0; c + 3 <= cols; c++) {
          const cells = [];
          for (let dr = 0; dr < 3; dr++) for (let dc = 0; dc < 3; dc++) cells.push(at(r + dr, c + dc));
          add(cells, true);
        }
      }
      break;
    case 'cross':
      for (let r = 1; r + 1 < rows; r++) {
        for (let c = 1; c + 1 < cols; c++) {
          add([at(r, c), at(r - 1, c), at(r + 1, c), at(r, c - 1), at(r, c + 1)], true);
        }
      }
      break;
    case 'lshape':
      for (let r = 0; r + 3 <= rows; r++) {
        for (let c = 0; c + 3 <= cols; c++) {
          for (const [cr, cc] of [[r, c], [r, c + 2], [r + 2, c], [r + 2, c + 2]]) {
            const dr = cr === r ? 1 : -1;
            const dc = cc === c ? 1 : -1;
            add([at(cr, cc), at(cr + dr, cc), at(cr + 2 * dr, cc), at(cr, cc + dc), at(cr, cc + 2 * dc)], true);
          }
        }
      }
      break;
    case 'tpa':
      for (let r = 0; r < rows; r++) {
        for (let c = 0; c + 4 <= cols; c++) add([0, 1, 2, 3].map((k) => at(r, c + k)), true);
      }
      for (let c = 0; c < cols; c++) {
        for (let r = 0; r + 4 <= rows; r++) add([0, 1, 2, 3].map((k) => at(r + k, c)), true);
      }
      break;
    default:
      throw new Error(`Unknown shape: ${type}`);
  }
  return out;
}
