/**
 * REHA-18: hazard orbs. Poison (P) and Mortal poison (M) must never be matched, not even by a
 * cascade; every Bomb (X) must be matched (1 or 2 bombs: impossible); an orb of unknown colour
 * (?: Super blind, Spinner) never matches, is never dragged, and a shape or blob combo touching it
 * does not count. The Android port has the same tests in
 * `android/app/src/test/java/dev/quickreplay/PadHazardTest.kt`.
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  BOMB, MORTAL_POISON, POISON, UNKNOWN, applyPath, countOrbs, formatBoard, orbCode, parseBoard,
} from '../../extension/lib/pad/board.js';
import { Matcher } from '../../extension/lib/pad/matcher.js';
import { checkFeasibility, normalizeGoals, solve } from '../../extension/lib/pad/solver.js';
import { gridFromTiles } from '../../extension/pad-runner.js';

const OPTIONS = { timeLimitMs: 60_000, maxBeamWidth: 1000 };
const ROW_L = [{ type: 'row', color: 'L' }];

/** One move (r2c4 up) makes the row of Light and P P P in row 2. */
const POISON_DIRECT = 'LLLPLL\nRBPLPG\nGDBHRB\nDHGRDH\nBRHGBD';
/** The old solver's Row L path here matched Poison in a cascade (found by a random search). */
const POISON_CASCADE = 'LBBBGP\nRPBGGL\nHLPHBH\nPDGLBP\nLPLRLP';
/** Same with Mortal poison. */
const MORTAL_CASCADE = 'MBDBML\nRHMBBM\nMHLLML\nLDLBDM\nMGBDLL';
/** Mortal poison version of POISON_DIRECT. */
const MORTAL_DIRECT = 'LLLMLL\nRBMLMG\nGDBHRB\nDHGRDH\nBRHGBD';
const BOMB1 = 'LLLLLX\nRBGDHL\nGDHRBG\nDHRBGD\nHRBGDH';
const BOMB2 = 'LLLLLX\nRBGDHX\nGDHRBG\nDHRBGD\nHRBGDL';
/** Three bombs that can be gathered into one line, and a row of Light a few moves away. */
const BOMB3 = 'LLLLLB\nXRGXDH\nGDXRBG\nDHRBGL\nHRBGDH';
/** One move makes the row of Light; the three bombs are far apart. */
const BOMB_FAR = 'LLLRLL\nXBGLDH\nGDHRBG\nDHRBGD\nXRBGDX';
/** An unknown orb in row 1: rows 1 and 2 cannot hold the row of Light. */
const UNKNOWN_ROW = 'LLL?LL\nLRBGDH\nRBGDHR\nBGDHRB\nGDHRBG';
/** The owner's dawnGlare board (https://pad.dawnglare.com/?s=unNHjo0): 3 bombs, P2, M1. */
const SAMPLE = 'HRXHGL\nGJBJRG\nXRXGMD\nGPGRBL\nBLPLDB';

function replay(text, path) {
  const board = parseBoard(text);
  const afterDrag = applyPath(board.cells, path);
  return { board, afterDrag, combos: new Matcher(board.rows, board.cols).simulate(afterDrag).combos };
}

function assertNoPoison(text, result) {
  const { combos } = replay(text, result.path);
  const poison = combos.filter((c) => c.color === POISON || c.color === MORTAL_POISON);
  assert.deepEqual(poison, [], `poison matched: ${JSON.stringify(result.path)}`);
}

test('new orb letters: X Bomb and ? unknown colour, P and M stay', () => {
  assert.equal(orbCode('X'), BOMB);
  assert.equal(orbCode('?'), UNKNOWN);
  assert.equal(orbCode('P'), POISON);
  assert.equal(orbCode('M'), MORTAL_POISON);
  const board = parseBoard(UNKNOWN_ROW);
  assert.equal(formatBoard(board), UNKNOWN_ROW);
  assert.equal(countOrbs(parseBoard(SAMPLE).cells)[BOMB], 3);
});

test('matcher: bombs match like a colour, unknown orbs never match', () => {
  const m = new Matcher(3, 3);
  assert.deepEqual(m.simulate(parseBoard('XXX\nRBG\nBGR').cells).combos.map((c) => c.color), [BOMB]);
  assert.equal(m.simulate(parseBoard('???\nRBG\nBGR').cells).combos.length, 0);
  assert.equal(m.simulate(parseBoard('?RB\n?BG\n?GR').cells).combos.length, 0);
  // Poison still matches in the simulation (the solver is what rejects it).
  assert.deepEqual(m.simulate(parseBoard('PPP\nRBG\nBGR').cells).combos.map((c) => c.color), [POISON]);
});

test('matcher flags combos that touch an unknown orb (4 directions)', () => {
  const m = new Matcher(3, 4);
  const [touching] = m.simulate(parseBoard('RRRB\n?GBG\nBGBG').cells).combos;
  assert.equal(touching.touchesUnknown, true);
  const [apart] = m.simulate(parseBoard('RRRB\nGBG?\nBGBG').cells).combos;
  assert.equal(apart.touchesUnknown, false, 'diagonal contact does not count');
});

test('poison: the result never matches Poison, directly or by a cascade', () => {
  for (const text of [POISON_DIRECT, POISON_CASCADE]) {
    const result = solve(parseBoard(text), ROW_L, OPTIONS);
    assert.equal(result.status, 'solved', text);
    assert.ok(result.combos.every((c) => c.color !== 'P'));
    assertNoPoison(text, result);
  }
});

