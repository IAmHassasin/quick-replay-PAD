/**
 * Match-style tests: for every goal type the solver must solve a board that has the orbs, reject
 * a board that does not (from orb counts, without searching) and report an unmet goal when the
 * move budget is too small. Shape definitions, goal combinations, other board sizes and
 * orthogonal-only drags are covered too. The Android port has the same tests in
 * `android/app/src/test/java/dev/quickreplay/PadMatchStyleTest.kt`.
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { applyPath, orbCode, parseBoard } from '../../extension/lib/pad/board.js';
import { Matcher } from '../../extension/lib/pad/matcher.js';
import {
  SHAPE_TYPES, blobPlacements, comboHasShape, rectanglePlacements, shapeFits, shapePlacements, shapeSize,
} from '../../extension/lib/pad/shapes.js';
import { goalLabel, normalizeGoals, solve } from '../../extension/lib/pad/solver.js';

// Fixture boards, rows top to bottom. DOC, SHOT, RICH and R3 are the boards of the shared
// vectors (orb counts in the comments); WIDE and BIG exercise other board sizes.
const DOC = 'LGHLBB\nLRDDGL\nBRRLRR\nHBRGBL\nLBBRRD'; // R8 B7 G3 L7 D3 H2
const SHOT = 'LHDGHL\nHDLBHR\nGLBRGG\nGGBHBG\nHGLHBH'; // R2 B5 G8 L5 D2 H8
const RICH = 'DRDBDG\nLDHDRD\nBDGLDB\nRHDBGL\nGLRHBD'; // D10
const R3 = 'HRBGDL\nRRLBGD\nLBDRHB\nGDRLBR\nBLHGRD'; // R7 B6
const WIDE = 'LRBLG\nGLHRB\nRBLGL\nHGRBD'; // 5 columns x 4 rows, L5
const BIG = 'RGBLDHR\nBRGLHDB\nGBRLDHG\nLGBRHDL\nDLGBRHD\nHDLGBRH'; // 7 columns x 6 rows, R7 L6

// One 1000-wide round and no wall-clock limit: the same result on every machine.
const OPTIONS = { timeLimitMs: 60_000, maxBeamWidth: 1000 };

const row = (i, cols) => Math.floor(i / cols);
const col = (i, cols) => i % cols;

/** Every step is a move to a neighbouring cell (orthogonal only when `diagonals` is false). */
function assertContinuous(path, cols, diagonals) {
  assert.ok(path.length >= 2, 'a path holds at least two cells');
  for (let i = 1; i < path.length; i++) {
    const dr = Math.abs(row(path[i], cols) - row(path[i - 1], cols));
    const dc = Math.abs(col(path[i], cols) - col(path[i - 1], cols));
    const ok = diagonals ? dr <= 1 && dc <= 1 && dr + dc > 0 : dr + dc === 1;
    assert.ok(ok, `step ${i} from cell ${path[i - 1]} to ${path[i]} is not a ${diagonals ? '' : 'orthogonal '}move`);
  }
}

/** Replays the path and returns the combos of match -> gravity -> cascades (no skyfall). */
function replay(board, result) {
  const afterDrag = applyPath(board.cells, result.path);
  assert.deepEqual(afterDrag, result.afterDrag, 'afterDrag must be the board after replaying the path');
  return new Matcher(board.rows, board.cols).simulate(afterDrag).combos;
}

/** Combos of one colour that have the shape, re-checked from the raw simulated cells. */
function shaped(combos, type, color, board) {
  return combos.filter((c) => c.color === orbCode(color) && comboHasShape(type, c.cells, board.rows, board.cols));
}

/** Solves and checks the contract of a solved result; returns the re-simulated combos. */
function assertSolved(text, goals, options = OPTIONS) {
  const board = parseBoard(text);
  const result = solve(board, goals, options);
  assert.equal(result.status, 'solved', `${JSON.stringify(goals)}: ${JSON.stringify(result.goals)}`);
  assert.deepEqual(result.reasons, []);
  assert.deepEqual(result.goals.map((g) => g.met), goals.map(() => true));
  assert.equal(result.moves, result.path.length - 1);
  assertContinuous(result.path, board.cols, options.diagonals ?? true);
  const combos = replay(board, result);
  assert.equal(combos.length, result.combos.length, 'reported combos match the simulation');
  return { board, result, combos };
}

/** A single shape goal is solved with exactly one combo of that colour and shape. */
function assertShapeGoal(text, type, color, options = OPTIONS) {
  const { board, result, combos } = assertSolved(text, [{ type, color }], options);
  const found = shaped(combos, type, color, board);
  assert.equal(found.length, 1, `expected exactly one ${type} of ${color}, got ${found.length}`);
  assert.equal(result.goals[0].met, true);
  assert.ok(result.combos.some((c) => c.color === color && c.shapes.includes(type)), 'the reported combo lists the shape');
  return { board, result, combo: found[0] };
}

/** Impossible from orb counts or board size: no search, no path, every goal unmet. */
function assertImpossible(text, goals, reasons) {
  const board = parseBoard(text);
  const result = solve(board, goals, OPTIONS);
  assert.equal(result.status, 'impossible', JSON.stringify(goals));
  assert.deepEqual(result.reasons, reasons);
  assert.deepEqual(result.goals.map((g) => g.met), goals.map(() => false));
  assert.deepEqual(result.path, []);
  assert.equal(result.moves, 0);
  assert.equal(result.afterDrag, null);
  assert.equal(result.afterMatch, null);
  assert.equal(result.stats.evaluated, 0, 'impossible goals are rejected without searching');
  assert.equal(result.stats.rounds, 0);
}

