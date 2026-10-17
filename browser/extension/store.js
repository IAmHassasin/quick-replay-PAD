/**
 * Script storage on top of `chrome.storage.local` (with `unlimitedStorage`).
 *
 * Layout: one key per script (`qr:script:<id>`) plus a small index (`qr:index`) holding
 * metadata, so listing scripts never loads every step of every script.
 */

const INDEX_KEY = 'qr:index';
const scriptKey = (id) => `qr:script:${id}`;

let queue = Promise.resolve();

/** Runs index read-modify-write operations one at a time. */
function serialize(fn) {
  const run = queue.then(fn);
  queue = run.catch(() => {});
  return run;
}

async function readIndex() {
  const { [INDEX_KEY]: index } = await chrome.storage.local.get(INDEX_KEY);
  return Array.isArray(index) ? index : [];
}

function metaOf(id, script) {
  return {
    id,
    name: script.name,
    stepCount: script.steps.length,
    startUrl: script.startUrl ?? '',
    updatedAt: new Date().toISOString(),
  };
}

/**
 * Lists saved scripts (metadata only), newest first.
 * @returns {Promise<Array<{id: string, name: string, stepCount: number, startUrl: string, updatedAt: string}>>}
 */
export async function listScripts() {
  const index = await readIndex();
  return [...index].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
}

/**
 * Loads one script.
 * @param {string} id
 * @returns {Promise<object>} The script.
 * @throws {Error} When the script does not exist.
 */
export async function getScript(id) {
  const key = scriptKey(id);
  const { [key]: script } = await chrome.storage.local.get(key);
  if (!script) throw new Error(`Script not found: ${id}`);
  return script;
}

/**
 * Saves a script (already validated) and returns its metadata.
 * @param {object} script
 * @param {string} [id] Existing id to overwrite; a new id is generated otherwise.
 */
export function saveScript(script, id = crypto.randomUUID()) {
  return serialize(async () => {
    const meta = metaOf(id, script);
    const index = (await readIndex()).filter((m) => m.id !== id);
    index.push(meta);
    await chrome.storage.local.set({ [scriptKey(id)]: script, [INDEX_KEY]: index });
    return meta;
  });
}

/**
 * Renames a saved script.
 * @param {string} id
 * @param {string} name Non-empty new name.
 */
export function renameScript(id, name) {
  const trimmed = String(name ?? '').trim();
  if (!trimmed) return Promise.reject(new Error('Name must not be empty'));
  return serialize(async () => {
    const script = await getScript(id);
    script.name = trimmed;
    const meta = metaOf(id, script);
    const index = (await readIndex()).map((m) => (m.id === id ? meta : m));
    await chrome.storage.local.set({ [scriptKey(id)]: script, [INDEX_KEY]: index });
    return meta;
  });
}

/**
 * Deletes a saved script. Deleting an unknown id is a no-op.
 * @param {string} id
 */
export function deleteScript(id) {
  return serialize(async () => {
    const index = (await readIndex()).filter((m) => m.id !== id);
    await chrome.storage.local.remove(scriptKey(id));
    await chrome.storage.local.set({ [INDEX_KEY]: index });
  });
}
