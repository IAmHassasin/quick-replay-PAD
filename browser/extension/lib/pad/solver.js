/**
 * PAD drag-path solver: finds one continuous drag that makes the requested combo shapes.
 *
 * Search: beam search over drag paths. A state is (board, cursor cell); the held orb is always
 * at the cursor, so one move is a swap of the cursor cell with a neighbour. Every start cell is
 * tried. Each depth keeps the best `beamWidth` states by a heuristic score; identical states are
 * dropped with a Zobrist hash. The search stops at the first depth where every goal is met, when
 * `maxMoves` is reached or when `timeLimitMs` runs out (the best path found so far is returned).
 */
import { ORB_COUNT, ORB_LETTERS, ORB_NAMES, applyPath, checkSize, countOrbs, orbCode } from './board.js';
import { Matcher } from './matcher.js';
import { SHAPE_LABELS, SHAPE_TYPES, comboHasShape, shapeFits, shapePlacements, shapeSize } from './shapes.js';

export const GOAL_TYPES = [...SHAPE_TYPES, 'combos'];

export const DEFAULT_OPTIONS = Object.freeze({
  diagonals: true,
  maxMoves: 40,
  /** Width of the first search round; later rounds are 4x wider up to `maxBeamWidth`. */
  beamWidth: 1000,
  maxBeamWidth: 64000,
  timeLimitMs: 3000,
});

const MET_SCORE = 1000;
const HAVE_WEIGHT = 10;
const LEAK_WEIGHT = 4;
// Weights tuned on 13 fixture goals (sum of moves at a fixed beam width: 157 → 150).
const DISTANCE_WEIGHT = 2;
/** Per pair of touching same-colour orbs while a combo-count goal is unmet (combos "about to form"). */
const PAIR_WEIGHT = 2;
const COMBO_WEIGHT = 2;
const COMBO_GOAL_WEIGHT = 40;
const TIME_CHECK_INTERVAL = 256;
// Same value in the Kotlin port, where it bounds memory on phones; changing it changes the
// shared vectors (spec/pad-solver-vectors.json).
const SEEN_LIMIT = 1_000_000;

/**
 * Validates goals and converts colour letters to orb codes.
 * @param {Array<{type: string, color?: string | number, count?: number}>} goals
 * @returns {Array<{type: string, color?: number, count?: number}>}
 * @throws {Error} On an invalid goal.
 */
export function normalizeGoals(goals) {
  if (!Array.isArray(goals)) throw new Error('goals must be an array');
  return goals.map((goal, i) => {
    const where = `Goal ${i + 1}`;
    if (!GOAL_TYPES.includes(goal?.type)) throw new Error(`${where}: unknown type "${goal?.type}"`);
    if (goal.type === 'combos') {
      if (!Number.isInteger(goal.count) || goal.count < 1) throw new Error(`${where}: combo count must be an integer >= 1`);
      return { type: 'combos', count: goal.count };
    }
    const color = typeof goal.color === 'number' ? goal.color : orbCode(goal.color ?? '');
    if (!Number.isInteger(color) || color < 0 || color >= ORB_COUNT) throw new Error(`${where}: unknown colour "${goal.color}"`);
    return { type: goal.type, color };
  });
}

/** Human-readable goal, e.g. "Row of Light" or "7+ combos". */
export function goalLabel(goal) {
  if (goal.type === 'combos') return `${goal.count}+ combos`;
  return `${SHAPE_LABELS[goal.type]} of ${ORB_NAMES[goal.color]}`;
}

/**
 * Checks orb counts and board size. A goal list can still be unsolvable when this passes.
 * @returns {string[]} Reasons the goals are impossible (empty when they may be possible).
 */
