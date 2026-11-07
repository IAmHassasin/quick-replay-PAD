/**
 * Classifies the matching conditions of JP leader skills (research for REHA-4).
 *
 * Input (not committed, see README.md): raw/mon_ja.json and raw/skill_ja.json, the JP server's
 * card and skill data as published by Mapaler/PADDashFormation (parsed from GungHo's
 * download_card_data / download_skill_data API responses).
 *
 * Selection: the N enabled monsters with the highest JP number that have a leader skill
 * (default 5000). Leader skills are grouped by skill id; type 138 (multi-part) skills are
 * expanded into parts. Each part is mapped to a matching style by its raw skill type and
 * parameters, following the type semantics documented in TsubakiBotPad/pad-data-pipeline
 * (etl/pad/raw/skills/leader_skill_info.py); awakening-triggered conditions (types 271, 280,
 * 285) are mapped through the awakening they name.
 *
 * Output: matching-styles-summary.json (aggregates only) and a Markdown rendering on stdout.
 *   node analyze-leader-skills.mjs [--top 5000]
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const here = (p) => fileURLToPath(new URL(p, import.meta.url));
const topArg = process.argv.indexOf('--top');
const TOP = topArg > 0 ? Number(process.argv[topArg + 1]) : 5000;

const ATTR_LETTERS = ['R', 'B', 'G', 'L', 'D', 'H', 'J', 'P', 'M', 'X'];
const EXAMPLES_PER_STYLE = 3;

/** Awakening-trigger bits of skill type 271 (derived from the bracketed names in the JP texts). */
const TRIGGER_BITS = {
  0: 'tpa', 1: 'vdp', 2: 'l', 3: 'cross', 4: 't',
  5: 'rowR', 6: 'rowB', 7: 'rowG', 8: 'rowL', 9: 'rowD',
  10: 'colors3', 11: 'colors4', 12: 'colors5',
  15: 'comboR', 16: 'comboB', 17: 'comboG', 18: 'comboL', 19: 'comboD',
  20: 'rowH', 21: 'blob12', 22: 'pairRB', 23: 'pairBG', 24: 'pairGR',
};
/** Awakening ids of skill type 280 ("each time awakening X activates"); names from DadGuide. */
const AWAKENING_IDS = {
  22: 'rowR', 23: 'rowB', 24: 'rowG', 25: 'rowL', 26: 'rowD', 27: 'tpa', 48: 'vdp', 60: 'l', 78: 'cross',
  79: 'colors3', 80: 'colors4', 81: 'colors5', 82: 'blob12',
};

/** Matching styles; `support` is the verdict against the current solver goals. */
const STYLES = {
  combos: { title: 'N+ combos (any colours)', support: 'supported', goal: 'combos' },
  combos_exact: { title: 'Exactly N combos', support: 'unsupported', goal: '-' },
  rainbow: { title: 'K+ colours matched at once (of a set)', support: 'unsupported', goal: '-' },
  colors_at_once: { title: 'A fixed set of colours matched at once', support: 'unsupported', goal: '-' },
  same_color_combos: { title: 'N+ separate combos of one colour', support: 'unsupported', goal: '-' },
  combo_pattern: { title: 'Mixed combo pattern (repeated colour + other colours)', support: 'unsupported', goal: '-' },
  blob_min: { title: 'N+ orbs of one colour connected', support: 'unsupported', goal: '-' },
  blob_exact: { title: 'Exactly N orbs of one colour (N != 4)', support: 'unsupported', goal: '-' },
  tpa: { title: 'Exactly 4 orbs (two-pronged attack)', support: 'supported', goal: 'tpa' },
  row: { title: 'Full row of one colour', support: 'supported', goal: 'row' },
  column: { title: 'Full column of one colour', support: 'supported', goal: 'column' },
  cross: { title: 'Cross of 5 (colour)', support: 'supported', goal: 'cross' },
  heart_cross: { title: 'Cross of 5 hearts', support: 'supported', goal: 'cross' },
  lshape: { title: 'L of 5', support: 'supported', goal: 'lshape' },
  tshape: { title: 'T of 5', support: 'unsupported', goal: '-' },
  square: { title: '3x3 square (void damage piercer)', support: 'supported', goal: 'square' },
  heal_match: { title: 'Heart match (4 hearts / hearts in a set)', support: 'partial', goal: '-' },
  min_erase: { title: 'Cannot erase N or fewer orbs (every match must be larger)', support: 'unsupported', goal: '-' },
  enhanced5: { title: '5 orbs including an enhanced orb', support: 'unsupported', goal: '-' },
};