/** Enough orbs, but a single move cannot make the goal: unmet, with the best one-move path. */
function assertUnmet(text, goals) {
  const board = parseBoard(text);
  const result = solve(board, goals, { ...OPTIONS, maxMoves: 1 });
  assert.equal(result.status, 'partial', JSON.stringify(goals));
  assert.deepEqual(result.reasons, []);
  assert.deepEqual(result.goals.map((g) => g.met), goals.map(() => false));
  assert.equal(result.moves, 1);
  assert.ok(result.stats.evaluated > 0, 'the board was searched');
  assertContinuous(result.path, board.cols, true);
  replay(board, result);
}

/** The cells of one full line of the board, if the combo contains one. */
function fullLine(combo, board, horizontal) {
  const lines = horizontal ? board.rows : board.cols;
  const per = horizontal ? board.cols : board.rows;
  for (let line = 0; line < lines; line++) {
    const cells = combo.cells.filter((i) => (horizontal ? row(i, board.cols) : col(i, board.cols)) === line);
    if (cells.length === per) return cells;
  }
  return null;
}

// --- Solved: one case per goal type, checking the shape from the simulated cells ---------------

test('row: a full row of Light on the doc board', () => {
  const { board, combo } = assertShapeGoal(DOC, 'row', 'L');
  assert.ok(fullLine(combo, board, true), 'the combo contains one full row');
  assert.ok(combo.cells.length >= board.cols);
});

test('column: a full column of Fire on the r3 board', () => {
  const { board, combo } = assertShapeGoal(R3, 'column', 'R');
  assert.ok(fullLine(combo, board, false), 'the combo contains one full column');
  assert.ok(combo.cells.length >= board.rows);
});

test('square: exactly 9 Dark orbs in a 3x3 block on the rich board', () => {
  const { board, combo } = assertShapeGoal(RICH, 'square', 'D');
  assert.equal(combo.cells.length, 9);
  const top = combo.cells[0];
  const block = [0, 1, 2].flatMap((dr) => [0, 1, 2].map((dc) => top + dr * board.cols + dc));
  assert.deepEqual(combo.cells, block);
});

test('cross: exactly 5 Heart orbs in a plus on the screenshot board', () => {
  const { board, combo } = assertShapeGoal(SHOT, 'cross', 'H');
  assert.equal(combo.cells.length, 5);
  const centre = combo.cells[2];
  assert.deepEqual(combo.cells, [centre - board.cols, centre - 1, centre, centre + 1, centre + board.cols]);
});

test('lshape: exactly 5 Water orbs, 3 + 3 sharing a corner, on the screenshot board', () => {
  const { board, combo } = assertShapeGoal(SHOT, 'lshape', 'B');
  assert.equal(combo.cells.length, 5);
  const rows = new Map();
  const cols = new Map();
  for (const i of combo.cells) {
    rows.set(row(i, board.cols), (rows.get(row(i, board.cols)) ?? 0) + 1);
    cols.set(col(i, board.cols), (cols.get(col(i, board.cols)) ?? 0) + 1);
  }
  const armRow = [...rows].find(([, n]) => n === 3)?.[0];
  const armCol = [...cols].find(([, n]) => n === 3)?.[0];
  assert.notEqual(armRow, undefined, 'three orbs in one row');
  assert.notEqual(armCol, undefined, 'three orbs in one column');
  assert.ok(combo.cells.includes(armRow * board.cols + armCol), 'the arms share their corner orb');
});

test('tpa: exactly 4 Water orbs in a straight line on the r3 board', () => {
  const { board, combo } = assertShapeGoal(R3, 'tpa', 'B');
  assert.equal(combo.cells.length, 4);
  const [first] = combo.cells;
  const horizontal = [0, 1, 2, 3].map((k) => first + k);
  const vertical = [0, 1, 2, 3].map((k) => first + k * board.cols);
  const inLine = JSON.stringify(combo.cells) === JSON.stringify(horizontal)
    ? horizontal.every((i) => row(i, board.cols) === row(first, board.cols))
    : JSON.stringify(combo.cells) === JSON.stringify(vertical);
  assert.ok(inLine, `cells ${combo.cells} are not 4 in a line`);
});

test('combos: at least 7 combos on the doc board', () => {
  const { result, combos } = assertSolved(DOC, [{ type: 'combos', count: 7 }]);
  assert.ok(result.combos.length >= 7, `${result.combos.length} combos`);
  assert.equal(result.goals[0].label, '7+ combos');
  for (const combo of combos) assert.ok(combo.cells.length >= 3, 'every combo has at least 3 orbs');
});

// --- Impossible and unmet: every goal type ------------------------------------------------------