export function checkFeasibility(board, goals) {
  const { rows, cols } = board;
  const counts = countOrbs(board.cells);
  const need = new Array(ORB_COUNT).fill(0);
  const rowGoals = new Array(ORB_COUNT).fill(0);
  const columnGoals = new Array(ORB_COUNT).fill(0);
  const reasons = [];
  for (const goal of goals) {
    if (goal.type === 'combos') {
      const max = counts.reduce((sum, n) => sum + Math.floor(n / 3), 0);
      if (goal.count > max) reasons.push(`${goalLabel(goal)}: at most ${max} combos are possible on this board`);
      continue;
    }
    if (!shapeFits(goal.type, rows, cols)) {
      reasons.push(`${goalLabel(goal)}: does not fit on a ${cols}x${rows} board`);
      continue;
    }
    need[goal.color] += shapeSize(goal.type, rows, cols);
    if (goal.type === 'row') rowGoals[goal.color]++;
    if (goal.type === 'column') columnGoals[goal.color]++;
  }
  for (let color = 0; color < ORB_COUNT; color++) {
    // A row and a column of one colour may share their crossing orb (one combo). Exact shapes
    // are whole combos of their own and never share orbs.
    need[color] -= rowGoals[color] * columnGoals[color];
    if (need[color] > counts[color]) {
      reasons.push(`${ORB_NAMES[color]}: the goals need ${need[color]} orbs, the board has ${counts[color]}`);
    }
  }
  return reasons;
}

/**
 * Solves a board.
 * @param {{rows: number, cols: number, cells: Uint8Array}} board
 * @param {Array<{type: string, color?: string | number, count?: number}>} goals Empty: maximise combos.
 * @param {Partial<typeof DEFAULT_OPTIONS> & {now?: () => number}} [options]
 * @returns {{
 *   status: 'solved' | 'partial' | 'impossible',
 *   reasons: string[],
 *   path: number[],
 *   moves: number,
 *   goals: Array<{label: string, met: boolean}>,
 *   combos: Array<{color: string, size: number, pass: number, shapes: string[]}>,
 *   afterDrag: Uint8Array | null,
 *   afterMatch: Uint8Array | null,
 *   stats: {depth: number, evaluated: number, rounds: number, beamWidth: number, ms: number, timedOut: boolean},
 * }} `stats.depth` is the deepest depth explored by any round, `stats.beamWidth` the width of
 *   the last round started (not necessarily the one that found the path).
 */
