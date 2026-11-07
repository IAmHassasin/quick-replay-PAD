// Captures dawnGlare board screenshots for BoardRecognizerTest.
// Usage (from browser/, where playwright is installed):
//   node ../tools/pad-fixtures/capture-dawnglare.cjs <outDir> <name>=<patt> [<name>=<patt> ...]
// Writes <name>.png (desktop page, 720x1600 viewport, like dawnglare-random-N.png) and <name>.txt
// (the board letters read from the page DOM, one row per line).
const path = require('node:path');
const fs = require('node:fs');
const { chromium } = require(path.resolve('node_modules/playwright'));

const LETTERS = {
  red: 'R', blue: 'B', green: 'G', light: 'L', dark: 'D', heart: 'H', jammer: 'J', poison: 'P', mortalpoison: 'M',
  xbomb: 'X', spinner: '?',
};

async function main() {
  const [outDir, ...specs] = process.argv.slice(2);
  if (!outDir || specs.length === 0) throw new Error('usage: capture-dawnglare.cjs <outDir> <name>=<patt> ...');
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({ viewport: { width: 720, height: 1600 } });
    for (const spec of specs) {
      const [name, patt] = spec.split('=');
      await page.goto(`https://pad.dawnglare.com/?patt=${patt}`, { waitUntil: 'networkidle' });
      await page.waitForTimeout(1000);
      const tiles = await page.$$eval('#tiles .tile', (els) => els.map((t) => {
        const r = t.getBoundingClientRect();
        return { color: t.getAttribute('tilecolor'), x: r.left, y: r.top, size: r.width };
      }));
      tiles.sort((a, b) => (Math.abs(a.y - b.y) > a.size / 2 ? a.y - b.y : a.x - b.x));
      const rows = [];
      for (let i = 0; i < tiles.length; i += 6) {
        rows.push(tiles.slice(i, i + 6).map((t) => LETTERS[t.color] ?? `<${t.color}>`).join(''));
      }
      await page.screenshot({ path: path.join(outDir, `${name}.png`) });
      fs.writeFileSync(path.join(outDir, `${name}.txt`), `${rows.join('\n')}\n`);
      console.log(name, JSON.stringify(tiles[0]), '\n' + rows.join('\n'));
    }
  } finally {
    await browser.close();
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