const OTHER_TYPES = {
  11: 'passive stats', 22: 'passive stats', 23: 'passive stats', 29: 'passive stats', 48: 'passive stats',
  121: 'passive stats', 129: 'passive stats', 136: 'passive stats', 137: 'passive stats',
  13: 'autoheal', 14: 'resolve', 41: 'counterattack', 53: 'drop rate', 54: 'coin rate', 148: 'rank exp',
  264: 'drop/plus rate', 265: 'drop rate', 15: 'extra move time', 185: 'extra move time', 178: 'fixed move time',
  38: 'HP threshold', 39: 'HP threshold', 43: 'HP threshold', 44: 'HP threshold', 94: 'HP threshold', 95: 'HP threshold',
  96: 'HP threshold', 97: 'HP threshold', 122: 'HP threshold', 123: 'HP threshold', 130: 'HP threshold',
  131: 'HP threshold', 139: 'HP threshold', 183: 'HP threshold', 105: 'reduced RCV', 106: 'reduced HP', 107: 'reduced HP',
  100: 'skill used', 133: 'skill used', 270: 'skills used per turn', 162: '7x6 board', 186: '7x6 board',
  163: 'no skyfall', 177: 'no skyfall / orbs remaining', 254: 'skyfall prediction', 197: 'poison disabled',
  125: 'team composition', 175: 'team composition', 203: 'team composition', 217: 'team composition',
  229: 'team composition', 245: 'team composition', 213: 'grants awakening', 198: 'heal amount', 155: 'multiplayer',
  33: 'taiko', 12: 'bonus attack', 16: 'damage reduction', 17: 'damage reduction', 36: 'damage reduction',
};

const mons = JSON.parse(readFileSync(here('raw/mon_ja.json'), 'utf8'));
const skills = JSON.parse(readFileSync(here('raw/skill_ja.json'), 'utf8'));

const eligible = mons.filter((m) => m && !m.isEmpty && m.enabled && m.id < 100000 && m.leaderSkillId > 0);
const selected = [...eligible].sort((a, b) => b.id - a.id).slice(0, TOP);
const byLs = new Map();
for (const m of selected) {
  if (!byLs.has(m.leaderSkillId)) byLs.set(m.leaderSkillId, []);
  byLs.get(m.leaderSkillId).push(m);
}

function parts(id, depth = 0) {
  const s = skills[id];
  if (!s || depth > 4) return [];
  if (s.type === 138) return s.params.flatMap((p) => parts(p, depth + 1));
  return [s];
}

function bits(mask) {
  const out = [];
  for (let b = 0; b < 31; b++) if (mask & (1 << b)) out.push(b);
  return out;
}
/** Letters of an attribute mask; "any" when all five attributes are allowed ("any+H" with hearts). */
const attrs = (mask) => ((mask & 31) === 31
  ? `any${mask & 32 ? '+H' : ''}`
  : bits(mask).map((b) => ATTR_LETTERS[b] ?? `?${b}`).join(''));
