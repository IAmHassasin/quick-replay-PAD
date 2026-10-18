import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { EMPTY, applyPath, countOrbs, formatBoard, parseBoard } from '../../extension/lib/pad/board.js';
import { Matcher } from '../../extension/lib/pad/matcher.js';
import { comboHasShape, shapePlacements } from '../../extension/lib/pad/shapes.js';
import { checkFeasibility, normalizeGoals, solve } from '../../extension/lib/pad/solver.js';

// Example board from docs/PAD-SOLVER.md (dawnGlare, 6x5).
const DOC_BOARD = `
LGHLBB
LRDDGL
BRRLRR
HBRGBL
LBBRRD`;

// Board read from the owner's in-game screenshot (watermelon orb skin, 6x5).
const SCREENSHOT_BOARD = `
LHDGHL
HDLBHR
GLBRGG
GGBHBG
HGLHBH`;

// Deterministic search for tests: no wall-clock limit decides the result (one 1000-wide round).
const TEST_OPTIONS = { timeLimitMs: 60_000, maxBeamWidth: 1000 };

function simulate(text) {
  const board = parseBoard(text);
  return { board, ...new Matcher(board.rows, board.cols).simulate(board.cells) };
}

/** Every step of the path moves to one of the 8 neighbouring cells. */
function assertContinuous(path, cols) {
  for (let i = 1; i < path.length; i++) {
    const dr = Math.abs(Math.floor(path[i] / cols) - Math.floor(path[i - 1] / cols));
    const dc = Math.abs((path[i] % cols) - (path[i - 1] % cols));
    assert.ok(dr <= 1 && dc <= 1 && dr + dc > 0, `step ${i} jumps from ${path[i - 1]} to ${path[i]}`);
  }
}

test('parses and formats boards', () => {
  const board = parseBoard(DOC_BOARD);
  assert.equal(board.rows, 5);
  assert.equal(board.cols, 6);
  assert.equal(formatBoard(board), DOC_BOARD.trim());
  // R B G L D H
  assert.deepEqual(countOrbs(board.cells).slice(0, 6), [8, 7, 3, 7, 3, 2]);
  assert.deepEqual(countOrbs(parseBoard(SCREENSHOT_BOARD).cells).slice(0, 6), [2, 5, 8, 5, 2, 8]);
  assert.equal(formatBoard(parseBoard('RRRBBB GGGLLL DDDHHH RBGLDH JPMRBG', { rows: 5, cols: 6 })).split('\n')[4], 'JPMRBG');
  assert.throws(() => parseBoard('RRX\nBBB\nGGG'), /Unknown orb letter "X"/);
  assert.throws(() => parseBoard('RRR\nBB\nGGG'), /same length/);
  assert.throws(() => parseBoard('RRRBBB', { rows: 3, cols: 3 }), /Expected 9 orbs/);
});

test('applies a drag path by swapping the held orb along it', () => {
  const board = parseBoard('RBG\nLDH\nRBG');
  // Hold R at r1c1, go right twice then down: R ends at r2c3.
  const after = applyPath(board.cells, [0, 1, 2, 5]);
  assert.equal(formatBoard(board, after), 'BGH\nLDR\nRBG');
});

test('groups touching matches of one colour into one combo', () => {
  const { combos } = simulate(`
RRRBG
RGBGB
RBGBG`);
  // Row of 3 R and column of 3 R share r1c1: one combo of 5.
  assert.equal(combos.length, 1);
  assert.deepEqual(combos[0], { color: 0, cells: [0, 1, 2, 5, 10], pass: 0 });
});

test('separate matches of one colour that do not touch are separate combos', () => {
  const { combos } = simulate(`
RRRGB
GBGBG
RRRBG`);
  assert.equal(combos.length, 2);
});

test('a matched line next to an unmatched orb of the same colour does not take it', () => {
  const { combos } = simulate(`
RRRGB
RBGBG
GBGLD`);
  assert.equal(combos.length, 1);
  assert.equal(combos[0].cells.length, 3);
});

test('cascades after gravity without skyfall', () => {
  // The R column is removed, the B above it falls to the bottom row and completes B B B.
  const { combos, board, cells } = simulate(`
LGB
GDR
DLR
BBR`);
  assert.deepEqual(combos.map((c) => [c.color, c.cells.length, c.pass]), [[0, 3, 0], [1, 3, 1]]);
  assert.equal(formatBoard(board, cells), '...\nLG.\nGD.\nDL.');
});