export function solve(board, goals, options = {}) {
  const { rows, cols, cells } = board;
  checkSize(rows, cols);
  if (!(cells instanceof Uint8Array) || cells.length !== rows * cols) throw new Error('board.cells must be a Uint8Array of rows * cols');
  const opts = { ...DEFAULT_OPTIONS, ...options };
  for (const key of ['maxMoves', 'beamWidth', 'maxBeamWidth', 'timeLimitMs']) {
    if (!Number.isInteger(opts[key]) || opts[key] < 1) throw new Error(`${key} must be an integer >= 1`);
  }
  // Monotonic, like the Kotlin port: a wall-clock change must not stretch or cut the time limit.
  const now = options.now ?? (() => performance.now());
  const started = now();
  const units = normalizeGoals(goals);
  const goalLabels = units.map(goalLabel);

  const reasons = checkFeasibility(board, units);
  if (reasons.length) {
    return {
      status: 'impossible',
      reasons,
      path: [],
      moves: 0,
      goals: goalLabels.map((label) => ({ label, met: false })),
      combos: [],
      afterDrag: null,
      afterMatch: null,
      stats: { depth: 0, evaluated: 0, rounds: 0, beamWidth: 0, ms: Math.round(now() - started), timedOut: false },
    };
  }

  const search = {
    // Private copy: the search swaps orbs in place while scoring.
    cells: cells.slice(),
    goalCount: units.length,
    evaluator: new Evaluator(rows, cols, units, opts.diagonals),
    neighbours: buildNeighbours(rows, cols, opts.diagonals),
    zobrist: new Zobrist(rows * cols),
    timeUp: () => now() - started > opts.timeLimitMs,
  };

  // Anytime search: each round is 4x wider than the previous one; once a path is found, later
  // rounds only accept a path that is shorter (or as long with more combos). A round without
  // improvement does not stop the search (a wider one often still finds a shorter path); it
  // ends at `maxBeamWidth` or at the time limit, keeping the best path so far.
  let best = null;
  let width = opts.beamWidth;
  let maxDepth = opts.maxMoves;
  let rounds = 0;
  let evaluated = 0;
  let depth = 0;
  let timedOut = false;
  for (;;) {
    rounds++;
    const round = beamRound(search, width, maxDepth);
    evaluated += round.evaluated;
    depth = Math.max(depth, round.depth);
    const improved = round.best !== null && better(round.best, best, search.goalCount);
    if (improved) best = round.best;
    if (round.timedOut) {
      timedOut = true;
      break;
    }
    const solved = best !== null && search.goalCount > 0 && best.met === search.goalCount;
    // One move is the shortest possible path.
    if (width >= opts.maxBeamWidth || (solved && best.depth === 1)) break;
    if (search.timeUp()) {
      timedOut = true;
      break;
    }
    if (solved) maxDepth = best.depth;
    width = Math.min(width * 4, opts.maxBeamWidth);
  }

  const evaluator = search.evaluator;
  const path = best ? pathOf(best) : [];
  const afterDrag = applyPath(cells, path);
  const sim = evaluator.matcher.simulate(afterDrag);
  const metFlags = evaluator.goalsMet(sim.combos);
  return {
    status: metFlags.every(Boolean) ? 'solved' : 'partial',
    reasons: [],
    path,
    moves: Math.max(0, path.length - 1),
    goals: goalLabels.map((label, i) => ({ label, met: metFlags[i] })),
    combos: sim.combos.map((combo) => ({
      color: ORB_LETTERS[combo.color],
      size: combo.cells.length,
      pass: combo.pass,
      shapes: SHAPE_TYPES.filter((type) => comboHasShape(type, combo.cells, rows, cols)),
    })),
    afterDrag,
    afterMatch: sim.cells.slice(),
    stats: { depth, evaluated, rounds, beamWidth: width, ms: Math.round(now() - started), timedOut },
  };
}

/**
 * True when search node `a` is a better answer than `b`: more goals met; then, with every goal
 * met, fewer moves and more combos (with no goals: more combos, then fewer moves); otherwise the
 * higher heuristic score.
 */
function better(a, b, goalCount) {
  if (!b) return true;
  if (a.met !== b.met) return a.met > b.met;
  if (goalCount === 0) return a.combos !== b.combos ? a.combos > b.combos : a.depth < b.depth;
  if (a.met === goalCount) return a.depth !== b.depth ? a.depth < b.depth : a.combos > b.combos;
  return a.score !== b.score ? a.score > b.score : a.depth < b.depth;
}

/**
 * One beam search pass. Children are scored by swapping in place on the parent's board; only
 * the kept states get their own board copy.
 * @returns {{best: object | null, evaluated: number, depth: number, timedOut: boolean}}
 */
