# Matching styles of JP leader skills vs. the solver goals (REHA-4)

Research and plan, 2026-11-07. No solver code changes; the owner reviews this before any
implementation issue is opened. Scripts and the aggregated data: [`tools/pad-research/`](../tools/pad-research/).

## Summary

- Of the 5000 highest-numbered JP monsters with a leader skill (No. 5601–14303, 4400 distinct
  leader skills), the matching conditions fall into 19 styles. The solver's 7 goals (`row`,
  `column`, `square`, `cross`, `lshape`, `tpa`, `combos`) cover 8 of them fully and one partly.
- The four most used styles are **not** supported: *N+ orbs of one colour connected* (1222 leader
  skills), *a fixed set of colours matched at once* (892), *N+ separate combos of one colour*
  (856) and *K+ colours at once* (779). The first three reduce to one new goal type (`blob`: a
  combo of a colour with at least N orbs); the fourth needs a `colors` goal.
- Two newer awakening-based conditions are also missing: the **T shape** (24 skills; newest
  monster No. 14113, older monsters got it through leader skill updates) and *exactly N orbs*
  for N ≠ 4 (114 skills). The **column** awakening
  (縦消し攻撃, 2026-10) is already covered by `column`.
- `tpa` ("exactly 4 in a line") is right for the 2体攻撃 awakening, whose official text says
  "4個消すと" (exactly 4). Leader skills that say "4個つなげて" (4 or more, 237 skills) are a
  `blob` condition, not a TPA.
- Plan: 4 implementation issues, one PR each, ordered by usage: `blob` (min N), `colors`,
  `tshape`, `blob` with `max` (exact N). Test cases for each are ready in
  [`tools/pad-research/proposed-test-cases.json`](../tools/pad-research/proposed-test-cases.json)
  and were checked against the existing matcher.

## 1. Data