test('impossible: every goal type is rejected from orb counts without searching', () => {
  assertImpossible(DOC, [{ type: 'row', color: 'D' }], ['Dark: the goals need 6 orbs, the board has 3']);
  assertImpossible(DOC, [{ type: 'column', color: 'H' }], ['Heart: the goals need 5 orbs, the board has 2']);
  assertImpossible(DOC, [{ type: 'square', color: 'D' }], ['Dark: the goals need 9 orbs, the board has 3']);
  assertImpossible(DOC, [{ type: 'cross', color: 'H' }], ['Heart: the goals need 5 orbs, the board has 2']);
  assertImpossible(DOC, [{ type: 'lshape', color: 'G' }], ['Wood: the goals need 5 orbs, the board has 3']);
  assertImpossible(DOC, [{ type: 'tpa', color: 'H' }], ['Heart: the goals need 4 orbs, the board has 2']);
  // R8 B7 G3 L7 D3 H2: floor(n / 3) per colour = 2 + 2 + 1 + 2 + 1 + 0.
  assertImpossible(DOC, [{ type: 'combos', count: 9 }], ['9+ combos: at most 8 combos are possible on this board']);
});

test('unmet: with enough orbs but one move, every goal type ends unmet with its best path', () => {
  assertUnmet(DOC, [{ type: 'row', color: 'L' }]);
  assertUnmet(R3, [{ type: 'column', color: 'R' }]);
  assertUnmet(RICH, [{ type: 'square', color: 'D' }]);
  assertUnmet(SHOT, [{ type: 'cross', color: 'H' }]);
  assertUnmet(SHOT, [{ type: 'lshape', color: 'B' }]);
  assertUnmet(R3, [{ type: 'tpa', color: 'B' }]);
  assertUnmet(DOC, [{ type: 'combos', count: 7 }]);
});

// --- Shape definitions ----------------------------------------------------------------------------

const ROWS = 5;
const COLS = 6;
const at = (r, c) => r * COLS + c;
const sorted = (cells) => [...cells].sort((a, b) => a - b);

test('square is exactly 9 orbs in a 3x3 block', () => {
  const square = [1, 2, 3].flatMap((r) => [2, 3, 4].map((c) => at(r, c)));
  assert.ok(comboHasShape('square', square, ROWS, COLS));
  assert.ok(!comboHasShape('square', sorted([...square, at(4, 2)]), ROWS, COLS), '10 orbs containing a 3x3 block');
  assert.ok(!comboHasShape('square', sorted([...square, at(4, 3)]), ROWS, COLS), '10 orbs, extra below the middle');
  assert.ok(!comboHasShape('square', square.slice(1), ROWS, COLS), '8 orbs');
  const wide = [1, 2, 3].flatMap((r) => [2, 3, 4].map((c) => at(r, c))).filter((i) => i !== at(2, 3)).concat(at(2, 5));
  assert.ok(!comboHasShape('square', sorted(wide), ROWS, COLS), '9 orbs in a 3x4 box');
  for (const type of ['cross', 'lshape', 'tpa']) assert.ok(!comboHasShape(type, square, ROWS, COLS), `a square is not a ${type}`);
});

test('cross is exactly 5 orbs in a plus', () => {
  const cross = [at(1, 2), at(2, 1), at(2, 2), at(2, 3), at(3, 2)];
  assert.ok(comboHasShape('cross', cross, ROWS, COLS));
  assert.ok(!comboHasShape('cross', sorted([...cross, at(1, 1)]), ROWS, COLS), 'a cross with one extra orb');
  assert.ok(!comboHasShape('cross', sorted([...cross, at(2, 4)]), ROWS, COLS), 'a cross with a longer arm');
  assert.ok(!comboHasShape('cross', [at(1, 1), at(1, 2), at(1, 3), at(2, 2), at(3, 2)], ROWS, COLS), 'a T is not a cross');
  assert.ok(!comboHasShape('cross', [at(1, 1), at(2, 1), at(3, 1), at(3, 2), at(3, 3)], ROWS, COLS), 'an L is not a cross');
  for (const type of ['square', 'lshape', 'tpa']) assert.ok(!comboHasShape(type, cross, ROWS, COLS), `a cross is not a ${type}`);
});

test('lshape is exactly 5 orbs, 3 + 3 in lines sharing a corner, in any orientation', () => {
  const corners = [
    [at(0, 0), at(1, 0), at(2, 0), at(2, 1), at(2, 2)], // corner bottom-left
    [at(0, 0), at(0, 1), at(0, 2), at(1, 0), at(2, 0)], // corner top-left
    [at(0, 0), at(0, 1), at(0, 2), at(1, 2), at(2, 2)], // corner top-right
    [at(0, 2), at(1, 2), at(2, 0), at(2, 1), at(2, 2)], // corner bottom-right
  ];
  for (const l of corners) {
    assert.ok(comboHasShape('lshape', l, ROWS, COLS), `L ${l}`);
    assert.ok(!comboHasShape('cross', l, ROWS, COLS));
    assert.ok(!comboHasShape('tpa', l, ROWS, COLS));
  }
  assert.ok(!comboHasShape('lshape', [at(0, 0), at(0, 1), at(0, 2), at(1, 1), at(2, 1)], ROWS, COLS), 'a T is not an L');
  assert.ok(!comboHasShape('lshape', sorted([...corners[0], at(1, 1)]), ROWS, COLS), 'an L with one extra orb');
  assert.ok(!comboHasShape('lshape', [at(0, 0), at(0, 1), at(0, 2), at(0, 3), at(1, 0)], ROWS, COLS), '4 + 2 is not 3 + 3');
  assert.ok(!comboHasShape('lshape', [at(0, 0), at(1, 0), at(2, 0), at(2, 1), at(2, 2), at(2, 3)], ROWS, COLS), '3 + 4 is not an L');
});