function beamRound(search, width, maxDepth) {
  const { cells, goalCount, evaluator, neighbours, zobrist } = search;
  const seen = new Set();
  let beam = [];
  for (let start = 0; start < cells.length; start++) {
    const [h1, h2] = zobrist.hashOf(cells, start);
    beam.push({ cells, cursor: start, prev: -1, parent: null, h1, h2 });
    seen.add(key(h1, h2));
  }
  let best = null;
  let evaluated = 0;
  let depth = 0;
  while (depth < maxDepth) {
    depth++;
    const candidates = [];
    for (const state of beam) {
      const board = state.cells;
      const a = state.cursor;
      for (const next of neighbours[a]) {
        if (next === state.prev) continue;
        const ca = board[a];
        const cb = board[next];
        // Unsigned like hashOf(), so a state reached again has the same key as a start state.
        const h1 = (state.h1 ^ zobrist.swap1(a, next, ca, cb)) >>> 0;
        const h2 = (state.h2 ^ zobrist.swap2(a, next, ca, cb)) >>> 0;
        const k = key(h1, h2);
        if (seen.has(k)) continue;
        // Bound memory (and stay far below the engine's Set size limit); forgetting old states
        // only costs some repeated work.
        if (seen.size >= SEEN_LIMIT) seen.clear();
        seen.add(k);
        board[a] = cb;
        board[next] = ca;
        const score = evaluator.score(board);
        board[a] = ca;
        board[next] = cb;
        const node = {
          cells: null, cursor: next, prev: a, parent: state, h1, h2, score, met: evaluator.met, combos: evaluator.combos, depth,
        };
        candidates.push(node);
        if (better(node, best, goalCount)) best = node;
        if (++evaluated % TIME_CHECK_INTERVAL === 0 && search.timeUp()) return { best, evaluated, depth, timedOut: true };
      }
    }
    if (!candidates.length) break;
    if (goalCount > 0 && best.met === goalCount) break;
    candidates.sort((x, y) => y.score - x.score);
    const kept = candidates.length > width ? candidates.slice(0, width) : candidates;
    for (const node of kept) {
      const board = node.parent.cells.slice();
      board[node.prev] = node.parent.cells[node.cursor];
      board[node.cursor] = node.parent.cells[node.prev];
      node.cells = board;
    }
    // Boards of the previous depth are no longer needed (paths only use the cursors).
    for (const state of beam) state.cells = null;
    beam = kept;
    if (search.timeUp()) return { best, evaluated, depth, timedOut: true };
  }
  return { best, evaluated, depth, timedOut: false };
}

function key(h1, h2) {
  // 32 + 21 bits fit exactly in a double.
  return h1 * 2097152 + (h2 >>> 11);
}

function pathOf(node) {
  const path = [];
  for (let n = node; n; n = n.parent) path.push(n.cursor);
  return path.reverse();
}

function buildNeighbours(rows, cols, diagonals) {
  const dirs = [[-1, 0], [1, 0], [0, -1], [0, 1]];
  if (diagonals) dirs.push([-1, -1], [-1, 1], [1, -1], [1, 1]);
  const out = [];
  for (let r = 0; r < rows; r++) {
    for (let c = 0; c < cols; c++) {
      const list = [];
      for (const [dr, dc] of dirs) {
        const nr = r + dr;
        const nc = c + dc;
        if (nr >= 0 && nr < rows && nc >= 0 && nc < cols) list.push(nr * cols + nc);
      }
      out.push(list);
    }
  }
  return out;
}

/** Zobrist hashing of (board, cursor) with two independent 32-bit keys. */
class Zobrist {
  constructor(n) {
    const random = mulberry32(0x5eed);
    this.n = n;
    this.orb1 = Uint32Array.from({ length: n * ORB_COUNT }, random);
    this.orb2 = Uint32Array.from({ length: n * ORB_COUNT }, random);
    this.cur1 = Uint32Array.from({ length: n }, random);
    this.cur2 = Uint32Array.from({ length: n }, random);
  }

  hashOf(cells, cursor) {
    let h1 = this.cur1[cursor];
    let h2 = this.cur2[cursor];
    for (let i = 0; i < cells.length; i++) {
      h1 ^= this.orb1[i * ORB_COUNT + cells[i]];
      h2 ^= this.orb2[i * ORB_COUNT + cells[i]];
    }
    return [h1 >>> 0, h2 >>> 0];
  }

  /** XOR delta of key 1 for swapping cells a (orb ca, cursor) and b (orb cb). */
  swap1(a, b, ca, cb) {
    const o = this.orb1;
    return (o[a * ORB_COUNT + ca] ^ o[b * ORB_COUNT + cb] ^ o[a * ORB_COUNT + cb] ^ o[b * ORB_COUNT + ca]
      ^ this.cur1[a] ^ this.cur1[b]) >>> 0;
  }