| Item | Value |
|---|---|
| Source | JP server card and skill data (`download_card_data`, `download_skill_data` API responses) as published by [Mapaler/PADDashFormation](https://github.com/Mapaler/PADDashFormation) in `monsters-info/mon_ja.json` and `skill_ja.json`. The repository documents how the author captures the data from the game client (`monsters-info/README.md`). |
| Snapshot | PADDashFormation commit `fbbafa1` ("2026年11月6日", 2026-11-06 13:11 UTC), fetched 2026-11-07. |
| Cross-check | DadGuide / Tsubaki bot export `dadguide.sqlite` (`Last-Modified: 2026-11-07 05:03 UTC`; the CloudFront URL that [TsubakiBotPad/pad-cogs](https://github.com/TsubakiBotPad/pad-cogs) `dbcog/dbcog.py` downloads from, constant `CLOUDFRONT_URL` in `tsutils/tsubaki/links.py`): `select count(*), max(monster_no_jp) from monsters where on_jp=1 and leader_skill_id>0` gives **9644 / 14303**, identical to the JSON selection below. |
| Type semantics | Raw skill type numbers and parameter layouts from [TsubakiBotPad/pad-data-pipeline](https://github.com/TsubakiBotPad/pad-data-pipeline) `etl/pad/raw/skills/leader_skill_info.py`; awakening activation texts from the DadGuide `awoken_skills` table (official JP text) and, for awakenings the export has not translated yet, from kamigame.jp / appmedia.jp (links in section 2). |
| How | `node tools/pad-research/analyze-leader-skills.mjs` (script committed, raw data not). |

Coverage:

| Count | Value |
|---|---:|
| Monsters in the JP card file (enabled, No. < 100000) | 14,291 |
| … with a leader skill | 9,644 |
| Selected (highest No. with a leader skill) | **5,000** (No. 5601 – 14303) |
| Distinct leader skills among them | 4,400 |

The 5000 target was met. 4,330 of the 4,400 skills are used by exactly one monster; several
monsters sharing a skill are counted once as a skill and each as a monster ("Monsters" column
below).

### How a leader skill was classified

A leader skill is a tree: type 138 lists child skill ids, each child is one effect with a type
and parameters. Every leaf was mapped to a style by its type (section 2 lists the mapping).
Three points needed interpretation:

1. **"N個つなげて消す" vs "N個消し"**. The pipeline's English texts and the in-game behaviour treat
   "Nつなげて" as *N or more connected orbs* (types 119, 159, 167, 182, 192, 200, 219: `blob_min`),
   and "N個消し1個につき" (type 235) as *exactly N orbs* (`blob_exact`). The two phrasings were
   separated by the JP text of each skill.
2. **Awakening-triggered conditions** (types 271 "[X]発動で", 280 "[X]を発動するたびに", 285
   縦消し攻撃): 507 + 8 + 14 skills name an awakening instead of a shape. Bit flags of type 271 were
   correlated with the bracketed awakening names in the JP text (every bit maps to exactly one
   name) and the awakening's own activation condition was used:

   | Awakening (JP) | Condition (official text / source) | Style |
   |---|---|---|
   | 2体攻撃 | 自分と同じ属性のドロップを**4個**消すと (DadGuide `awoken_skills` 27) | `tpa` (exactly 4) |
   | ダメージ無効貫通 | 3×3の正方形で消すと (id 48) | `square` |
   | L字消し攻撃 | ドロップ5個をL字型に消すと (id 60) | `lshape` |
   | 十字消し攻撃 | 5個を十字型に消すと ([game8](https://game8.jp/archives/457056)) | `cross` |
   | T字消し攻撃 | 5個をT字型に消すと ([kamigame](https://kamigame.jp/puzzle-dragons/%E8%A6%9A%E9%86%92%E3%82%B9%E3%82%AD%E3%83%AB/T%E5%AD%97%E6%B6%88%E3%81%97%E6%94%BB%E6%92%83.html)) | `tshape` |
   | 火〜闇列強化 | 横1列 of the colour | `row` |
   | 火〜闇縦消し攻撃 (type 285) | 縦1列 of the colour ([appmedia](https://appmedia.jp/?p=80498014), Ver. 23.6, 2026-10) | `column` |
   | 3色/4色/5色攻撃強化 | 3/4/5色以上同時攻撃 ([game8](https://game8.jp/archives/162029)) | `rainbow` K=3/4/5 |
   | 火水/水木/木火同時攻撃 | the two colours matched at once ([kamigame](https://kamigame.jp/puzzle-dragons/%E8%A6%9A%E9%86%92%E3%82%B9%E3%82%AD%E3%83%AB/%E7%81%AB%E6%B0%B4%E5%90%8C%E6%99%82%E6%94%BB%E6%92%83.html)) | `colors_at_once` |
   | 火〜闇コンボ強化 | 火ドロップで**2コンボ以上**すると (ids 73–77) | `same_color_combos` N=2 |
   | 超つなげ消し強化 | 12個以上つなげて消すと (id 82) | `blob_min` N=12 |
   | バインド回復 | 回復ドロップを横一列で (id 20) | `row` of H |
3. **Attribute "slot" lists** (types 124, 164, 171, 201, 206: "火闇の同時攻撃", "闇の2コンボ",
   "闇闇火火の同時攻撃"): each slot is one combo of the listed colour; repeated colours mean
   separate combos of that colour. Distinct colours → `colors_at_once`; one repeated colour →
   `same_color_combos`; both → `combo_pattern`; masks with several colours per slot → `rainbow`.

## 2. Matching styles found

Counts are leader skills / monsters among the 5000. "Parameters" lists the most common values
(number of skills). Support is judged against `GOAL_TYPES` in
`browser/extension/lib/pad/solver.js:14` (`SHAPE_TYPES` of `shapes.js:14` + `combos`) and
`Shapes.TYPES` in `android/app/src/main/java/dev/quickreplay/pad/Shapes.kt:9`.

| # | Style | Skills | Monsters | Parameters (skills) | Raw types | Support |
|---|---|---:|---:|---|---|---|
| 1 | N+ orbs of one colour connected (`blob_min`) | 1222 | 1238 | N=6: 615, N=5: 578, N=4: 237, N=9: 114, N=10: 41, N=7: 31, N=12: 24, N=8: 21, N=3: 26; of these "any colour" (ドロップを): 112, "one of two colours" (火か闇): ~150, "two colours together" (火と闇を同時に, type 192): 162 | 119, 159, 167, 182, 192, 200, 219, 235 (つなげて), 271 bit 21 | **unsupported** |
| 2 | A fixed set of colours matched at once (`colors_at_once`) | 892 | 905 | pairs: R+B 100, R+D 98, R+L 94, L+D 77, B+D 73, B+H 68, B+G 67, L+H 65, G+H 61, R+H 58 …; triples: R+B+L 50, R+B+D 33, R+D+H 27 …; 4 colours: 11 | 124, 164, 171, 201, 206 (distinct slots), 271 bits 22–24 | **unsupported** |
| 3 | N+ separate combos of one colour (`same_color_combos`) | 856 | 865 | 2×D 413, 2×R 285, 2×L 234, 2×G 185, 2×B 179, 2×H 86, 3×D 40, 3×L 25, 3×R 21, 3×G 16, 3×B 14, 4×: 4 | same types, repeated slot; 271 bits 15–19 (○コンボ強化) | **unsupported** |
| 4 | K+ colours matched at once, of a set (`rainbow`) | 779 | 793 | K=3 of 5: 435, K=4 of 5: 356, K=5 of 5: 196, K=2 of 5: 53, 5 colours + heart: 29, K=2 of a pair (e.g. 光か闇): 118 | 61, 165, 170, 194, 199, 124/171 (multi-colour slots), 271 bits 10–12 | **unsupported** |
| 5 | N+ combos (`combos`) | 638 | 652 | N=7: 245, N=8: 177, N=10: 93, N=6: 91, N=9: 67, N=5: 52, N=4: 31, N≥11: 17 | 66, 98, 103, 104, 166, 169, 223 | supported (`combos`) |
| 6 | Heart match (`heal_match`) | 349 | 354 | hearts in a colour set (B+H 68, L+H 65, G+H 61, R+H 58, D+H 45, R+D+H 27 …, most of the 349); cross of 5 hearts: 46; 3+ … 9+ connected hearts: 76; exactly 4 hearts (per match): 3; row of hearts: 1 | subset of 1, 2, 4, 7, 8 with the heart bit; 149, 151, 209 | **partial** (see below) |
| 7 | Exactly 4 orbs, two-pronged attack (`tpa`) | 170 | 170 | own colour (2体攻撃): 111; "4個消し1個につき" of a colour: 59 | 271 bit 0, 280 id 27, 235 (4個消し), 149 | supported (`tpa`) |
| 8 | L of 5 (`lshape`) | 125 | 126 | own colour (L字消し攻撃): 50; any colour: 48; B: 24 … | 193, 220, 221, 271 bit 2 | supported (`lshape`) |
| 9 | Exactly N orbs, N ≠ 4 (`blob_exact`) | 114 | 115 | N=5: 79, N=6: 24, N=9: 8, N=10: 3 | 235 (N個消し) | **unsupported** |
| 10 | Cross of 5, colour (`cross`) | 87 | 89 | own colour (十字消し攻撃): 40; 火か闇: 14; 光か闇: 8 … | 157, 210, 271 bit 3, 280 id 78 | supported (`cross`) |
| 11 | 3x3 square, VDP (`square`) | 66 | 66 | own colour: 66 | 271 bit 1, 280 id 48 | supported (`square`) |
| 12 | Full row of one colour (`row`) | 63 | 64 | D 28, R 17, L 12, B 7, G 6, H 1 | 271 bits 5–9 and 20, 280 ids 22–26 | supported (`row`) |
| 13 | Cross of 5 hearts (`heart_cross`) | 28 | 30 | — | 151, 209 | supported (`cross` of H) |
| 14 | T of 5 (`tshape`) | 24 | 24 | own colour: 24 | 271 bit 4 | **unsupported** |
| 15 | Full column of one colour (`column`) | 14 | 14 | G 6, D 6, B 2 | 285 | supported (`column`) |
| 16 | 5 orbs including an enhanced orb (`enhanced5`) | 12 | 13 | — | 150 | unsupported, not planned |
| 17 | Mixed combo pattern (`combo_pattern`) | 12 | 12 | 闇闇火火 4, 光光水 4, 光光闇 3, 水水光 3 … | slot lists with repeats and other colours | unsupported (covered by #1's goal) |
| 18 | Cannot erase N or fewer orbs (`min_erase`) | 9 | 9 | N=3 (消せない ≤3): 7, N=4: 2 | 158 | unsupported, not planned |
| 19 | Exactly N combos (`combos_exact`) | 3 | 3 | N=7: 2, N=10: 1 | 101 | unsupported, not planned |

Styles the issue listed that did not appear as a *leader skill* condition: "many rows" (no skill
requires two rows; the row awakening counts each row), "heart 3+" (hearts are matched like any
colour; the smallest heart condition is "回復を3個つなげて", 11 skills, in #1).

### Conclusions per style

- **`blob_min` (unsupported).** No goal means "a combo of colour X with at least N orbs". `row`
  requires a full line (`shapes.js:52`, `n >= cols && hasFullLine`), `tpa` requires exactly 4
  (`shapes.js:58`, `if (n !== 4) return false`; `Shapes.kt:43`, `n == 4`). The N=4 group (237
  skills, "4個つなげて") is therefore *not* met by `tpa`: a 5-orb match satisfies the leader skill
  but not the goal.
- **`colors_at_once`, `same_color_combos`, `combo_pattern` (unsupported).** They need "any combo
  of colour X" (3+ orbs) as a goal, possibly repeated. Repeating a goal already means distinct
  combos (`solver.js:395-413`, groups by `type:color` with one index per goal), so once a `blob`
  goal with N=3 exists, "火闇の同時攻撃" is `[blob R 3, blob D 3]` and "闇の2コンボ" is
  `[blob D 3, blob D 3]` with no further solver change.
- **`rainbow` (unsupported).** "K of the 5 colours" cannot be written as a fixed list of goals
  (any 3 of 5 would be 10 alternatives). It needs its own goal that counts distinct colours among
  the combos.
- **`combos` (supported).** `combos.length` counts cascades too (`matcher.js`, pass 1+), as the
  game does without skyfall. Exactly-N (#19) is 3 old skills; not planned.
- **`heal_match` (partial).** "回復の4個消し" (exactly 4 hearts, awakening 回復ドロップ強化 /
  type 149) is `{type: "tpa", color: "H"}` and the heart cross is `{type: "cross", color: "H"}`;
  both work today. The skills with hearts in a colour set ("火回復の同時攻撃", the bulk of the
  349) and the 76 "回復をN個つなげて" skills need the `blob` goal: there is no goal for "a heart
  combo" or "5+ hearts" (`GOAL_TYPES`, `solver.js:14`).
- **`tpa` (supported).** The awakening text is "4個消すと" (exactly 4) and "4個消し1個につき"
  (type 235) is per match of exactly 4, so `n === 4` is the right definition. On a 6x5 board a
  4-orb combo is always a straight line (a 4th orb next to a line of 3 is matched only if it is
  itself in a line of 3), so the `1x4 / 4x1` box test is equivalent.
- **`row`, `column` (supported).** `hasFullLine` accepts extra orbs attached to the line, which is
  how 列強化 / 縦消し攻撃 behave (the whole combo must contain a full line).
- **`cross`, `lshape`, `square`, `heart_cross` (supported).** Exact 5 / 5 / 9 orbs with the shape
  test, matching the awakening texts (5個十字, 5個L字, 3×3の正方形). Assumption kept from the
  original design: extra orbs attached to the shape break it (the pipeline and wiki texts agree
  for L and VDP; not verified in the game for cross).
- **`tshape` (unsupported).** New 5-orb shape (3 in a line plus 2 continuing from the middle of
  that line). `isL` (`shapes.js:98`) rejects it and nothing else accepts it; the REHA-3 tests even
  assert "a T is not an L".
- **`blob_exact` (unsupported).** "5個消し1個につき" needs exactly N; a 6-orb combo does not
  count. Same goal as #1 with an upper bound.
- **Not planned**: `enhanced5` (needs enhanced-orb recognition, which `BoardRecognizer` does not
  do), `min_erase` (a constraint on *every* match including skyfall combos the solver cannot see),
  `combos_exact` (3 skills, also skyfall-dependent).

### Conditions that are not matching styles (listed, not planned)

| Condition | Skills | Monsters | Note |
|---|---:|---:|---|
| Passive stats (HP/ATK/RCV by attribute or type) | 2114 | 2688 | no board condition |
| 7x6 board | 459 | 468 | board size is already a setup parameter (`rows`, `cols`) |
| Extra move time / fixed move time | 332 / 284 | 340 / 286 | affects `maxMoves` and drag speed, not the goal |
| Team composition (collab, rarity, group) | 177 | 178 | — |
| HP threshold (HP ≥ / ≤ x%) | 130 | 134 | — |
| Rank exp / drop / coin rates | 276 | 279 | — |
| No skyfall (落ちコンなし) / orbs remaining ≤ N | 70 / 37 | 70 / 38 | the solver already ignores skyfall; "orbs remaining" would be a new scoring term, out of scope |
| Poison disabled, grants awakening, skill used, skills used per turn, heal amount ≥ N, skyfall prediction, autoheal, resolve, counterattack, reduced HP/RCV | 47 / 45 / 44 / 15 / 21 / 17 / 33 / 7 / 5 / 7 | — | — |

## 3. Plan

Priority by number of leader skills and monsters. Every issue is one PR with JS and Kotlin in the
same PR (solver parity is checked by `spec/pad-solver-vectors.json`; a one-sided change cannot
be merged). Each PR: tests first (REHA-3 style cases in `browser/test/unit/pad-match-styles.test.js`
and `PadMatchStyleTest.kt`), then code, then `node scripts/pad-vectors.js` and the shared
vector tests on both sides, lint, Android unit tests.

### Issue A — `blob` goal: a combo of one colour with at least N orbs (styles #1, #2, #3, #6, #17)

Covers 1222 + 892 + 856 (+ hearts in sets, mixed patterns) leader skills, about 3,000 of the 5000
monsters once overlaps are removed. Estimate: **large** (new goal kind with its own heuristic).

- Goal JSON: `{ "type": "blob", "color": "R", "min": 5 }` (`min` ≥ 3, ≤ rows × cols). Labels:
  "5+ Fire connected". Repeated goals = separate combos.
- `browser/extension/lib/pad/solver.js`: `GOAL_TYPES` + `blob`; `normalizeGoals` validates
  `min`; `goalLabel`; `checkFeasibility` adds `min` orbs of the colour to `need` (a blob never
  shares orbs with a row/column of the same colour unless it *is* that line; keep the simple sum);
  `Evaluator`: group key `blob:${color}:${min}`, `available()` counts combos of the colour with
  `cells.length >= min`; `progress()` needs placements. Proposal: placements = all `h × w`
  rectangles with `h * w >= min`, `max(h, w) >= 3`, `h * w <= min + 2`, enumerated h ascending,
  then w, then row, then column (a 1×5 line, a 2×3 block … are all connected combos), `exact:
  false`. The existing have / leak / distance scoring then applies unchanged. For `min` > 12
  (24 skills) the rectangles become few and large; acceptable.
- `browser/extension/lib/pad/shapes.js`: `rectanglePlacements(h, w, rows, cols)` helper (also
  usable by `row`/`column`/`square`/`tpa` later, but do not refactor them in this PR).
- Kotlin: `Solver.kt` `Goal.Blob(color, min)` + the same Evaluator changes; `Shapes.kt`
  `rectanglePlacements`; `PadSetup.kt` `parseGoal` / `goalJson` (`min` field); `PadSetupDialog.kt`
  type list + the count field reused for `min` (visible for `combos` and `blob`).
- Browser UI: `popup.js` `addGoalRow` (new option, show both colour and count), `popup.html`
  unchanged; `background.js` passes goals through `normalizeGoals` already.
- Schema: `spec/quickreplay.schema.json` `$defs.goal.oneOf` gets a third branch
  (`type: "blob"`, `color`, `min` integer ≥ 3). Docs: `docs/script-format.md` goal table,
  `README.md` / `browser/README.md` goal lists, `docs/PAD-SOLVER.md` shapes table.
- Scoring / beam search impact: one more placement set per group (≈ 40–70 placements for N=5–6
  on 6x5, versus 48 for `lshape`), so the per-node cost grows by the same order as one L goal.
  `SEEN_LIMIT`, hashing and ordering unchanged. Vectors: new cases only (existing cases keep their
  paths; verify by regenerating and diffing).
- Parity risks: rectangle enumeration order (defines `bestIndex` tie-breaks), the `h * w <= min + 2`
  bound, and `Float64` vs `Double` sums — keep the loops literal copies, as today.
- Feasibility nuance to document: "two colours together" (type 192, 162 skills) is two `blob`
  goals; "one of two colours" (火か闇, ≈150 skills) cannot be expressed (either/or); the user
  picks one colour. Not planned.

### Issue B — `colors` goal: at least K distinct colours among the combos (style #4)

779 leader skills / 793 monsters. Estimate: **medium**.

- Goal JSON: `{ "type": "colors", "count": 3 }`, optional `"of": ["R", "B", "G", "L", "D"]`
  (default the 5 attributes; add `"H"` for "5色+回復"). Label: "3+ colours".
- `solver.js`: `normalizeGoals` (count 1–6, set validation), `checkFeasibility` (colours of the
  set with ≥ 3 orbs must be ≥ count: message "3+ colours: only 2 colours have 3 or more orbs"),
  `Evaluator`: met when distinct colours of combos ∩ set ≥ count; progress like `combos`:
  `COMBO_GOAL_WEIGHT * have / count` + `PAIR_WEIGHT * pairs` (pairs already counted once per
  board). Optional refinement later: per missing colour, the best 1×3 placement value.
- Kotlin: `Goal.Colors(count, set)`, same evaluator code, `PadSetup` JSON, dialog: count field
  (the set stays at the default in the first version; a set editor is a follow-up if needed).
- Browser popup: new option "Colours (at least)", count field. Schema branch:
  `type: "colors"`, `count` 1–6, optional `of` array of colour letters.
- Risks: the heuristic is weak when a colour has exactly 3 orbs scattered (the search relies on
  `pairs`); acceptable for a first version, measure on the fixture boards. Parity: set membership
  order does not affect scores (counts only).

### Issue C — `tshape` shape (style #14)

24 leader skills today, growing (the awakening is from 2025). Estimate: **small**; independent of
A and B, can be done first as a warm-up of the process.

- `shapes.js`: `SHAPE_TYPES` + `tshape`, `SHAPE_LABELS` "T shape", `shapeSize` 5, `shapeFits`
  3×3, `comboHasShape`: `n === 5 && boxSize === '3x3' && isT`, `shapePlacements`: 4 orientations
  per 3×3 window (48 on 6x5, like L), `exact: true`. `Shapes.kt` the same. `isT`: a centre cell
  with two opposite neighbours and two cells continuing in one of the two remaining directions.
- Goal JSON: `{ "type": "tshape", "color": "D" }` (the existing shape branch; schema enum + `tshape`).
  UI: the popup and the dialog build their lists from `SHAPE_TYPES` / `Shapes.TYPES`, so only the
  labels are new. Docs: shape tables.
- Tests: shape definition (4 orientations, L / cross / 6 orbs / 4+1 rejected, see section 4),
  REHA-3 style solved / impossible / unmet cases, one vector.
- Parity risk: orientation order in `shapePlacements` (ties in `progress`). Keep JS order and copy it.

### Issue D — `blob` with `max`: exactly N orbs (style #9)

114 leader skills. Estimate: **small** once A is merged.

- Goal JSON: `{ "type": "blob", "color": "R", "min": 5, "max": 5 }` (`max` ≥ `min`). Label
  "exactly 5 Fire". `available()` adds `cells.length <= max`; placements become `exact: true`
  when `max === min` (border leak penalty, like `tpa`), else unchanged. Schema: optional `max`.
  UI: an "exactly" checkbox next to the count (dialog and popup).

### Not planned (and why)

- Either/or colours ("火か闇を5個", ≈150 skills in #1): would need alternative goals; the user
  chooses one colour. Revisit only if the owner needs it.
- `min_erase`, `combos_exact`, `enhanced5`: rare (≤ 12 skills each) and/or depend on skyfall or
  enhanced-orb recognition.
- "Orbs remaining ≤ N" (37 skills): a scoring change, not a goal; separate research if wanted.
- A colour-set editor for `colors` (`of`): 24 + 29 skills use a non-default set; JSON supports it
  from the start, the UI can follow.

## 4. Test cases for the proposed goal types

Same template as REHA-3: a concrete 6x5 board (letters R/B/G/L/D/H, rows top to bottom, 30 orbs),
the goal JSON, the expected result. `solved` cases give the path (cell index = row × 6 + column)
that makes the combos; `impossible` cases were counted by hand (counts in the notes) and give the
expected `reasons`. All cases are in
[`tools/pad-research/proposed-test-cases.json`](../tools/pad-research/proposed-test-cases.json)
and were checked with `node tools/pad-research/verify-proposed-cases.mjs` (existing `Matcher` on
the replayed path, reference checkers for the new goal types): every board has 30 orbs and no
initial match, every solved path gives exactly the listed combos, every impossible case fails the
orb count with the listed reason. Nothing was added to the test suites or to
`spec/pad-solver-vectors.json`.

Boards:

```
BLOB            THREE           RBG             TEE             TWICE
RRBRRG          RLRRBB          RBGRBG          BDGLRH          RRBRGL
GLRDHB          GDHBHD          BGRBGR          GDBHLR          GRLHBD
BGLDHL          HGGDGL          GRBGRB          DHDGBL          RHRBLG
HDBGLR          LHBLDR          RBGRBG          LDRBGH          BLGDHB
LBHGDB          DBLHRG          LDHLDH          RBLHDG          DGHLRH
R6 B6 G5 L6     R5 B5 G5 L5     R8 B8 G8 L2     R4 B5 G5 L5     R7 B5 G5 L5
D4 H4 (no init  D5 H5           D2 H2           D6 H5           D3 H5
match)
```

| Case | Board | Goals | Expected |
|---|---|---|---|
| blob-min-solved | BLOB | `[{"type":"blob","color":"R","min":5}]` | `solved`, path `[8, 2]` (1 move: the R at r1c2 up), combos: R×5 in row 0 (the 6th R at r3c5 is not connected) |
| blob-min-impossible | BLOB | `[{"type":"blob","color":"H","min":5}]` | `impossible`, reasons `["Heart: the goals need 5 orbs, the board has 4"]` (H at r1c4, r2c4, r3c0, r4c2) |
| blob-exact-solved | BLOB | `[{"type":"blob","color":"R","min":5,"max":5}]` | `solved`, same path, combo of exactly 5 |
| blob-exact-impossible | BLOB | `[{"type":"blob","color":"G","min":6,"max":6}]` | `impossible`, `["Wood: the goals need 6 orbs, the board has 5"]` (G at r0c5, r1c0, r2c1, r3c3, r4c3) |
| colors-solved | THREE | `[{"type":"colors","count":3}]` | `solved`, path `[1, 2, 3, 9, 15, 14, 13]` (6 moves holding the L at r0c1: right ×2 → R R R, down ×2 → B B B in row 0, left ×2 → G G G in row 2), combos R×3, B×3, G×3 |
| colors-impossible | RBG | `[{"type":"colors","count":4}]` | `impossible`, `["4+ colours: only 3 colours have 3 or more orbs (R, B, G), the goal needs 4"]` (L 2, D 2, H 2) |
| colors-at-once-solved | THREE | `[{"type":"blob","color":"R","min":3},{"type":"blob","color":"B","min":3}]` | `solved`, same path as colors-solved; both goals met (a fixed colour set = blob goals with min 3) |
| colors-at-once-impossible | RBG | `[{"type":"blob","color":"L","min":3},{"type":"blob","color":"D","min":3}]` | `impossible`, `["Light: the goals need 3 orbs, the board has 2", "Dark: the goals need 3 orbs, the board has 2"]` |
| same-color-combos-solved | TWICE | `[{"type":"blob","color":"R","min":3},{"type":"blob","color":"R","min":3}]` | `solved`, path `[3, 2, 1, 7, 13]` (4 moves holding the R at r0c3: left ×2 → row 0 R R R, down ×2 → row 2 R R R; row 1 G H L keeps them apart), combos R×3, R×3 |
| same-color-combos-impossible | TWICE | `[{"type":"blob","color":"D","min":3},{"type":"blob","color":"D","min":3}]` | `impossible`, `["Dark: the goals need 6 orbs, the board has 3"]` (D at r1c5, r3c3, r4c0) |
| tshape-solved | TEE | `[{"type":"tshape","color":"D"}]` | `solved`, path `[19, 13]` (1 move: the D at r3c1 up), combo D×5 = column 1 rows 0–2 + row 2 columns 0–2, a T with the stem up; not a cross, L, square or tpa |
| tshape-impossible | TEE | `[{"type":"tshape","color":"R"}]` | `impossible`, `["Fire: the goals need 5 orbs, the board has 4"]` (R at r0c4, r1c5, r3c2, r4c0) |

T shape definition cases (`comboHasShape('tshape', cells, 5, 6)`, cells as (row, col)):

| Cells | Expected | Note |
|---|---|---|
| (0,0) (0,1) (0,2) (1,1) (2,1) | true | stem down |
| (0,1) (1,1) (2,0) (2,1) (2,2) | true | stem up |
| (0,0) (1,0) (2,0) (1,1) (1,2) | true | stem right |
| (0,2) (1,2) (2,2) (1,1) (1,0) | true | stem left |
| (0,0) (1,0) (2,0) (2,1) (2,2) | false | an L is not a T |
| (0,1) (1,0) (1,1) (1,2) (2,1) | false | a cross is not a T |
| (0,0) (0,1) (0,2) (1,1) (2,1) (3,1) | false | 6 orbs |
| (0,0) (0,1) (0,2) (0,3) (1,1) | false | 4 in a line plus 1 |

An "unmet with `maxMoves: 1`" case per type (REHA-3's third case) can reuse the THREE and TWICE
boards: their solved paths need 6 and 4 moves, so with `maxMoves: 1` the goal stays unmet
(`status: "partial"`); this was not verified here because it needs the new goal types in the
solver.

## 5. Open points for the owner

1. Order: A (blob) first because of usage, or C (tshape) first because it is small? The plan
   assumes A → B → C → D with C possible in parallel.
2. Either/or colours ("火か闇を5個つなげて") stay out; confirm.
3. For `colors`, is a count field enough in the UI (set fixed to the 5 attributes, hearts via
   JSON only)?
4. The cross / L / 3x3 "exact shape" assumption (extra attached orbs break the awakening) was
   not verified in the game; the solver already relies on it.
