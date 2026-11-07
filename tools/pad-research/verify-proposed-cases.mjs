/**
 * Checks the test cases proposed in docs/MATCHING-STYLES.md (proposed-test-cases.json) with the
 * existing board model and matcher, so the expected results in the document are not guesses:
 *
 * - every board has rows * cols orbs (30 on 6x5) and no match before the drag;
 * - a solved case: replaying its path yields exactly the expected combos and meets every goal;
 * - an impossible case: the orb-count check of the proposed goal types yields the expected reasons;
 * - the T shape definition cases.
 *
 * The proposed goal types (`blob`, `colors`, `tshape`) are evaluated by reference checkers in this
 * file; they are the behaviour the solver issues should implement, not production code.
 *   node verify-proposed-cases.mjs
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { ORB_LETTERS, ORB_NAMES, applyPath, countOrbs, orbCode, parseBoard } from '../../browser/extension/lib/pad/board.js';
import { Matcher } from '../../browser/extension/lib/pad/matcher.js';
import { comboHasShape } from '../../browser/extension/lib/pad/shapes.js';

const spec = JSON.parse(readFileSync(new URL('./proposed-test-cases.json', import.meta.url), 'utf8'));
const ATTRIBUTES = ['R', 'B', 'G', 'L', 'D'];

/** Reference: exactly 5 orbs, a line of 3 plus 2 orbs continuing from its middle (4 orientations). */
function isT(cells, rows, cols) {
  if (cells.length !== 5) return false;
  const set = new Set(cells);
  const at = (r, c) => (r >= 0 && r < rows && c >= 0 && c < cols ? r * cols + c : -1);
  for (const centre of cells) {
    const r = Math.floor(centre / cols);
    const c = centre % cols;
    const arms = [
      [[r, c - 1], [r, c + 1], [r + 1, c], [r + 2, c]], // stem down
      [[r, c - 1], [r, c + 1], [r - 1, c], [r - 2, c]], // stem up
      [[r - 1, c], [r + 1, c], [r, c + 1], [r, c + 2]], // stem right
      [[r - 1, c], [r + 1, c], [r, c - 1], [r, c - 2]], // stem left
    ];
    if (arms.some((arm) => arm.every(([ar, ac]) => set.has(at(ar, ac))))) return true;
  }
  return false;
}

/** Reference feasibility check for the proposed goal types (orb counts only). */
function feasibility(board, goals) {
  const counts = countOrbs(board.cells);
  const need = new Array(ORB_LETTERS.length).fill(0);
  const reasons = [];
  for (const goal of goals) {
    if (goal.type === 'colors') {
      const set = goal.of ?? ATTRIBUTES;
      const have = set.filter((letter) => counts[orbCode(letter)] >= 3);
      if (have.length < goal.count) {
        reasons.push(`${goal.count}+ colours: only ${have.length} colours have 3 or more orbs (${have.join(', ')}), the goal needs ${goal.count}`);
      }
      continue;
    }
    const size = goal.type === 'blob' ? goal.min : goal.type === 'tshape' ? 5 : null;
    assert.ok(size !== null, `unknown goal type ${goal.type}`);
    need[orbCode(goal.color)] += size;
  }
  for (let color = 0; color < ORB_LETTERS.length; color++) {
    if (need[color] > counts[color]) reasons.push(`${ORB_NAMES[color]}: the goals need ${need[color]} orbs, the board has ${counts[color]}`);
  }
  return reasons;
}

/** Reference "met" check: each goal takes a distinct combo. */
function goalsMet(board, combos, goals) {
  const used = new Set();
  const met = [];
  for (const goal of goals) {
    if (goal.type === 'colors') {
      const set = goal.of ?? ATTRIBUTES;
      const colors = new Set(combos.map((c) => ORB_LETTERS[c.color]).filter((l) => set.includes(l)));
      met.push(colors.size >= goal.count);
      continue;
    }
    const index = combos.findIndex((c, i) => {
      if (used.has(i) || c.color !== orbCode(goal.color)) return false;
      if (goal.type === 'blob') return c.cells.length >= goal.min && (goal.max === undefined || c.cells.length <= goal.max);
      if (goal.type === 'tshape') return isT(c.cells, board.rows, board.cols);
      return false;
    });
    if (index >= 0) used.add(index);
    met.push(index >= 0);
  }
  return met;
}

let failures = 0;
function check(name, fn) {
  try {
    fn();
    console.log(`ok   ${name}`);
  } catch (err) {
    failures++;
    console.log(`FAIL ${name}\n     ${err.message}`);
  }
}

for (const [name, text] of Object.entries(spec.boards)) {
  check(`board ${name}: 30 orbs, no initial match`, () => {
    const board = parseBoard(text);
    assert.equal(board.rows * board.cols, 30);
    const { combos } = new Matcher(board.rows, board.cols).simulate(board.cells);
    assert.deepEqual(combos, [], 'the board must not match before the drag');
  });
}

for (const c of spec.cases) {
  check(`${c.id} (${c.style})`, () => {
    const board = parseBoard(spec.boards[c.board]);
    const reasons = feasibility(board, c.goals);
    if (c.expected.status === 'impossible') {
      assert.deepEqual(reasons, c.expected.reasons);
      return;
    }
    assert.deepEqual(reasons, [], 'a solved case must pass the orb count');
    const { path } = c.expected;
    for (let i = 1; i < path.length; i++) {
      const dr = Math.abs(Math.floor(path[i] / board.cols) - Math.floor(path[i - 1] / board.cols));
      const dc = Math.abs((path[i] % board.cols) - (path[i - 1] % board.cols));
      assert.ok(dr <= 1 && dc <= 1 && dr + dc > 0, `step ${i} is not a move to a neighbour`);
    }
    const after = applyPath(board.cells, path);
    const { combos } = new Matcher(board.rows, board.cols).simulate(after);
    const got = combos.map((combo) => ({
      color: ORB_LETTERS[combo.color],
      size: combo.cells.length,
      ...(isT(combo.cells, board.rows, board.cols) ? { shape: 'tshape' } : {}),
    }));
    assert.deepEqual(got, c.expected.combos);
    assert.deepEqual(goalsMet(board, combos, c.goals), c.goals.map(() => true), 'every goal must be met');
    // The existing shapes must not already cover the case (otherwise no new goal type is needed).
    for (const goal of c.goals) {
      if (goal.type !== 'tshape') continue;
      for (const combo of combos) {
        if (combo.color !== orbCode(goal.color)) continue;
        for (const type of ['cross', 'lshape', 'square', 'tpa']) assert.equal(comboHasShape(type, combo.cells, board.rows, board.cols), false, `a T must not count as ${type}`);
      }
    }
  });
}

for (const d of spec.tshapeDefinition.cases) {
  check(`tshape definition: ${d.note}`, () => {
    const cells = d.cells.map(([r, c]) => r * 6 + c).sort((a, b) => a - b);
    assert.equal(isT(cells, 5, 6), d.expected);
  });
}

console.log(failures ? `\n${failures} check(s) failed` : '\nall checks passed');
process.exit(failures ? 1 : 0);