  swap2(a, b, ca, cb) {
    const o = this.orb2;
    return (o[a * ORB_COUNT + ca] ^ o[b * ORB_COUNT + cb] ^ o[a * ORB_COUNT + cb] ^ o[b * ORB_COUNT + ca]
      ^ this.cur2[a] ^ this.cur2[b]) >>> 0;
  }
}

/** Small deterministic PRNG returning uint32 values. */
function mulberry32(seed) {
  let s = seed >>> 0;
  return () => {
    s = (s + 0x6d2b79f5) >>> 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return (t ^ (t >>> 14)) >>> 0;
  };
}

/** Scores boards against a goal list (allocation-free in the hot path). */
class Evaluator {
  constructor(rows, cols, units, diagonals) {
    this.rows = rows;
    this.cols = cols;
    this.units = units;
    this.matcher = new Matcher(rows, cols);
    const n = rows * cols;
    // Moves needed to bring an orb from cell i to cell j (ignoring other orbs).
    this.dist = new Uint8Array(n * n);
    for (let i = 0; i < n; i++) {
      for (let j = 0; j < n; j++) {
        const dr = Math.abs(Math.floor(i / cols) - Math.floor(j / cols));
        const dc = Math.abs((i % cols) - (j % cols));
        this.dist[i * n + j] = diagonals ? Math.max(dr, dc) : dr + dc;
      }
    }
    this.maxDist = rows + cols;
    this.histogram = new Int32Array(this.maxDist + 1);
    this.empty = new Int32Array(n);
    /** Goals met and combo count of the last {@link score} call. */
    this.met = 0;
    this.combos = 0;
    // Goals that share type and colour need distinct combos; group them.
    this.groups = new Map();
    units.forEach((unit, index) => {
      const k = unit.type === 'combos' ? 'combos' : `${unit.type}:${unit.color}`;
      if (!this.groups.has(k)) {
        const placements = unit.type === 'combos' ? [] : shapePlacements(unit.type, rows, cols).map((p) => {
          const inside = new Uint8Array(n);
          for (const i of p.cells) inside[i] = 1;
          return { cells: Int32Array.from(p.cells), border: Int32Array.from(p.exact ? borderOf(p.cells, rows, cols) : []), inside };
        });
        this.groups.set(k, {
          type: unit.type,
          color: unit.color,
          indices: [],
          placements,
          values: new Float64Array(placements.length),
          haves: new Int32Array(placements.length),
        });
      }
      this.groups.get(k).indices.push(index);
    });
  }

  /** Per-goal met flags for a list of combos. */
  goalsMet(combos) {
    const met = new Array(this.units.length).fill(false);
    for (const group of this.groups.values()) {
      const available = this.available(group, combos);
      group.indices.forEach((index, k) => {
        met[index] = group.type === 'combos' ? combos.length >= this.units[index].count : k < available;
      });
    }
    return met;
  }

  available(group, combos) {
    if (group.type === 'combos') return combos.length;
    let n = 0;
    for (const combo of combos) {
      if (combo.color === group.color && comboHasShape(group.type, combo.cells, this.rows, this.cols)) n++;
    }
    return n;
  }

  /** Heuristic score of a board; also sets {@link met} and {@link combos}. */
  score(cells) {
    const { combos } = this.matcher.simulate(cells);
    let score = combos.length * COMBO_WEIGHT;
    let met = 0;
    let pairsAdded = false;
    for (const group of this.groups.values()) {
      if (group.type === 'combos') {
        for (const index of group.indices) {
          const want = this.units[index].count;
          if (combos.length >= want) {
            score += MET_SCORE;
            met++;
          } else {
            score += (COMBO_GOAL_WEIGHT * combos.length) / want;
            if (!pairsAdded) {
              score += PAIR_WEIGHT * this.pairs(cells);
              pairsAdded = true;
            }
          }
        }
        continue;
      }
      const available = Math.min(this.available(group, combos), group.indices.length);
      met += available;
      score += available * MET_SCORE;
      const missing = group.indices.length - available;
      if (missing > 0) score += this.progress(group, cells, available, missing);
    }
    this.met = met;
    this.combos = combos.length;
    return score;
  }