test('tpa is exactly 4 orbs in a straight line', () => {
  assert.ok(comboHasShape('tpa', [at(4, 1), at(4, 2), at(4, 3), at(4, 4)], ROWS, COLS), 'horizontal');
  assert.ok(comboHasShape('tpa', [at(0, 5), at(1, 5), at(2, 5), at(3, 5)], ROWS, COLS), 'vertical');
  assert.ok(!comboHasShape('tpa', [at(4, 0), at(4, 1), at(4, 2), at(4, 3), at(4, 4)], ROWS, COLS), '5 in a line');
  assert.ok(!comboHasShape('tpa', [at(4, 1), at(4, 2), at(4, 3)], ROWS, COLS), '3 in a line');
  assert.ok(!comboHasShape('tpa', [at(0, 0), at(0, 1), at(1, 0), at(1, 1)], ROWS, COLS), 'a 2x2 block');
  assert.ok(!comboHasShape('tpa', [at(0, 0), at(0, 1), at(0, 2), at(1, 2)], ROWS, COLS), '3 + 1 bent');
  assert.ok(!comboHasShape('tpa', [at(0, 0), at(0, 1), at(0, 2), at(0, 3), at(1, 0), at(1, 1), at(1, 2), at(1, 3)], ROWS, COLS), 'two rows of 4');
});

test('row and column contain a full line and accept extra orbs', () => {
  const fullRow = [0, 1, 2, 3, 4, 5];
  assert.ok(comboHasShape('row', fullRow, ROWS, COLS));
  assert.ok(comboHasShape('row', [...fullRow, at(1, 0), at(2, 0)], ROWS, COLS), 'a row plus a column stub');
  assert.ok(!comboHasShape('row', [0, 1, 2, 3, 4], ROWS, COLS), '5 of 6 is not a row');
  assert.ok(!comboHasShape('row', [at(0, 0), at(0, 1), at(0, 2), at(1, 2), at(1, 3), at(1, 4)], ROWS, COLS), '6 orbs on two rows');
  assert.ok(!comboHasShape('column', fullRow, ROWS, COLS));
  const fullColumn = [at(0, 2), at(1, 2), at(2, 2), at(3, 2), at(4, 2)];
  assert.ok(comboHasShape('column', fullColumn, ROWS, COLS), 'a column needs 5 orbs on a 6x5 board');
  assert.ok(comboHasShape('column', sorted([...fullColumn, at(2, 3), at(2, 4)]), ROWS, COLS), 'a column plus a row stub');
  assert.ok(!comboHasShape('column', fullColumn.slice(1), ROWS, COLS), '4 of 5 is not a column');
  assert.ok(!comboHasShape('row', fullColumn, ROWS, COLS));
  // Full lines are not "exact" shapes of their size.
  assert.ok(!comboHasShape('tpa', fullColumn, ROWS, COLS));
  assert.ok(!comboHasShape('square', [...fullRow, 6, 7, 8], ROWS, COLS), '9 orbs in a 2x6 box');
});

test('shape sizes, fits and placements follow the board size', () => {
  assert.deepEqual([shapeSize('row', 4, 5), shapeSize('column', 4, 5)], [5, 4]);
  assert.deepEqual([shapeSize('row', 6, 7), shapeSize('column', 6, 7)], [7, 6]);
  assert.equal(shapeSize('square', 4, 5), 9);
  assert.equal(shapeSize('tpa', 6, 7), 4);
  for (const type of SHAPE_TYPES) assert.ok(shapeFits(type, 4, 5) && shapeFits(type, 6, 7), `${type} fits 5x4 and 7x6`);
  assert.ok(!shapeFits('tpa', 3, 3), '4 in a line needs a side of 4');
  assert.ok(shapeFits('tpa', 3, 4) && shapeFits('tpa', 4, 3));
  assert.ok(shapeFits('square', 3, 3) && shapeFits('cross', 3, 3) && shapeFits('lshape', 3, 3));
  // Placements per board: rows, columns, 3x3 windows, 4 Ls per window, 4-lines both ways.
  assert.equal(shapePlacements('row', 4, 5).length, 4);
  assert.equal(shapePlacements('column', 4, 5).length, 5);
  assert.equal(shapePlacements('square', 4, 5).length, 2 * 3);
  assert.equal(shapePlacements('lshape', 4, 5).length, 2 * 3 * 4);
  assert.equal(shapePlacements('tpa', 4, 5).length, 4 * 2 + 5 * 1);
  assert.equal(shapePlacements('row', 6, 7).length, 6);
  assert.equal(shapePlacements('column', 6, 7).length, 7);
  assert.equal(shapePlacements('cross', 6, 7).length, 4 * 5);
  for (const [rows, cols] of [[4, 5], [6, 7]]) {
    for (const type of SHAPE_TYPES) {
      for (const { cells } of shapePlacements(type, rows, cols)) assert.ok(comboHasShape(type, cells, rows, cols), `${type} ${cells} on ${cols}x${rows}`);
    }
  }
  // On a 4-row board a full column is also 4 in a line.
  assert.ok(comboHasShape('column', [0, 5, 10, 15], 4, 5));
  assert.ok(comboHasShape('tpa', [0, 5, 10, 15], 4, 5));
});

// --- Goal combinations ----------------------------------------------------------------------------

test('two goals of different kinds are both met: row L + 5 combos', () => {
  const { board, result, combos } = assertSolved(DOC, [{ type: 'row', color: 'L' }, { type: 'combos', count: 5 }]);
  assert.deepEqual(result.goals.map((g) => g.label), ['Row of Light', '5+ combos']);
  assert.equal(shaped(combos, 'row', 'L', board).length, 1);
  assert.ok(combos.length >= 5);
});