test('mortal poison: the result never matches Mortal poison, directly or by a cascade', () => {
  for (const text of [MORTAL_DIRECT, MORTAL_CASCADE]) {
    const result = solve(parseBoard(text), ROW_L, OPTIONS);
    assert.equal(result.status, 'solved', text);
    assert.ok(result.combos.every((c) => c.color !== 'M'));
    assertNoPoison(text, result);
  }
});

test('poison: P and M are not counted as orbs of a colour; poison goals are impossible', () => {
  // R2 B6 G5 L4 D5 H5: 6 combos; the 3 Poison orbs would make a 7th.
  const board = parseBoard('PPPRRB\nBGDHLB\nGDHLBG\nDHLBGD\nHLBGDH');
  assert.deepEqual(checkFeasibility(board, normalizeGoals([{ type: 'combos', count: 7 }])),
    ['7+ combos: at most 6 combos are possible on this board']);
  const result = solve(board, [{ type: 'row', color: 'P' }], OPTIONS);
  assert.equal(result.status, 'impossible');
  assert.deepEqual(result.reasons, ['Row of Poison: Poison and Mortal poison orbs must never be matched']);
});

test('bombs: 1 or 2 bombs on the board are impossible before any search', () => {
  for (const [text, reason] of [
    [BOMB1, 'Bomb: 1 bomb on the board, all bombs must be matched and a match needs 3'],
    [BOMB2, 'Bomb: 2 bombs on the board, all bombs must be matched and a match needs 3'],
  ]) {
    const result = solve(parseBoard(text), ROW_L, OPTIONS);
    assert.equal(result.status, 'impossible');
    assert.deepEqual(result.reasons, [reason]);
    assert.equal(result.stats.evaluated, 0);
    // Any goal list, even none.
    assert.equal(solve(parseBoard(text), [{ type: 'combos', count: 1 }], OPTIONS).status, 'impossible');
  }
});

test('bombs: 3 bombs are solved only when every bomb is matched', () => {
  const result = solve(parseBoard(BOMB3), ROW_L, OPTIONS);
  assert.equal(result.status, 'solved');
  assert.deepEqual(result.goals, [{ label: 'Row of Light', met: true }, { label: 'All 3 bombs matched', met: true }]);
  const { combos } = replay(BOMB3, result.path);
  const matched = combos.filter((c) => c.color === BOMB).reduce((n, c) => n + c.cells.length, 0);
  assert.equal(matched, 3);
});

test('bombs: a row met with bombs left over is not solved', () => {
  const result = solve(parseBoard(BOMB_FAR), ROW_L, { ...OPTIONS, maxMoves: 1 });
  assert.equal(result.status, 'partial');
  assert.deepEqual(result.goals.map((g) => g.met), [true, false]);
  assert.equal(result.goals[1].label, 'All 3 bombs matched');
});

test('unknown orbs: the path avoids them and the row does not touch them', () => {
  const board = parseBoard(UNKNOWN_ROW);
  const result = solve(board, ROW_L, OPTIONS);
  assert.equal(result.status, 'solved');
  assert.ok(!result.path.includes(3), `path crosses the unknown orb: ${result.path}`);
  const { afterDrag, combos } = replay(UNKNOWN_ROW, result.path);
  assert.equal(afterDrag[3], UNKNOWN, 'the unknown orb did not move');
  const row = combos.find((c) => c.color === orbCode('L') && c.cells.length >= 6);
  assert.ok(row, 'a row of Light');
  assert.equal(row.touchesUnknown, false);
  // Rows 1 and 2 touch r1c4.
  assert.ok(row.cells[0] >= 12, `row ${row.cells}`);
});

test('unknown orbs: not counted in feasibility', () => {
  const board = parseBoard('LLL?LL\n?RBGDH\nRBGDHR\nBGDHRB\nGDHRBG');
  assert.deepEqual(checkFeasibility(board, normalizeGoals(ROW_L)), ['Light: the goals need 6 orbs, the board has 5']);
  const triple = parseBoard('???RRB\nBGDHLB\nGDHLBG\nDHLBGD\nHLBGDH');
  assert.deepEqual(checkFeasibility(triple, normalizeGoals([{ type: 'combos', count: 7 }])),
    ['7+ combos: at most 6 combos are possible on this board']);
});

test('X and ? are not goal colours', () => {
  assert.throws(() => normalizeGoals([{ type: 'row', color: 'X' }]), /unknown colour/);
  assert.throws(() => normalizeGoals([{ type: 'row', color: '?' }]), /unknown colour/);
});

test('dawnGlare sample board (?s=unNHjo0): all bombs matched, no poison', () => {
  const result = solve(parseBoard(SAMPLE), [{ type: 'combos', count: 3 }], OPTIONS);
  assert.equal(result.status, 'solved');
  assertNoPoison(SAMPLE, result);
  const { combos } = replay(SAMPLE, result.path);
  assert.equal(combos.filter((c) => c.color === BOMB).reduce((n, c) => n + c.cells.length, 0), 3);
});

test('dawnGlare reader maps xbomb, spinner, poison and mortalpoison', () => {
  const colors = ['red', 'xbomb', 'spinner', 'poison', 'mortalpoison', 'blue', 'green', 'light', 'dark'];
  const items = colors.map((color, i) => ({ color, x: (i % 3) * 90 + 45, y: Math.floor(i / 3) * 90 + 45, size: 90 }));
  assert.equal(gridFromTiles(items).text, 'RX?\nPMB\nGLD');
});