test('detects combo shapes', () => {
  const rows = 5;
  const cols = 6;
  const at = (r, c) => r * cols + c;
  assert.ok(comboHasShape('row', [0, 1, 2, 3, 4, 5], rows, cols));
  assert.ok(comboHasShape('row', [0, 1, 2, 3, 4, 5, 6], rows, cols), 'a row with extra orbs still counts');
  assert.ok(!comboHasShape('row', [0, 1, 2, 3, 4], rows, cols));
  assert.ok(comboHasShape('column', [0, 6, 12, 18, 24], rows, cols));
  const square = [];
  for (let r = 1; r < 4; r++) for (let c = 2; c < 5; c++) square.push(at(r, c));
  assert.ok(comboHasShape('square', square, rows, cols));
  assert.ok(!comboHasShape('square', [...square, at(4, 2)].sort((a, b) => a - b), rows, cols), '10 orbs is not a 3x3');
  const cross = [at(1, 2), at(2, 1), at(2, 2), at(2, 3), at(3, 2)];
  assert.ok(comboHasShape('cross', cross, rows, cols));
  assert.ok(!comboHasShape('lshape', cross, rows, cols));
  const l = [at(0, 0), at(1, 0), at(2, 0), at(2, 1), at(2, 2)];
  assert.ok(comboHasShape('lshape', l, rows, cols));
  assert.ok(!comboHasShape('cross', l, rows, cols));
  const t = [at(0, 0), at(0, 1), at(0, 2), at(1, 1), at(2, 1)];
  assert.ok(!comboHasShape('lshape', t, rows, cols), 'a T is not an L');
  assert.ok(!comboHasShape('cross', t, rows, cols));
  assert.ok(comboHasShape('tpa', [at(4, 1), at(4, 2), at(4, 3), at(4, 4)], rows, cols));
  assert.ok(comboHasShape('tpa', [at(0, 5), at(1, 5), at(2, 5), at(3, 5)], rows, cols));
  assert.ok(!comboHasShape('tpa', [at(0, 0), at(0, 1), at(1, 0), at(1, 1)], rows, cols));
});

test('enumerates shape placements', () => {
  assert.equal(shapePlacements('row', 5, 6).length, 5);
  assert.equal(shapePlacements('column', 5, 6).length, 6);
  assert.equal(shapePlacements('square', 5, 6).length, 12);
  assert.equal(shapePlacements('cross', 5, 6).length, 12);
  assert.equal(shapePlacements('lshape', 5, 6).length, 48);
  assert.equal(shapePlacements('tpa', 5, 6).length, 5 * 3 + 6 * 2);
  for (const type of ['square', 'cross', 'lshape', 'tpa']) {
    for (const { cells } of shapePlacements(type, 5, 6)) assert.ok(comboHasShape(type, cells, 5, 6), `${type} ${cells}`);
  }
});

test('validates goals', () => {
  assert.deepEqual(normalizeGoals([{ type: 'row', color: 'l' }, { type: 'combos', count: 5 }]), [
    { type: 'row', color: 3 },
    { type: 'combos', count: 5 },
  ]);
  assert.throws(() => normalizeGoals([{ type: 'star', color: 'L' }]), /unknown type/);
  assert.throws(() => normalizeGoals([{ type: 'row', color: 'Q' }]), /unknown colour/);
  assert.throws(() => normalizeGoals([{ type: 'combos', count: 0 }]), /combo count/);
});

test('reports impossible goals from orb counts without searching', () => {
  const board = parseBoard(DOC_BOARD);
  assert.deepEqual(checkFeasibility(board, normalizeGoals([{ type: 'row', color: 'L' }])), []);
  const result = solve(board, [{ type: 'square', color: 'D' }], TEST_OPTIONS);
  assert.equal(result.status, 'impossible');
  assert.deepEqual(result.reasons, ['Dark: the goals need 9 orbs, the board has 3']);
  assert.equal(result.stats.evaluated, 0);
  // Two goals of one colour add up: 6 + 5 Light needed, 7 on the board.
  const two = checkFeasibility(board, normalizeGoals([{ type: 'row', color: 'L' }, { type: 'cross', color: 'L' }]));
  assert.deepEqual(two, ['Light: the goals need 11 orbs, the board has 7']);
  // A row and a column can share their crossing orb: 6 + 5 - 1 = 10 Light orbs are enough.
  const cross = parseBoard('LLLLLL\nLRRBBL\nLGGBBR\nLRRGGB\nRBBGGR');
  assert.equal(countOrbs(cross.cells)[3], 10);
  assert.deepEqual(checkFeasibility(cross, normalizeGoals([{ type: 'row', color: 'L' }, { type: 'column', color: 'L' }])), []);
  assert.deepEqual(checkFeasibility(cross, normalizeGoals([{ type: 'row', color: 'L' }, { type: 'row', color: 'L' }])), [
    'Light: the goals need 12 orbs, the board has 10',
  ]);
  // 30 orbs: at most floor(n/3) combos per colour.
  assert.match(checkFeasibility(board, normalizeGoals([{ type: 'combos', count: 10 }]))[0], /at most 8 combos/);
});