test('two shape goals of different colours are both met: L B + row G', () => {
  const { board, combos } = assertSolved(SHOT, [{ type: 'lshape', color: 'B' }, { type: 'row', color: 'G' }]);
  const l = shaped(combos, 'lshape', 'B', board);
  assert.equal(l.length, 1);
  assert.equal(l[0].cells.length, 5);
  assert.equal(shaped(combos, 'row', 'G', board).length, 1);
});

test('two goals of one colour add up their orbs', () => {
  // 6 + 5 Light needed, 7 on the board.
  assertImpossible(DOC, [{ type: 'row', color: 'L' }, { type: 'cross', color: 'L' }], ['Light: the goals need 11 orbs, the board has 7']);
  // Two rows of Fire: 12 needed, 7 on the board.
  assertImpossible(R3, [{ type: 'row', color: 'R' }, { type: 'row', color: 'R' }], ['Fire: the goals need 12 orbs, the board has 7']);
  // Every goal is reported, including the one that could be met on its own.
  assertImpossible(DOC, [{ type: 'combos', count: 3 }, { type: 'square', color: 'D' }], ['Dark: the goals need 9 orbs, the board has 3']);
});

// --- Other board sizes ----------------------------------------------------------------------------

test('5x4 board: a row needs 5 orbs and a column 4', () => {
  const { board, combo } = assertShapeGoal(WIDE, 'row', 'L');
  assert.equal(board.rows, 4);
  assert.equal(board.cols, 5);
  assert.equal(fullLine(combo, board, true).length, 5);
  const column = assertShapeGoal(WIDE, 'column', 'L');
  assert.equal(fullLine(column.combo, column.board, false).length, 4);
});

test('7x6 board: a column needs 6 orbs and a row 7', () => {
  const { board, combo } = assertShapeGoal(BIG, 'column', 'L');
  assert.equal(board.rows, 6);
  assert.equal(board.cols, 7);
  assert.equal(fullLine(combo, board, false).length, 6);
  const r = assertShapeGoal(BIG, 'row', 'R');
  assert.equal(fullLine(r.combo, r.board, true).length, 7);
});

test('3x3 board: 4 in a line does not fit', () => {
  assertImpossible('RBG\nLDH\nRRR', [{ type: 'tpa', color: 'R' }], ['4 in a line of Fire: does not fit on a 3x3 board']);
});

// --- Diagonals off --------------------------------------------------------------------------------

test('without diagonals an exact shape is still made with orthogonal moves only', () => {
  const { result, combo } = assertShapeGoal(SHOT, 'cross', 'H', { ...OPTIONS, diagonals: false });
  assert.equal(combo.cells.length, 5);
  assertContinuous(result.path, 6, false);
  // Diagonal moves are allowed by default and give a shorter path here.
  const withDiagonals = solve(parseBoard(SHOT), [{ type: 'cross', color: 'H' }], OPTIONS);
  assert.ok(withDiagonals.moves <= result.moves);
});

test('without diagonals a full row is still made with orthogonal moves only', () => {
  const { result } = assertShapeGoal(DOC, 'row', 'L', { ...OPTIONS, diagonals: false });
  assertContinuous(result.path, 6, false);
});

// --- blob: one combo of a colour with at least N connected orbs (REHA-11) ------------------------

// Boards of tools/pad-research/proposed-test-cases.json (REHA-4): 30 orbs, no initial match.
const BLOB = 'RRBRRG\nGLRDHB\nBGLDHL\nHDBGLR\nLBHGDB'; // R6 B6 G5 L6 D4 H4
const THREE = 'RLRRBB\nGDHBHD\nHGGDGL\nLHBLDR\nDBLHRG'; // R5 B5 G5 L5 D5 H5
const RBG = 'RBGRBG\nBGRBGR\nGRBGRB\nRBGRBG\nLDHLDH'; // R8 B8 G8 L2 D2 H2
const TWICE = 'RRBRGL\nGRLHBD\nRHRBLG\nBLGDHB\nDGHLRH'; // R7 B5 G5 L5 D3 H5

const blob = (color, min) => ({ type: 'blob', color, min });

/** Combos of one colour with at least `min` orbs, re-checked from the raw simulated cells. */
function blobs(combos, color, min) {
  return combos.filter((c) => c.color === orbCode(color) && c.cells.length >= min);
}

test('blob: 5+ Fire connected on the blob board in one move', () => {
  const { result, combos } = assertSolved(BLOB, [blob('R', 5)]);
  assert.deepEqual(result.goals, [{ label: '5+ Fire connected', met: true }]);
  assert.equal(result.moves, 1, 'the R at r1c2 moved up completes R x5 in row 0');
  assert.equal(blobs(combos, 'R', 5).length, 1);
});

test('blob: a fixed colour set is one blob goal per colour (min 3)', () => {
  const { result, combos } = assertSolved(THREE, [blob('R', 3), blob('B', 3)]);
  assert.deepEqual(result.goals.map((g) => g.label), ['3+ Fire connected', '3+ Water connected']);
  assert.equal(blobs(combos, 'R', 3).length >= 1, true);
  assert.equal(blobs(combos, 'B', 3).length >= 1, true);
});

