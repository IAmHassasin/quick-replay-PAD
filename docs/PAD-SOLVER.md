# PAD board solver — feature notes

Status: **planned, nothing implemented yet** (written 2026-10-18 when this repo was created from
[`quick-replay`](https://github.com/IAmHassasin/quick-replay) at `a394373`, v0.1.3).
Read [`CLAUDE.md`](CLAUDE.md) first for the existing Android architecture.

## Goal

Given a Puzzle & Dragons (PAD) board on screen, find **one orb drag path** that produces the
orb shapes the user asks for, for example:

- a **row of Light** (one combo that fills a whole horizontal line),
- a **3×3 square of Dark**,
- plus other shapes / a minimum combo count,

then show the path and perform it as a single continuous drag with the existing gesture engine.

## Example board (from the owner, dawnGlare PAD Simulator, 6×5)

Letters: `R` Fire, `B` Water, `G` Wood, `L` Light, `D` Dark, `H` Heart. Rows top to bottom.

```
L G H L B B
L R D D G L
B R R L R R
H B R G B L
L B B R R D
```

Counts: L 7, B 7, R 8, G 3, D 3, H 2 (30 orbs).
So "row of Light" (needs 6 L) is possible, but "3×3 of Dark" (needs 9 D) is **not** possible
on this board without skyfall. The solver must check orb counts first and report impossible
goals instead of searching.
Use this board as the first unit-test fixture.

## PAD rules the solver relies on

Domain knowledge, **not yet verified in this project**. Check each one against the simulator
before relying on it.

- Boards: 6×5 (normal), 5×4 and 7×6 also exist. Keep rows/cols as parameters.
- The player holds one orb and drags it. Every cell the finger enters swaps with the held
  orb. Diagonal moves are allowed (8 directions). There is a move time limit (a few seconds,
  changed by awakenings/leaders). The timer starts on the first swap, not on touch down.
- After release: 3+ same-colour orbs in a straight horizontal/vertical line are matched.
  Matched orbs of the same colour that touch orthogonally form **one** combo.
  Then gravity, then cascades. New orbs (skyfall) are random. The solver ignores skyfall.
- Shapes (each is one combo):

| Shape | Definition | Typical use |
|---|---|---|
| Row | Combo contains a full horizontal line (6 orbs on 6×5) | Row awakenings |
| 3×3 square | Combo is **exactly** 9 orbs in a 3×3 block | Void damage piercer (VDP) |
| Cross | Exactly 5 orbs in a plus shape | Heart cross, cross leaders |
| L shape | Exactly 5 orbs, 3+3 sharing a corner | L-shape awakenings |
| 4 in a line | Exactly 4 orbs | Two-pronged attack (TPA) |
| Column | Full vertical line | Some leader skills (verify) |

## Proposed pipeline (Android)

```
screenshot → board bounds → orb recognition → (user fixes cells) → solver(goal)
          → path preview overlay → user confirms → one swipe gesture
```

1. **Capture.** The service today only injects gestures
   (`res/xml/accessibility_service_config.xml`: `canPerformGestures` only,
   `canRetrieveWindowContent=false`). Options:
   - `AccessibilityService.takeScreenshot()`: API 30+, needs `android:canTakeScreenshot="true"`.
     No extra consent dialog.
   - `MediaProjection`: works on API 26–29, but asks for consent every session.
   - Decision needed: require API 30+ for the solver (simplest), or support both.
2. **Board bounds.** Calibrate once: the user drags a rectangle over the board, stored per
   screen size. Cell = bounds / (cols, rows). Auto-detecting the board is a later step.
3. **Orb recognition.** Sample the centre ~40% of each cell. Classify by hue/saturation:
   Heart is a pink rounded square, Fire red-orange, Light yellow, Water blue, Wood green,
   Dark purple. Show the detected board in an editor so the user can tap to fix a cell.
   Special orbs (enhanced `+`, locked, jammer, poison, mortal poison, bomb, blind) count as
   *unknown* in v1. The solver treats them as unmovable-for-goal, or the user fixes them.
4. **Solver** (pure Kotlin, no Android dependencies, JVM unit tests, like `engine/`):
   - Input: `Board(rows, cols, cells)`, `Goal` list (e.g. `Row(L)`, `Square3(D)`,
     `MinCombos(n)`), options (diagonals on/off, max path length, time budget).
   - Feasibility check from orb counts.
   - **Beam search** over drag paths. State = board + cursor cell + path. Expand to 8
     neighbours, never step straight back. Try every start cell. Keep the top *W* states per
     depth (start with W≈2000–5000, depth ≤ 40, time budget ~1–2 s on a phone; tune with
     measurements).
   - Score = big bonus per satisfied goal + partial progress (Row: most target orbs in one
     row; Square: most target orbs in the best 3×3 window) + combos × w − path length × small.
     Evaluate a state by simulating match → gravity → cascades without skyfall.
   - Output: path as a list of cells, the final board and the combos found.
5. **Preview.** Draw the path (numbered arrows) on a non-touchable overlay. Reuse
   `overlay/PlaybackOverlay` / `TouchMarkers`.
6. **Execute.** Convert the cell path to one `swipe` step of `quickreplay/v1`
   (`points: [{x, y, t}]` at cell centres, see [`script-format.md`](script-format.md)) and play
   it with the existing `engine/Player`. To verify on the simulator / game:
   - the speed at which every crossed cell is still registered (start with ~80–120 ms per cell
     and measure),
   - that diagonal moves through a cell corner do not also swap a side neighbour (aim exactly
     at centres, maybe hold briefly at each centre),
   - that the whole drag fits the in-game move timer.
   Note: the current player replays a swipe with its total duration and does not reproduce
   per-segment speed (README limitation). Continued strokes
   (`StrokeDescription.continueStroke`, API 26) are the way to fix that if needed.

## Suggested code layout

```
android/app/src/main/java/dev/quickreplay/pad/
  Board.kt        Orb enum, Board (rows, cols, cells), parsing the letter format above
  Matcher.kt      match + combo grouping + gravity + cascades (no skyfall)
  Shapes.kt       Row, Square3, Cross, LShape, Tpa, Column detection on a combo
  Goal.kt         goal model + scoring
  Solver.kt       beam search, feasibility check
  PathGesture.kt  cell path → quickreplay/v1 swipe step
  recognize/      screenshot → board (Android-dependent, keep thin)
  ui/             calibration, board editor, goal picker, path preview
```

Write tests in this order: Matcher (combos, cascades), Shapes, Solver on the example board
(row of Light found, 3×3 Dark reported impossible), then PathGesture.

## Testing without the real game

The example screenshot comes from the **dawnGlare PAD Simulator** (web). It has an unlimited
timer mode, which suits tuning the drag speed. Run it in the emulator's browser and play the
solver's swipe on it. Recognition can be tested offline with saved screenshots.

## Existing open-source solvers (reference only)

Found by web search on 2026-10-18. Licences and maintenance **not checked**. Do not copy
code before checking the licence (this project is MIT).

- [matthargett/padopt](https://github.com/matthargett/padopt): JS web solver (fork of kennytm's
  pndopt), screenshot import. Notes that it ignores cascades and timing.
- [pazusoba/core](https://github.com/pazusoba/core): fast core with a priority queue and several
  scoring profiles (combo, colour, shape).
- [toraora/PadPlayer](https://github.com/toraora/PadPlayer): beam search by default, plays via
  MonkeyRunner.
- [senkevinli/PAD-Auto-Solver](https://github.com/senkevinli/PAD-Auto-Solver): ADB capture + OpenCV
  orb detection + automated swipes. Combos only.
- [mloo-sp/pazudora-solver](https://github.com/mloo-sp/pazudora-solver),
  [alexknutson/Combo.Tips](https://github.com/alexknutson/Combo.Tips): heuristic solvers.

## Open questions for the owner

1. Target: the real PAD app on Android, the web simulator, or both? (The browser extension
   could solve on the simulator page directly.)
2. Board sizes for v1: 6×5 only, or also 5×4 / 7×6?
3. Minimum Android version for the solver (API 30 for `takeScreenshot` vs MediaProjection).
4. Which goals first: Row, 3×3, Cross, combo count? Are several goals at once needed
   (e.g. Row of L + 3×3 of D)?
5. Should the drag run automatically after solving, or only after the user confirms the preview?

## Risk

Automating input in the live game may break the game's terms of service and risk the account.
Decide this before shipping the solver for the real app. A "show path only" mode (the user drags
by hand) avoids automated input completely.