/** Hearts are a condition only when the mask names them without allowing every colour. */
const hasHeart = (mask) => Boolean(mask & 32) && (mask & 31) !== 31;
const popcount = (mask) => bits(mask).length;
/** How the colour of a blob is constrained: one colour, one of a set, or any. */
const colorKind = (mask) => ((mask & 31) === 31 ? 'any colour' : bits(mask).length === 1 ? 'one colour' : `one of ${attrs(mask)}`);
const clean = (text) => text.replace(/\^[0-9a-f]{6}\^|\^p|\n/g, ' ').replace(/\s+/g, ' ').trim();

/**
 * Attribute "slot" lists (types 124, 164, 171, 201, 206): each slot is one combo of any attribute
 * in its mask; `min` of them are needed.
 */
function classifySlots(slots, min, hit) {
  slots = slots.filter(Boolean);
  if (!slots.length) return;
  const single = slots.every((m) => bits(m).length === 1);
  const distinct = new Set(slots).size;
  const heart = slots.some(hasHeart);
  if (single && distinct === 1) {
    hit('same_color_combos', { color: attrs(slots[0]), n: min });
    return;
  }
  if (single && distinct === slots.length && min >= slots.length) {
    hit('colors_at_once', { colors: slots.map(attrs).join('+'), heart });
    if (heart) hit('heal_match', { with: slots.map(attrs).join('+') });
    return;
  }
  if (single) {
    hit('combo_pattern', { pattern: slots.map(attrs).join('+'), n: min });
    if (heart) hit('heal_match', { with: slots.map(attrs).join('+') });
    return;
  }
  hit('rainbow', { k: min, of: attrs(slots[0]), heart });
}

/** Colours of a type 157 part: pairs of (attribute, multiplier). */
function crossColors(d) {
  let mask = 0;
  for (let i = 0; i < d.length; i += 2) mask |= 1 << d[i];
  return attrs(mask);
}

/** Maps one skill part to matching styles; calls hit(style, params). */
function classify(p, text, hit) {
  const d = p.params;
  switch (p.type) {
    case 66: case 98: case 103: case 104: case 166: case 169: case 223:
      hit('combos', { n: d[0] });
      break;
    case 101:
      hit('combos_exact', { n: d[0] });
      break;
    case 61: case 165: case 170: case 194: case 199: {
      // Hearts are required when K exceeds the number of attributes in the set (e.g. 5 colours + hearts).
      const heart = Boolean(d[0] & 32) && d[1] > popcount(d[0] & 31);
      hit('rainbow', { k: d[1], of: attrs(d[0]), heart });
      if (heart) hit('heal_match', { with: `${d[1]} of ${attrs(d[0])}` });
      break;
    }
    case 124: classifySlots(d.slice(0, 5), d[5], hit); break;
    case 164: case 171: case 201: classifySlots(d.slice(0, 4), d[4], hit); break;
    case 206: classifySlots(d.slice(0, 4), d[5], hit); break;
    case 109: case 119: case 159: case 167: case 182: case 200:
      hit('blob_min', { n: d[1], colors: attrs(d[0]), kind: colorKind(d[0]) });
      if (hasHeart(d[0])) hit('heal_match', { with: `${d[1]}+ connected of ${attrs(d[0])}` });
      break;
    case 192: case 219:
      hit('blob_min', { n: d[1], colors: attrs(d[0]), kind: colorKind(d[0]), together: p.type === 192 });
      if (hasHeart(d[0])) hit('heal_match', { with: `${d[1]}+ connected of ${attrs(d[0])}` });
      break;
    case 235: {
      const n = d[2];
      if (n === 0) break; // "every time the colour is matched": any combo, no size condition
      if (text.includes(`${n}個消し`)) {
        if (n === 4) hit('tpa', { colors: attrs(d[0]) });
        else hit('blob_exact', { n, colors: attrs(d[0]) });
      } else {
        hit('blob_min', { n, colors: attrs(d[0]), kind: colorKind(d[0]) });
      }
      if (hasHeart(d[0])) hit('heal_match', { with: `${n} of ${attrs(d[0])} per match` });
      break;
    }
    case 158: hit('min_erase', { n: d[0] }); break;
    case 157: hit('cross', { colors: crossColors(d) }); break;
    case 210: hit('cross', { colors: attrs(d[0]) }); break;
    case 151: case 209:
      hit('heart_cross', {});
      hit('heal_match', { with: 'cross of 5 hearts' });
      break;
    case 193: case 220: case 221: hit('lshape', { colors: attrs(d[0]) }); break;
    case 149:
      hit('heal_match', { with: '4 hearts' });
      hit('tpa', { colors: 'H' });
      break;
    case 150: hit('enhanced5', {}); break;
    case 285: hit('column', { colors: attrs(d[0]) }); break;
    case 271: for (const b of bits(d[0])) hitTrigger(TRIGGER_BITS[b] ?? `bit${b}`, hit); break;
    case 280: hitTrigger(AWAKENING_IDS[d[0]] ?? `awakening${d[0]}`, hit); break;
    default:
      if (p.type in OTHER_TYPES) hit(`__other:${OTHER_TYPES[p.type]}`, {});
      else hit('__unmapped', { type: p.type });
  }
}