test('blob: a repeated goal needs separate combos of the colour', () => {
  const { combos } = assertSolved(TWICE, [blob('R', 3), blob('R', 3)]);
  assert.ok(blobs(combos, 'R', 3).length >= 2, 'two separate Fire combos');
});

test('blob: impossible from orb counts without searching', () => {
  assertImpossible(BLOB, [blob('H', 5)], ['Heart: the goals need 5 orbs, the board has 4']);
  assertImpossible(RBG, [blob('L', 3), blob('D', 3)], [
    'Light: the goals need 3 orbs, the board has 2',
    'Dark: the goals need 3 orbs, the board has 2',
  ]);
  assertImpossible(TWICE, [blob('D', 3), blob('D', 3)], ['Dark: the goals need 6 orbs, the board has 3']);
  // Blob orbs add up with other goals of the colour.
  assertImpossible(BLOB, [{ type: 'row', color: 'R' }, blob('R', 3)], ['Fire: the goals need 9 orbs, the board has 6']);
});

test('blob: unmet with one move when two separate combos are needed', () => {
  const board = parseBoard(TWICE);
  const goals = [blob('R', 3), blob('R', 3)];
  const result = solve(board, goals, { ...OPTIONS, maxMoves: 1 });
  // One move makes one Fire combo; that combo cannot count for the second goal too.
  assert.equal(result.status, 'partial');
  assert.deepEqual(result.goals.map((g) => g.met), [true, false]);
  assert.equal(result.moves, 1);
  assertContinuous(result.path, board.cols, true);
  assert.equal(blobs(replay(board, result), 'R', 3).length, 1);
  // A single goal of a size one move cannot reach stays unmet.
  assertUnmet(TWICE, [blob('R', 6)]);
});

test('blob: min must be an integer from 3 to the board size', () => {
  for (const min of [2, 0, -3, 3.5, '5', undefined, null]) {
    assert.throws(() => normalizeGoals([blob('R', min)]), /Goal 1: blob size must be an integer >= 3/, String(min));
  }
  assert.throws(() => normalizeGoals([{ type: 'blob', min: 5 }]), /Goal 1: unknown colour/);
  // Without a board the limit is the largest board (9x9).
  assert.throws(() => normalizeGoals([blob('R', 82)]), /at most 81/);
  assert.deepEqual(normalizeGoals([blob('R', 81)]), [{ type: 'blob', color: 0, min: 81 }]);
  // With a board: at most rows x cols orbs.
  assert.deepEqual(normalizeGoals([blob('H', 30)], { rows: 5, cols: 6 }), [{ type: 'blob', color: 5, min: 30 }]);
  assert.throws(() => normalizeGoals([blob('H', 31)], { rows: 5, cols: 6 }), /Goal 1: blob size must be at most 30 on a 6x5 board/);
  assert.throws(() => solve(parseBoard(BLOB), [blob('R', 31)], OPTIONS), /at most 30/);
  assert.equal(goalLabel({ type: 'blob', color: orbCode('G'), min: 7 }), '7+ Wood connected');
});

test('blob placements: rectangles of min to min + 2 orbs with a side of 3+, h then w then row then column', () => {
  const placements = blobPlacements(5, ROWS, COLS);
  const sizes = [];
  for (const { cells, exact } of placements) {
    assert.equal(exact, false);
    const rs = cells.map((i) => row(i, COLS));
    const cs = cells.map((i) => col(i, COLS));
    const h = Math.max(...rs) - Math.min(...rs) + 1;
    const w = Math.max(...cs) - Math.min(...cs) + 1;
    assert.equal(cells.length, h * w, 'a filled rectangle');
    assert.ok(h * w >= 5 && h * w <= 7 && Math.max(h, w) >= 3, `${h}x${w}`);
    assert.deepEqual(cells, sorted(cells));
    const size = `${h}x${w}`;
    if (sizes.at(-1) !== size) sizes.push(size);
  }
  // 1x5, 1x6, 2x3, 3x2, 5x1 (no 1x7, 7x1 or 6x1 on 6x5).
  assert.deepEqual(sizes, ['1x5', '1x6', '2x3', '3x2', '5x1']);
  assert.equal(placements.length, 5 * 2 + 5 * 1 + 4 * 4 + 3 * 5 + 1 * 6);
  // Row-major order inside one size.
  assert.deepEqual(placements[0].cells, [0, 1, 2, 3, 4]);
  assert.deepEqual(placements[1].cells, [1, 2, 3, 4, 5]);
  assert.deepEqual(placements[2].cells, [6, 7, 8, 9, 10]);
  assert.deepEqual(rectanglePlacements(2, 3, ROWS, COLS)[0], [0, 1, 2, 6, 7, 8]);
  assert.equal(rectanglePlacements(2, 3, ROWS, COLS).length, 4 * 4);
  assert.deepEqual(rectanglePlacements(6, 1, ROWS, COLS), []);
  // The whole board is the only placement for min = rows x cols.
  assert.deepEqual(blobPlacements(30, ROWS, COLS).map((p) => p.cells.length), [30]);
});

test('blob placements exist for every min on every board size', () => {
  for (let rows = 3; rows <= 9; rows++) {
    for (let cols = 3; cols <= 9; cols++) {
      for (let min = 3; min <= rows * cols; min++) {
        const placements = blobPlacements(min, rows, cols);
        assert.ok(placements.length > 0, `min ${min} on ${cols}x${rows}`);
        for (const { cells } of placements) assert.ok(cells.length >= min);
      }
    }
  }
});