  /**
   * Partial progress towards the unmet goals of a group: how many target cells of the best
   * placements already hold the colour, minus same-colour orbs touching exact shapes, minus the
   * distance of the nearest loose orbs to the best placement's empty cells.
   */
  progress(group, cells, skip, count) {
    const { placements, values, haves, color } = group;
    let bestIndex = 0;
    for (let p = 0; p < placements.length; p++) {
      const { cells: target, border } = placements[p];
      let have = 0;
      for (let k = 0; k < target.length; k++) if (cells[target[k]] === color) have++;
      let leak = 0;
      for (let k = 0; k < border.length; k++) if (cells[border[k]] === color) leak++;
      haves[p] = have;
      values[p] = HAVE_WEIGHT * have - LEAK_WEIGHT * leak;
      if (values[p] > values[bestIndex]) bestIndex = p;
    }
    if (skip === 0 && count === 1) {
      return values[bestIndex] - DISTANCE_WEIGHT * this.distance(placements[bestIndex], haves[bestIndex], cells, color);
    }
    // Several unmet goals of one type and colour (rare): take the next best placements.
    const order = Array.from(values.keys()).sort((x, y) => values[y] - values[x]);
    let total = 0;
    for (let k = 0; k < count; k++) {
      const p = order[Math.min(skip + k, order.length - 1)];
      total += values[p];
      if (k === 0) total -= DISTANCE_WEIGHT * this.distance(placements[p], haves[p], cells, color);
    }
    return total;
  }

  /** Number of horizontally or vertically touching pairs of same-colour orbs. */
  pairs(cells) {
    const { rows, cols } = this;
    let count = 0;
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c + 1 < cols; c++) {
        const i = r * cols + c;
        if (cells[i] === cells[i + 1]) count++;
      }
    }
    for (let i = 0; i + cols < rows * cols; i++) if (cells[i] === cells[i + cols]) count++;
    return count;
  }

  /** Sum of the move distances of the `missing` nearest loose orbs to the placement's gaps. */
  distance(placement, have, cells, color) {
    const missing = placement.cells.length - have;
    if (missing <= 0) return 0;
    const { dist, histogram, empty, maxDist } = this;
    const n = cells.length;
    let gaps = 0;
    for (let k = 0; k < placement.cells.length; k++) {
      const j = placement.cells[k];
      if (cells[j] !== color) empty[gaps++] = j;
    }
    histogram.fill(0);
    for (let i = 0; i < n; i++) {
      if (cells[i] !== color || placement.inside[i]) continue;
      let d = maxDist;
      const row = i * n;
      for (let k = 0; k < gaps; k++) {
        const v = dist[row + empty[k]];
        if (v < d) d = v;
      }
      histogram[d]++;
    }
    let sum = 0;
    let left = missing;
    for (let d = 0; d <= maxDist && left > 0; d++) {
      const take = Math.min(left, histogram[d]);
      sum += take * d;
      left -= take;
    }
    return sum + left * maxDist;
  }
}

/** Cells orthogonally adjacent to a placement but outside it. */
function borderOf(cells, rows, cols) {
  const inside = new Set(cells);
  const out = new Set();
  for (const i of cells) {
    const r = Math.floor(i / cols);
    const c = i % cols;
    for (const [dr, dc] of [[-1, 0], [1, 0], [0, -1], [0, 1]]) {
      const nr = r + dr;
      const nc = c + dc;
      const j = nr * cols + nc;
      if (nr >= 0 && nr < rows && nc >= 0 && nc < cols && !inside.has(j)) out.add(j);
    }
  }
  return [...out];
}