function assertSolved(text, goals) {
  const board = parseBoard(text);
  const result = solve(board, goals, TEST_OPTIONS);
  assert.equal(result.status, 'solved', `goals not met: ${JSON.stringify(result.goals)}`);
  assert.ok(result.goals.every((g) => g.met));
  assert.ok(result.moves >= 1);
  assertContinuous(result.path, board.cols);
  // The reported boards are consistent with replaying the path.
  const after = applyPath(board.cells, result.path);
  assert.deepEqual(after, result.afterDrag);
  const sim = new Matcher(board.rows, board.cols).simulate(after);
  assert.equal(sim.combos.length, result.combos.length);
  return result;
}

test('solver makes a row of Light on the doc board', () => {
  const result = assertSolved(DOC_BOARD, [{ type: 'row', color: 'L' }]);
  assert.ok(result.combos.some((c) => c.color === 'L' && c.shapes.includes('row')));
});

test('solver makes a row of Wood on the screenshot board', () => {
  const result = assertSolved(SCREENSHOT_BOARD, [{ type: 'row', color: 'G' }]);
  assert.ok(result.combos.some((c) => c.color === 'G' && c.shapes.includes('row')));
});

test('solver handles combo-count and exact-shape goals', () => {
  assertSolved(DOC_BOARD, [{ type: 'combos', count: 5 }]);
  const result = assertSolved(SCREENSHOT_BOARD, [{ type: 'cross', color: 'H' }]);
  assert.ok(result.combos.some((c) => c.color === 'H' && c.shapes.includes('cross') && c.size === 5));
});

test('solver without diagonals only moves orthogonally', () => {
  const board = parseBoard(DOC_BOARD);
  const result = solve(board, [{ type: 'row', color: 'L' }], { ...TEST_OPTIONS, diagonals: false });
  for (let i = 1; i < result.path.length; i++) {
    const d = Math.abs(result.path[i] - result.path[i - 1]);
    assert.ok(d === 1 || d === board.cols, `diagonal step at ${i}`);
  }
});

test('wider search rounds find a shorter path and never a worse one', () => {
  const board = parseBoard(SCREENSHOT_BOARD);
  const goals = [{ type: 'cross', color: 'H' }];
  const single = solve(board, goals, TEST_OPTIONS);
  const anytime = solve(board, goals, { timeLimitMs: 60_000, maxBeamWidth: 16_000 });
  assert.equal(single.status, 'solved');
  assert.equal(anytime.status, 'solved');
  assert.equal(anytime.stats.rounds, 3); // widths 1000, 4000, 16000
  assert.ok(anytime.moves < single.moves, `anytime ${anytime.moves} moves, single round ${single.moves}`);
  assertContinuous(anytime.path, board.cols);
  assert.deepEqual(applyPath(board.cells, anytime.path), anytime.afterDrag);
});

test('solver reproduces the shared vectors (also checked by the Android port)', () => {
  // Regenerate with `node scripts/pad-vectors.js` after an intended solver change.
  const { vectors } = JSON.parse(readFileSync(new URL('../../../spec/pad-solver-vectors.json', import.meta.url), 'utf8'));
  assert.ok(vectors.length >= 10);
  for (const v of vectors) {
    const board = parseBoard(v.board);
    const r = solve(board, v.goals, v.options);
    assert.equal(r.status, v.expected.status, v.name);
    assert.deepEqual(r.reasons, v.expected.reasons, v.name);
    assert.deepEqual(r.path, v.expected.path, v.name);
    assert.equal(r.combos.length, v.expected.combos, v.name);
  }
});

test('solver stops at the time limit and returns the best path so far', () => {
  let t = 0;
  const board = parseBoard(DOC_BOARD);
  // Fake clock: each reading advances 1 s, so the first time check exceeds the limit.
  const result = solve(board, [{ type: 'combos', count: 8 }], { timeLimitMs: 100, now: () => (t += 1000) });
  assert.equal(result.stats.timedOut, true);
  assert.ok(['solved', 'partial'].includes(result.status));
  assert.ok(result.path.length >= 2);
});

test('empty cells are never matched', () => {
  const matcher = new Matcher(3, 3);
  const cells = new Uint8Array(9).fill(EMPTY);
  assert.equal(matcher.simulate(cells).combos.length, 0);
});