// --- colors: at least K distinct colours among the combos (REHA-12) ------------------------------

// One swap of r0c2 and r0c3 makes R R R B B B in row 0 (two colours); the three hearts are apart,
// so no single move makes a Heart combo. No initial match.
const DUO = 'RRBRBB\nGLDGLD\nDGLDGH\nLDHLDG\nGHLGLD'; // R3 B3 G6 L7 D7 H3

const colors = (count, of) => (of ? { type: 'colors', count, of } : { type: 'colors', count });

/** Distinct colours of the combos that are in `of` (default: the 5 attributes). */
function distinctColors(combos, of = ['R', 'B', 'G', 'L', 'D']) {
  return new Set(combos.map((c) => c.color).filter((code) => of.map(orbCode).includes(code))).size;
}

test('colors: 3+ colours on the three board', () => {
  const { result, combos } = assertSolved(THREE, [colors(3)]);
  assert.deepEqual(result.goals, [{ label: '3+ colours', met: true }]);
  assert.ok(distinctColors(combos) >= 3, `${distinctColors(combos)} colours`);
});

test('colors: impossible when fewer colours of the set have 3 orbs', () => {
  assertImpossible(RBG, [colors(4)], ['4+ colours: only 3 colours have 3 or more orbs (R, B, G), the goal needs 4']);
  // Hearts are outside the default set; with them in the set the RBG board still has 3 (H2).
  assertImpossible(RBG, [colors(4, ['R', 'B', 'G', 'H'])], [
    '4+ colours of Fire, Water, Wood, Heart: only 3 colours have 3 or more orbs (R, B, G), the goal needs 4',
  ]);
  // Colours outside `of` do not count: only Fire of R, L, D has 3+ orbs on the RBG board.
  assertImpossible(RBG, [colors(2, ['R', 'L', 'D'])], [
    '2+ colours of Fire, Light, Dark: only 1 colour has 3 or more orbs (R), the goal needs 2',
  ]);
  assertImpossible(RBG, [colors(1, ['L', 'D'])], ['1+ colours of Light, Dark: only 0 colours have 3 or more orbs, the goal needs 1']);
});

test('colors: a set with Heart counts a Heart combo as a colour', () => {
  const { result, combos } = assertSolved(THREE, [colors(2, ['B', 'H'])]);
  assert.equal(result.goals[0].label, '2+ colours of Water, Heart');
  const found = new Set(combos.map((c) => c.color));
  assert.ok(found.has(orbCode('B')) && found.has(orbCode('H')), 'a Water and a Heart combo');
});

test('colors: colours outside the set do not count', () => {
  // One move makes Fire and Water combos: enough for the default set, not for Fire + Heart.
  const { result } = assertSolved(DUO, [colors(2)], { ...OPTIONS, maxMoves: 1 });
  assert.equal(result.moves, 1);
  const board = parseBoard(DUO);
  const unmet = solve(board, [colors(2, ['R', 'H'])], { ...OPTIONS, maxMoves: 1 });
  assert.equal(unmet.status, 'partial');
  assert.deepEqual(unmet.goals.map((g) => g.met), [false]);
  // The order of the set does not change the result.
  const reordered = solve(board, [colors(2, ['H', 'R'])], { ...OPTIONS, maxMoves: 1 });
  assert.deepEqual(reordered.path, unmet.path);
});

test('colors: unmet with one move on the three board', () => {
  assertUnmet(THREE, [colors(3)]);
});

test('colors: count from 1 to 6 and a non-empty set of distinct R, B, G, L, D, H', () => {
  for (const count of [0, 7, -1, 2.5, '3', undefined, null]) {
    assert.throws(() => normalizeGoals([colors(count)]), /Goal 1: colour count must be an integer from 1 to 6/, String(count));
  }
  for (const of of [[], 'RBG', ['R', 'J'], ['r'], ['R', 'R'], [6], ['X'], [null]]) {
    assert.throws(() => normalizeGoals([colors(1, of)]), /Goal 1: of must be a non-empty list of distinct colours R, B, G, L, D, H/, JSON.stringify(of));
  }
  assert.throws(() => normalizeGoals([colors(3, ['R', 'B'])]), /Goal 1: colour count must be at most 2, the colours in of/);
  assert.throws(() => normalizeGoals([colors(6)]), /at most 5/);
  assert.deepEqual(normalizeGoals([colors(3)]), [{ type: 'colors', count: 3, of: [0, 1, 2, 3, 4] }]);
  assert.deepEqual(normalizeGoals([colors(6, ['H', 'R', 'B', 'G', 'L', 'D'])]), [{ type: 'colors', count: 6, of: [5, 0, 1, 2, 3, 4] }]);
  // Normalised goals (orb codes) normalise to themselves.
  assert.deepEqual(normalizeGoals(normalizeGoals([colors(2, ['B', 'H'])])), [{ type: 'colors', count: 2, of: [1, 5] }]);
  assert.equal(goalLabel(normalizeGoals([colors(3)])[0]), '3+ colours');
  // The default set in another order is still the default label.
  assert.equal(goalLabel(normalizeGoals([colors(5, ['D', 'L', 'G', 'B', 'R'])])[0]), '5+ colours');
});

// --- T shape (REHA-13) ----------------------------------------------------------------------------

