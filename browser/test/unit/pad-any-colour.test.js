/**
 * REHA-16: goals the board does not have the orbs for are rejected with a readable reason, and
 * shape / blob goals may use the colour "any" (the solver picks one of R, B, G, L, D; several Any
 * goals of one list get pairwise different colours). The Android port has the same tests in
 * `android/app/src/test/java/dev/quickreplay/PadAnyColourTest.kt`.
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { applyPath, countOrbs, orbCode, parseBoard } from '../../extension/lib/pad/board.js';
import { Matcher } from '../../extension/lib/pad/matcher.js';
import { comboHasShape } from '../../extension/lib/pad/shapes.js';
import {
  ANY_ASSIGNMENT_LIMIT, ANY_COLOR, checkFeasibility, goalLabel, normalizeGoals, solve,
} from '../../extension/lib/pad/solver.js';

const OPTIONS = { timeLimitMs: 60_000, maxBeamWidth: 1000 };

// Boards built for these tests (orb counts in the comments; J, P, M fill the rest).
/** L4 only. */
const LIGHT4 = 'LRBGDH\nRBGDHR\nLBGDHB\nRGLDHG\nBRDLHR';
/** L5, exactly 2 Fire. */
const LIGHT5_FIRE2 = 'LLLLLB\nRGDHBG\nDBGHDB\nRGDHBG\nDBGHDB';
/** L7. */
const LIGHT7 = 'LLLLLL\nLRBGDH\nRBGDHR\nBGDHRB\nGDHRBG';
/** L5 (one move from a T of Light), R4 B4 G4 D4: only Light has 5 orbs. */
const TEE_L = 'LLLRBG\nRLBGDH\nBGLDJP\nGDRJPM\nDHJMRB';
/** L5, R2 B2 G2 D2 and 9 Hearts: only Light has 3 or more attribute orbs. */
const TEE_L_ONLY = 'LLLRBG\nHLHHHH\nHJLJPM\nRBGDJP\nDHHHMM';
/** L8 (a T one move away and a separate column of 3), R2 B2 G2 D2. */
const TEE_L8 = 'LLLRBG\nHLHHLH\nHJLJLM\nRBGDLP\nDHHHMM';

function assertImpossible(text, goals, reasons) {
  const result = solve(parseBoard(text), goals, OPTIONS);
  assert.equal(result.status, 'impossible', JSON.stringify(goals));
  assert.deepEqual(result.reasons, reasons);
  assert.deepEqual(result.path, []);
  assert.equal(result.stats.evaluated, 0, 'rejected from orb counts, without searching');
  return result;
}

/** Solved, and every combo the result reports is there when the path is replayed. */
function assertSolved(text, goals) {
  const board = parseBoard(text);
  const result = solve(board, goals, OPTIONS);
  assert.equal(result.status, 'solved', `${JSON.stringify(goals)}: ${JSON.stringify(result.goals)}`);
  assert.deepEqual(result.goals.map((g) => g.met), goals.map(() => true));
  const combos = new Matcher(board.rows, board.cols).simulate(applyPath(board.cells, result.path)).combos;
  assert.equal(combos.length, result.combos.length);
  return { board, result, combos };
}

test('the test boards have the orb counts their comments state', () => {
  const count = (text, letter) => countOrbs(parseBoard(text).cells)[orbCode(letter)];
  assert.equal(count(LIGHT4, 'L'), 4);
  assert.equal(count(LIGHT5_FIRE2, 'L'), 5);
  assert.equal(count(LIGHT5_FIRE2, 'R'), 2);
  assert.equal(count(LIGHT7, 'L'), 7);
  assert.deepEqual(['R', 'B', 'G', 'L', 'D'].map((c) => count(TEE_L, c)), [4, 4, 4, 5, 4]);
  assert.deepEqual(['R', 'B', 'G', 'L', 'D', 'H'].map((c) => count(TEE_L_ONLY, c)), [2, 2, 2, 5, 2, 9]);
  assert.deepEqual(['R', 'B', 'G', 'L', 'D'].map((c) => count(TEE_L8, c)), [2, 2, 2, 8, 2]);
});

