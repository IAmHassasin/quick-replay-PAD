// Writes spec/pad-solver-vectors.json: solver results that the JavaScript solver (browser) and
// its Kotlin port (Android) must both reproduce exactly. Run after any solver change:
//   node scripts/pad-vectors.js
// Options never let the clock decide (huge time limit), so results are deterministic.
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { formatBoard, parseBoard } from '../extension/lib/pad/board.js';
import { solve } from '../extension/lib/pad/solver.js';

const OUT = fileURLToPath(new URL('../../spec/pad-solver-vectors.json', import.meta.url));

const DOC = 'LGHLBB\nLRDDGL\nBRRLRR\nHBRGBL\nLBBRRD';
const SHOT = 'LHDGHL\nHDLBHR\nGLBRGG\nGGBHBG\nHGLHBH';
const RICH = 'DRDBDG\nLDHDRD\nBDGLDB\nRHDBGL\nGLRHBD';
const R2 = 'GLRLDB\nLHBRLG\nDLGBLR\nBRLDHL\nRGBLGD';
const R3 = 'HRBGDL\nRRLBGD\nLBDRHB\nGDRLBR\nBLHGRD';
const SMALL = 'RBGL\nLDHR\nBRGD\nHLBG';
// Other board sizes used by the match-style tests (5 columns x 4 rows, 7 columns x 6 rows).
const WIDE = 'LRBLG\nGLHRB\nRBLGL\nHGRBD';
const BIG = 'RGBLDHR\nBRGLHDB\nGBRLDHG\nLGBRHDL\nDLGBRHD\nHDLGBRH';
// Blob boards of tools/pad-research/proposed-test-cases.json (REHA-4); FULL is 9x9 with R73.
const BLOB = 'RRBRRG\nGLRDHB\nBGLDHL\nHDBGLR\nLBHGDB';
const THREE = 'RLRRBB\nGDHBHD\nHGGDGL\nLHBLDR\nDBLHRG';
const TWICE = 'RRBRGL\nGRLHBD\nRHRBLG\nBLGDHB\nDGHLRH';
const FULL = Array.from({ length: 9 }, (_, r) => (r < 8 ? `${'R'.repeat(r)}B${'R'.repeat(8 - r)}` : 'R'.repeat(9))).join('\n');

const single = { timeLimitMs: 600000, maxBeamWidth: 1000 };
const twoRounds = { timeLimitMs: 600000, maxBeamWidth: 4000 };

const cases = [
  ['doc row L', DOC, [{ type: 'row', color: 'L' }], single],
  ['doc row L no diagonals', DOC, [{ type: 'row', color: 'L' }], { ...single, diagonals: false }],
  ['doc row L + 5 combos', DOC, [{ type: 'row', color: 'L' }, { type: 'combos', count: 5 }], single],
  ['doc 7 combos', DOC, [{ type: 'combos', count: 7 }], single],
  ['doc 3x3 D impossible', DOC, [{ type: 'square', color: 'D' }], single],
  ['doc no goals', DOC, [], { ...single, maxMoves: 12 }],
  ['shot row G', SHOT, [{ type: 'row', color: 'G' }], single],
  ['shot L B + row G', SHOT, [{ type: 'lshape', color: 'B' }, { type: 'row', color: 'G' }], single],
  ['shot cross H', SHOT, [{ type: 'cross', color: 'H' }], single],
  ['rich 3x3 D', RICH, [{ type: 'square', color: 'D' }], single],
  ['r2 row L', R2, [{ type: 'row', color: 'L' }], single],
  ['r2 cross L', R2, [{ type: 'cross', color: 'L' }], single],
  ['r3 column R', R3, [{ type: 'column', color: 'R' }], single],
  ['r3 tpa B + 5 combos', R3, [{ type: 'tpa', color: 'B' }, { type: 'combos', count: 5 }], single],
  ['r3 two rows R impossible', R3, [{ type: 'row', color: 'R' }, { type: 'row', color: 'R' }], single],
  ['small 4x4 2 combos', SMALL, [{ type: 'combos', count: 2 }], single],
  ['doc 7 combos two rounds', DOC, [{ type: 'combos', count: 7 }], twoRounds],
  ['shot cross H two rounds', SHOT, [{ type: 'cross', color: 'H' }], twoRounds],
  ['rich 3x3 D two rounds', RICH, [{ type: 'square', color: 'D' }], twoRounds],
  // Rounds up to 16000 wide with no early stop: passes SEEN_LIMIT, so the set is cleared mid-round.
  ['doc no goals wide', DOC, [], { timeLimitMs: 600000, maxBeamWidth: 16000, maxMoves: 20 }],
  // Match-style cases: other board sizes, an exact shape without diagonals, an unmet goal.
  ['wide 5x4 row L', WIDE, [{ type: 'row', color: 'L' }], single],
  ['big 7x6 column L', BIG, [{ type: 'column', color: 'L' }], single],
  ['shot cross H no diagonals', SHOT, [{ type: 'cross', color: 'H' }], { ...single, diagonals: false }],
  ['doc row L one move unmet', DOC, [{ type: 'row', color: 'L' }], { ...single, maxMoves: 1 }],
  ['doc tpa L', DOC, [{ type: 'tpa', color: 'L' }], single],
  // Blob goals (REHA-11): N+ connected orbs, a colour set, two combos of one colour, impossible,
  // unmet, mixed with a shape, and a size where no rectangle is within min + 2 (73 on 9x9).
  ['blob 5+ R', BLOB, [{ type: 'blob', color: 'R', min: 5 }], single],
  ['blob 5+ H impossible', BLOB, [{ type: 'blob', color: 'H', min: 5 }], single],
  ['three blob R + blob B', THREE, [{ type: 'blob', color: 'R', min: 3 }, { type: 'blob', color: 'B', min: 3 }], single],
  ['twice two blobs R', TWICE, [{ type: 'blob', color: 'R', min: 3 }, { type: 'blob', color: 'R', min: 3 }], single],
  ['twice two blobs D impossible', TWICE, [{ type: 'blob', color: 'D', min: 3 }, { type: 'blob', color: 'D', min: 3 }], single],
  ['twice two blobs R one move unmet', TWICE, [{ type: 'blob', color: 'R', min: 3 }, { type: 'blob', color: 'R', min: 3 }], { ...single, maxMoves: 1 }],
  ['doc blob 6+ B + row L', DOC, [{ type: 'blob', color: 'B', min: 6 }, { type: 'row', color: 'L' }], single],
  ['doc blob 4+ R no diagonals', DOC, [{ type: 'blob', color: 'R', min: 4 }], { ...single, diagonals: false }],
  ['full 9x9 blob 73+ R', FULL, [{ type: 'blob', color: 'R', min: 73 }], { ...single, maxMoves: 8 }],
];

const vectors = cases.map(([name, text, goals, options]) => {
  const board = parseBoard(text);
  const r = solve(board, goals, options);
  return {
    name,
    board: formatBoard(board),
    goals,
    options,
    expected: {
      status: r.status,
      reasons: r.reasons,
      path: r.path,
      goalsMet: r.goals.map((g) => g.met),
      combos: r.combos.length,
      afterDrag: r.afterDrag ? formatBoard(board, r.afterDrag) : null,
      rounds: r.stats.rounds,
    },
  };
});

writeFileSync(OUT, `${JSON.stringify({
  description: 'PAD solver results shared by the browser (JS) and Android (Kotlin) solvers. Generated by browser/scripts/pad-vectors.js.',
  vectors,
}, null, 2)}\n`);
console.log(`wrote ${vectors.length} vectors to ${OUT}`);