function hitTrigger(name, hit) {
  if (name === 'tpa') hit('tpa', { colors: 'own' });
  else if (name === 'vdp') hit('square', { colors: 'own' });
  else if (name === 'l') hit('lshape', { colors: 'own' });
  else if (name === 'cross') hit('cross', { colors: 'own' });
  else if (name === 't') hit('tshape', { colors: 'own' });
  else if (name.startsWith('row')) {
    hit('row', { colors: name.slice(3) });
    if (name === 'rowH') hit('heal_match', { with: 'row of hearts' });
  }
  else if (name.startsWith('colors')) hit('rainbow', { k: Number(name.slice(6)), of: 'RBGLD', heart: false });
  else if (name.startsWith('combo')) hit('same_color_combos', { color: name.slice(5), n: 2 });
  else if (name === 'blob12') hit('blob_min', { n: 12, colors: 'own', kind: 'one colour' });
  else if (name.startsWith('pair')) hit('colors_at_once', { colors: name.slice(4).split('').join('+'), heart: false });
  else hit('__unmapped', { trigger: name });
}

// Aggregate per leader skill (a skill counts once per style, however many parts hit it).
const styles = new Map();
const other = new Map();
const unmapped = new Map();
for (const [lsId, monsters] of byLs) {
  const text = clean(skills[lsId].description);
  const seen = new Map();
  for (const p of parts(lsId)) {
    classify(p, text, (style, params) => {
      if (style === '__unmapped') {
        const k = `${JSON.stringify(params)} skill ${lsId}: ${text}`;
        unmapped.set(k, (unmapped.get(k) ?? 0) + 1);
        return;
      }
      if (style.startsWith('__other:')) {
        const k = style.slice(8);
        if (!other.has(k)) other.set(k, { skills: new Set(), monsters: 0 });
        if (!other.get(k).skills.has(lsId)) {
          other.get(k).skills.add(lsId);
          other.get(k).monsters += monsters.length;
        }
        return;
      }
      if (!seen.has(style)) seen.set(style, []);
      seen.get(style).push(params);
    });
  }
  for (const [style, paramList] of seen) {
    if (!styles.has(style)) styles.set(style, { skills: 0, monsters: 0, params: new Map(), examples: [] });
    const s = styles.get(style);
    s.skills++;
    s.monsters += monsters.length;
    for (const params of paramList) {
      const k = JSON.stringify(params);
      s.params.set(k, (s.params.get(k) ?? 0) + 1);
    }
    s.examples.push({
      skillId: lsId,
      monsters: monsters.length,
      monster: [...monsters].sort((a, b) => b.id - a.id)[0],
      text,
    });
  }
}