// --- Missing orbs: the main pattern, a second pattern of another colour, two of one colour -----

test('a T of Light on a board with 4 Light orbs is impossible', () => {
  assertImpossible(LIGHT4, [{ type: 'tshape', color: 'L' }], ['Light: the goals need 5 orbs, the board has 4']);
});

test('a T of Light plus 3 connected Fire with only 2 Fire orbs is impossible because of Fire', () => {
  assertImpossible(
    LIGHT5_FIRE2,
    [{ type: 'tshape', color: 'L' }, { type: 'blob', color: 'R', min: 3 }],
    ['Fire: the goals need 3 orbs, the board has 2'],
  );
});

test('a T of Light plus 3 connected Light with 7 Light orbs is impossible (8 needed)', () => {
  assertImpossible(
    LIGHT7,
    [{ type: 'tshape', color: 'L' }, { type: 'blob', color: 'L', min: 3 }],
    ['Light: the goals need 8 orbs, the board has 7'],
  );
});

// --- The "any" colour: parsing and labels ------------------------------------------------------

test('"any" is accepted in any letter case for shape and blob goals, and only for them', () => {
  for (const color of ['any', 'ANY', 'Any', 'aNy']) {
    assert.deepEqual(normalizeGoals([{ type: 'tshape', color }]), [{ type: 'tshape', color: ANY_COLOR }]);
    assert.deepEqual(normalizeGoals([{ type: 'blob', color, min: 3 }]), [{ type: 'blob', color: ANY_COLOR, min: 3 }]);
  }
  assert.throws(() => normalizeGoals([{ type: 'row', color: 'anything' }]), /unknown colour/);
  assert.throws(() => normalizeGoals([{ type: 'row', color: 'X' }]), /unknown colour/);
  // `combos` and `colors` have no colour; an `of` set has no "any".
  assert.throws(() => normalizeGoals([{ type: 'colors', count: 1, of: ['any'] }]), /of must be/);
});

test('Any goals have readable labels', () => {
  assert.equal(goalLabel({ type: 'tshape', color: ANY_COLOR }), 'T shape of any colour');
  assert.equal(goalLabel({ type: 'row', color: ANY_COLOR }), 'Row of any colour');
  assert.equal(goalLabel({ type: 'blob', color: ANY_COLOR, min: 3 }), '3+ any colour connected');
  assert.equal(goalLabel({ type: 'blob', color: ANY_COLOR, min: 5, max: 5 }), 'exactly 5 any colour');
  assert.equal(goalLabel({ type: 'blob', color: ANY_COLOR, min: 4, max: 6 }), '4-6 any colour connected');
});

test('feasibility of a goal list without assignment ignores the orbs of Any goals but not their fit', () => {
  // 3 orbs of each colour: no colour has 5, but the colour is only chosen later.
  const board = parseBoard('RBG\nBGR\nGRB');
  assert.deepEqual(checkFeasibility(board, normalizeGoals([{ type: 'tshape', color: 'any' }])), []);
  // 4 in a line needs a side of 4: it does not fit on 3x3 whatever the colour.
  assert.deepEqual(
    checkFeasibility(board, normalizeGoals([{ type: 'tpa', color: 'any' }])),
    ['4 in a line of any colour: does not fit on a 3x3 board'],
  );
});

// --- The "any" colour: solving -----------------------------------------------------------------

test('a T of any colour becomes Light when only Light has 5 orbs, and the result says so', () => {
  const { board, result, combos } = assertSolved(TEE_L, [{ type: 'tshape', color: 'any' }]);
  assert.equal(result.goals[0].anyColor, 'L');
  assert.equal(result.goals[0].label, 'T shape of Light (any colour)');
  const tees = combos.filter((c) => c.color === orbCode('L') && comboHasShape('tshape', c.cells, board.rows, board.cols));
  assert.equal(tees.length, 1);
});