// TEE of tools/pad-research/proposed-test-cases.json: moving the D at r3c1 up makes a T of Dark.
const TEE = 'BDGLRH\nGDBHLR\nDHDGBL\nLDRBGH\nRBLHDG'; // R4 B5 G5 L5 D6 H5
// TEE with the D at r3c1 swapped one row down: the T needs two moves.
const TEE_FAR = 'BDGLRH\nGDBHLR\nDHDGBL\nLBRBGH\nRDLHDG'; // R4 B5 G5 L5 D6 H5

test('tshape is exactly 5 orbs: 3 in a line plus 2 from its middle, in any orientation', () => {
  const ts = [
    [at(0, 0), at(0, 1), at(0, 2), at(1, 1), at(2, 1)], // stem down
    [at(0, 1), at(1, 1), at(2, 0), at(2, 1), at(2, 2)], // stem up
    [at(0, 0), at(1, 0), at(2, 0), at(1, 1), at(1, 2)], // stem right
    [at(0, 2), at(1, 2), at(2, 2), at(1, 1), at(1, 0)], // stem left
  ].map(sorted);
  for (const t of ts) {
    assert.ok(comboHasShape('tshape', t, ROWS, COLS), `T ${t}`);
    for (const type of ['square', 'cross', 'lshape', 'tpa']) assert.ok(!comboHasShape(type, t, ROWS, COLS), `a T is not a ${type}`);
  }
  assert.ok(!comboHasShape('tshape', sorted([at(0, 0), at(1, 0), at(2, 0), at(2, 1), at(2, 2)]), ROWS, COLS), 'an L is not a T');
  assert.ok(!comboHasShape('tshape', sorted([at(0, 1), at(1, 0), at(1, 1), at(1, 2), at(2, 1)]), ROWS, COLS), 'a cross is not a T');
  assert.ok(!comboHasShape('tshape', sorted([at(0, 0), at(0, 1), at(0, 2), at(1, 1), at(2, 1), at(3, 1)]), ROWS, COLS), '6 orbs: a longer stem');
  assert.ok(!comboHasShape('tshape', sorted([at(0, 0), at(0, 1), at(0, 2), at(0, 3), at(1, 1)]), ROWS, COLS), '4 in a line plus 1');
  // Exact shape: a T with one more orb of the colour attached is a 6-orb combo, not a T.
  assert.ok(!comboHasShape('tshape', sorted([...ts[0], at(1, 0)]), ROWS, COLS), 'a T with one extra orb');
  assert.ok(!comboHasShape('tshape', sorted([...ts[1], at(2, 3)]), ROWS, COLS), 'a T with a longer bar');
  // Same shape away from the corner of the board.
  assert.ok(comboHasShape('tshape', sorted([at(2, 3), at(2, 4), at(2, 5), at(3, 4), at(4, 4)]), ROWS, COLS), 'T at r2c3');
});

test('tshape: size 5, needs a 3x3 board, 4 placements per 3x3 window', () => {
  assert.ok(SHAPE_TYPES.includes('tshape'));
  assert.equal(shapeSize('tshape', 5, 6), 5);
  assert.ok(shapeFits('tshape', 3, 3));
  for (const [rows, cols] of [[2, 6], [6, 2], [2, 2], [1, 9]]) assert.ok(!shapeFits('tshape', rows, cols), `${cols}x${rows}`);
  assert.equal(shapePlacements('tshape', 5, 6).length, 48);
  assert.equal(shapePlacements('tshape', 4, 5).length, 2 * 3 * 4);
  assert.equal(shapePlacements('tshape', 3, 3).length, 4);
  // Orientation order per window (decides ties; Shapes.kt keeps it): stem down, up, right, left.
  assert.deepEqual(shapePlacements('tshape', 3, 3), [
    { cells: [0, 1, 2, 4, 7], exact: true },
    { cells: [1, 4, 6, 7, 8], exact: true },
    { cells: [0, 3, 4, 5, 6], exact: true },
    { cells: [2, 3, 4, 5, 8], exact: true },
  ]);
  assert.equal(goalLabel(normalizeGoals([{ type: 'tshape', color: 'D' }])[0]), 'T shape of Dark');
});

test('tshape: T of Dark on the tee board in one move', () => {
  const { result, combo } = assertShapeGoal(TEE, 'tshape', 'D');
  // One swap of r2c1 and r3c1. The research case wrote [19, 13] (D up); the solver holds the H and
  // drags it down ([13, 19]), which leaves the same board.
  assert.equal(result.moves, 1);
  assert.deepEqual(sorted(result.path), [13, 19]);
  assert.equal(combo.cells.length, 5);
  assert.deepEqual(combo.cells, [1, 7, 12, 13, 14], 'column 1 rows 0-2 + row 2 columns 0-2: stem up');
  const reported = result.combos.find((c) => c.color === 'D' && c.shapes.includes('tshape'));
  assert.deepEqual(reported.shapes, ['tshape'], 'not a cross, L, square or 4 in a line');
});

test('tshape: impossible with 4 Fire orbs, unmet with one move when the T needs two', () => {
  assertImpossible(TEE, [{ type: 'tshape', color: 'R' }], ['Fire: the goals need 5 orbs, the board has 4']);
  assertImpossible('RRB\nGLD\nHBG', [{ type: 'tshape', color: 'R' }], ['Fire: the goals need 5 orbs, the board has 2']);
  assertUnmet(TEE_FAR, [{ type: 'tshape', color: 'D' }]);
  assertShapeGoal(TEE_FAR, 'tshape', 'D');
});
