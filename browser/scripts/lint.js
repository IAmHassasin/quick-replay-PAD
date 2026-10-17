// Syntax-checks every JavaScript file of the project with `node --check`.
import { execFileSync } from 'node:child_process';
import { readdirSync, readFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url));
const SKIP = new Set(['node_modules', 'test-results', 'playwright-report', '.git']);

function* jsFiles(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (SKIP.has(entry.name) || entry.name.startsWith('.e2e-profile')) continue;
    const full = join(dir, entry.name);
    if (entry.isDirectory()) yield* jsFiles(full);
    else if (/\.(m?js)$/.test(entry.name)) yield full;
  }
}

let failed = 0;
let count = 0;
for (const file of jsFiles(root)) {
  count++;
  try {
    execFileSync(process.execPath, ['--check', file], { stdio: 'pipe' });
  } catch (err) {
    failed++;
    console.error(`FAIL ${relative(root, file)}\n${err.stderr}`);
  }
}
// JSON files must parse too (manifest, examples).
for (const file of ['extension/manifest.json', ...readdirSync(join(root, 'examples')).map((f) => `examples/${f}`)]) {
  count++;
  try {
    JSON.parse(readFileSync(join(root, file), 'utf8'));
  } catch (err) {
    failed++;
    console.error(`FAIL ${file}: ${err.message}`);
  }
}
console.log(`lint: ${count - failed}/${count} files OK`);
process.exit(failed ? 1 : 0);