test('two Any goals cannot share a colour: impossible when only one colour has enough orbs', () => {
  const result = assertImpossible(
    TEE_L_ONLY,
    [{ type: 'tshape', color: 'any' }, { type: 'blob', color: 'any', min: 3 }],
    ['Any colour: no assignment of distinct colours has enough orbs for "T shape of any colour", "3+ any colour connected"'],
  );
  assert.deepEqual(result.goals.map((g) => g.label), ['T shape of any colour', '3+ any colour connected']);
  assert.deepEqual(result.goals.map((g) => g.anyColor), [undefined, undefined]);
});

test('Heart is not an Any colour', () => {
  // 9 Hearts would make 3 connected; Any only picks among R, B, G, L, D.
  assertImpossible(
    TEE_L_ONLY,
    [{ type: 'blob', color: 'any', min: 6 }],
    ['Any colour: no assignment of distinct colours has enough orbs for "6+ any colour connected"'],
  );
});

test('two Any goals get two different colours when a second colour has the orbs', () => {
  const { result } = assertSolved(TEE_L, [{ type: 'tshape', color: 'any' }, { type: 'blob', color: 'any', min: 3 }]);
  const [tee, blob] = result.goals.map((g) => g.anyColor);
  assert.equal(tee, 'L');
  assert.ok(['R', 'B', 'G', 'D'].includes(blob), `the blob took ${blob}`);
  assert.equal(result.combos.some((c) => c.color === blob && c.size >= 3), true);
});

test('an Any goal may take the colour of a fixed goal when the board has the orbs for both', () => {
  const { result, combos } = assertSolved(TEE_L8, [{ type: 'tshape', color: 'L' }, { type: 'blob', color: 'any', min: 3 }]);
  assert.equal(result.goals[0].anyColor, undefined, 'a fixed goal has no Any colour');
  assert.equal(result.goals[0].label, 'T shape of Light');
  assert.equal(result.goals[1].anyColor, 'L');
  assert.equal(result.goals[1].label, '3+ Light connected (any colour)');
  assert.ok(combos.filter((c) => c.color === orbCode('L')).length >= 2);
});

test('a fixed goal without its orbs is reported as such, not as an Any problem', () => {
  assertImpossible(
    LIGHT4,
    [{ type: 'tshape', color: 'L' }, { type: 'blob', color: 'any', min: 3 }],
    ['Light: the goals need 5 orbs, the board has 4'],
  );
});

test('more Any goals than Any colours is impossible', () => {
  const goals = Array.from({ length: 6 }, () => ({ type: 'blob', color: 'any', min: 3 }));
  const result = solve(parseBoard('RRRBBB\nGGGLLL\nDDDRRR\nBBBGGG\nLLLDDD'), goals, OPTIONS);
  assert.equal(result.status, 'impossible');
  assert.match(result.reasons[0], /^Any colour: no assignment of distinct colours has enough orbs for /);
});

test('the time limit is shared by the assignments that are searched', () => {
  // A fake clock that advances 1 ms per call: every assignment gets a slice and the result
  // still comes back, with at most ANY_ASSIGNMENT_LIMIT assignments searched.
  let t = 0;
  const result = solve(
    parseBoard(TEE_L),
    [{ type: 'blob', color: 'any', min: 3 }, { type: 'blob', color: 'any', min: 3 }],
    { timeLimitMs: 400, now: () => t++ },
  );
  assert.notEqual(result.status, 'impossible');
  assert.ok(result.stats.assignments >= 2 && result.stats.assignments <= ANY_ASSIGNMENT_LIMIT, `searched ${result.stats.assignments}`);
  const [a, b] = result.goals.map((g) => g.anyColor);
  assert.notEqual(a, b);
  assert.ok(t < 1000, `the clock ran to ${t} ms`);
});

test('goals without Any search one assignment, as before', () => {
  const result = solve(parseBoard(TEE_L), [{ type: 'tshape', color: 'L' }], OPTIONS);
  assert.equal(result.status, 'solved');
  assert.equal(result.stats.assignments, 1);
  assert.equal(result.goals[0].label, 'T shape of Light');
});
