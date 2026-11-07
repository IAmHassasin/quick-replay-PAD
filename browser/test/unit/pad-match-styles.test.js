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
import { SHAPE_TYPES, comboHasShape, shapeFits, shapePlacements, shapeSize } from '../../extension/lib/pad/shapes.js';
import { solve } from '../../extension/lib/pad/solver.js';

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