function paramKey(style, p) {
  switch (style) {
    case 'combos': case 'combos_exact': case 'min_erase': return `N=${p.n}`;
    case 'rainbow': return `K=${p.k} of ${p.of}${p.heart ? ' (heart in set)' : ''}`;
    case 'colors_at_once': return p.colors;
    case 'same_color_combos': return `${p.n} x ${p.color}`;
    case 'combo_pattern': return `${p.n} of ${p.pattern}`;
    case 'blob_min': return `N=${p.n} (${p.together ? 'two colours together' : p.kind})`;
    case 'blob_exact': return `N=${p.n}`;
    case 'heal_match': return p.with;
    default: return p.colors ?? '-';
  }
}

function paramSummary(style, params) {
  const hist = new Map();
  for (const [k, n] of params) {
    const key = paramKey(style, JSON.parse(k));
    hist.set(key, (hist.get(key) ?? 0) + n);
  }
  return [...hist].sort((a, b) => b[1] - a[1]);
}

const summary = {
  generatedAt: new Date().toISOString(),
  source: 'Mapaler/PADDashFormation monsters-info/mon_ja.json + skill_ja.json (JP server card and skill data)',
  totals: {
    monstersInFile: mons.length,
    monstersWithLeaderSkill: eligible.length,
    selected: selected.length,
    idRange: [selected[selected.length - 1].id, selected[0].id],
    distinctLeaderSkills: byLs.size,
  },
  styles: Object.fromEntries([...styles].sort((a, b) => b[1].monsters - a[1].monsters).map(([style, s]) => [style, {
    title: STYLES[style]?.title,
    support: STYLES[style]?.support,
    currentGoal: STYLES[style]?.goal,
    leaderSkills: s.skills,
    monsters: s.monsters,
    parameters: paramSummary(style, s.params),
    // The newest monsters using the style.
    examples: s.examples
      .sort((a, b) => b.monster.id - a.monster.id)
      .slice(0, EXAMPLES_PER_STYLE)
      .map((e) => ({
        skillId: e.skillId,
        monstersUsingSkill: e.monsters,
        monsterNo: e.monster.id,
        monsterName: e.monster.name,
        text: e.text,
      })),
  }])),
  otherConditions: Object.fromEntries([...other]
    .sort((a, b) => b[1].monsters - a[1].monsters)
    .map(([k, v]) => [k, { leaderSkills: v.skills.size, monsters: v.monsters }])),
  unmapped: Object.fromEntries(unmapped),
};
writeFileSync(here('matching-styles-summary.json'), `${JSON.stringify(summary, null, 2)}\n`);

// Markdown rendering.
const t = summary.totals;
console.log(`Monsters in file: ${t.monstersInFile}; with a leader skill: ${t.monstersWithLeaderSkill}; selected: ${t.selected} (No. ${t.idRange[0]}..${t.idRange[1]}); distinct leader skills: ${t.distinctLeaderSkills}\n`);
console.log('| Style | Leader skills | Monsters | Parameters (skills) | Support |');
console.log('|---|---:|---:|---|---|');
for (const [style, s] of Object.entries(summary.styles)) {
  const params = s.parameters.slice(0, 6).map(([k, n]) => `${k}: ${n}`).join('; ');
  console.log(`| ${s.title} (\`${style}\`) | ${s.leaderSkills} | ${s.monsters} | ${params} | ${s.support} |`);
}
console.log('\n### Examples\n');
for (const [style, s] of Object.entries(summary.styles)) {
  console.log(`**${s.title}** (\`${style}\`)\n`);
  for (const e of s.examples) console.log(`- No. ${e.monsterNo} ${e.monsterName} (skill ${e.skillId}, ${e.monstersUsingSkill} monsters): ${e.text}`);
  console.log();
}
console.log('| Other condition | Leader skills | Monsters |');
console.log('|---|---:|---:|');
for (const [k, v] of Object.entries(summary.otherConditions)) console.log(`| ${k} | ${v.leaderSkills} | ${v.monsters} |`);
console.log('\nunmapped:', JSON.stringify(summary.unmapped));
