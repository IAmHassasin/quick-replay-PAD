# PAD leader skill research (REHA-4)

Scripts behind [`docs/MATCHING-STYLES.md`](../../docs/MATCHING-STYLES.md). They are research
tools, not part of the app or the browser extension; nothing here is imported by the solver.

## Data (not committed)

The raw game data is GungHo's; only the aggregated summary is committed. Fetch the inputs into
`raw/` (git-ignored) before running the scripts:

```bash
cd tools/pad-research && mkdir -p raw
# JP server card and skill data, as published by Mapaler/PADDashFormation (intercepted from the
# game's download_card_data / download_skill_data API; see that repository's monsters-info/README.md).
curl -sL -o raw/mon_ja.json   https://raw.githubusercontent.com/Mapaler/PADDashFormation/master/monsters-info/mon_ja.json
curl -sL -o raw/skill_ja.json https://raw.githubusercontent.com/Mapaler/PADDashFormation/master/monsters-info/skill_ja.json
# Optional cross-check: the DadGuide / Tsubaki bot export (SQLite, ~225 MB).
curl -sL -o raw/dadguide.sqlite https://d30r6ivozz8w2a.cloudfront.net/db/dadguide.sqlite
```

Snapshot used for the document: PADDashFormation commit `fbbafa1` (2026-11-06), DadGuide export
`Last-Modified: 2026-11-07 05:03 UTC`, both fetched on 2026-11-07.

## Scripts

| Script | What it does |
|---|---|
| `analyze-leader-skills.mjs` | Takes the 5000 highest-numbered JP monsters with a leader skill, groups them by leader skill, maps every skill part to a matching style by its raw skill type (type semantics from TsubakiBotPad/pad-data-pipeline) and writes `matching-styles-summary.json` plus a Markdown rendering on stdout. `--top N` changes the sample size. |
| `verify-proposed-cases.mjs` | Checks `proposed-test-cases.json` (the test cases proposed in the document) with the existing matcher: every board has 30 orbs and no initial match, solved cases produce the expected combos when their path is replayed, impossible cases fail the orb count. The new goal types are evaluated by small reference checkers inside the script. |

```bash
node analyze-leader-skills.mjs
node verify-proposed-cases.mjs
```

Cross-check against the DadGuide export (same selection: JP monsters with a leader skill, top 5000
by JP number):

```bash
sqlite3 raw/dadguide.sqlite "select count(*), max(monster_no_jp) from monsters where on_jp=1 and leader_skill_id>0"
```
