# Quick Replay for Chrome / Edge

A Manifest V3 extension that records what you do on a web page (clicks, typing, Enter / Tab /
Escape, scrolling, page navigation) and replays it with **no limit** on the number of steps or
loops. Scripts use the shared `quickreplay/v1` JSON format
([`docs/script-format.md`](../docs/script-format.md)), so they can be exported, edited by hand
and imported again.

Plain JavaScript ES modules, no build step: the `extension/` folder is loaded as-is.

## Install (developer mode)

1. Open `chrome://extensions` (Edge: `edge://extensions`).
2. Turn on **Developer mode**.
3. Click **Load unpacked** and select the `browser/extension/` folder.
4. Pin "Quick Replay" to the toolbar.

Requires Chrome / Edge 118 or newer.

## Usage

1. Open the page you want to automate (http, https or file URL).
2. Open the Quick Replay popup, optionally type a name, click **Record**.
3. Use the page normally. Navigation to other pages in the same tab is followed automatically.
4. Open the popup again and click **Stop recording**. The script is saved in the list.
5. Set **Repeat** (`0` = forever until you click **Stop playback**) and an optional delay
   between loops, then click **Play** on a script. Playback runs in the current tab.
6. Each script can be renamed, exported as JSON (download) and deleted. **Import…** loads a
   JSON file; it is rejected unless it is a valid `quickreplay/v1` script with
   `"platform": "browser"`.

The popup shows progress (loop i of N, step j of M) and any playback error. Closing the popup
does not stop recording or playback.

## How it works

- **Recorder** (`extension/recorder.js`): a content script injected with
  `chrome.scripting.executeScript` into the top frame when recording starts, and again after
  each page load in that tab while recording. It only listens to trusted (real user) events:
  - `click` (left button): a selector for the clicked element (or its closest clickable
    ancestor) plus the click position in viewport CSS pixels as a fallback. Selector priority:
    `#id`, `[data-testid]` (also `data-test-id`, `data-test`), `tag[name]`, `tag[aria-label]`,
    then the shortest unique CSS path (`nth-of-type` segments, anchored on an ancestor id when
    possible).
  - `type`: text inputs, textareas and `contenteditable` elements, recorded on `change` and
    after 800 ms without typing; the step holds the final text.
  - `key`: only `Enter`, `Tab` and `Escape` without modifiers.
  - `scroll`: the window scroll position, recorded once scrolling pauses for 400 ms.
  - `navigate`: top-level URL changes of the recorded tab (detected by the service worker via
    `chrome.tabs.onUpdated`, so this also covers address-bar navigation and SPA URL changes).
- **Timing**: every step gets `delayBeforeMs` = real time since the previous step.
  **A single recorded delay is capped at 10 seconds** so idle pauses do not end up in the
  script; edit the JSON (or add a `wait` step) if you need a longer pause.
- **Player** (`extension/player.js`, runs in the service worker) attaches `chrome.debugger`
  (CDP 1.3) to the tab and produces trusted input:
  - `click`: resolves the selector with `Runtime.evaluate` (waits up to 5 s for it to appear),
    scrolls the element into view and clicks its center with `Input.dispatchMouseEvent`
    (moved / pressed / released). If the selector does not resolve, the recorded x/y is used
    and a warning is shown.
  - `type`: focuses the element, selects its content, then `Input.insertText` (replaces the
    value). Fails with an error if the element cannot be found.
  - `key`: `Input.dispatchKeyEvent` (keyDown / keyUp) to the focused element.
  - `scroll`: `window.scrollTo(x, y)`.
  - `navigate`: `Page.navigate` and waits for the load event — skipped if the tab is already on
    that URL (e.g. the previous click followed a link).
  - `startUrl`: before each loop, the tab is navigated to `startUrl` unless it is already there.
  - Steps run one by one; nothing is pre-built per step, so huge scripts and infinite loops
    run in constant memory. **Stop** interrupts any delay immediately. The debugger is always
    detached when playback completes, is stopped or fails, and errors are shown in the popup.
- **Storage**: `chrome.storage.local` with `unlimitedStorage`, one key per script plus a small
  index. A recording in progress is also saved there, so it survives a service worker restart.

### Recorded passwords

Values of `<input type="password">` are **never recorded**. The step is stored as
`{ "type": "type", "selector": "...", "text": "", "sensitive": true }`. During playback a
sensitive step with empty `text` is **skipped** (with a warning) instead of clearing the
field. If you accept the risk of storing a secret in plain text, you can put the value in
`text` by editing the exported JSON; it is then typed like any other text.

## Permissions

| Permission | Why |
|---|---|
| `debugger` | Playback sends real (trusted, `isTrusted === true`) mouse and keyboard events through the Chrome DevTools Protocol. Pages that ignore synthetic events still react. While playing, Chrome shows a **"Quick Replay started debugging this browser"** banner; clicking *Cancel* on it stops playback. |
| `scripting`, `<all_urls>` host access | Inject the recorder into the page being recorded, on any site. |
| `tabs` | Read the tab URL (start URL, navigation steps) and follow page loads while recording. |
| `activeTab` | Act on the tab the popup was opened from. |
| `storage`, `unlimitedStorage` | Save scripts locally without a size cap. |

No telemetry and no network requests: the extension only talks to the pages you automate.

## Limitations

- Only the top frame is recorded. Elements inside iframes (especially cross-origin ones) and
  inside shadow DOM get no selector; clicks there fall back to coordinates, typing there is not
  recorded.
- Canvas-only apps (games, maps, drawing tools) have no useful selectors: clicks replay at the
  recorded viewport coordinates, which only work if layout, zoom and scroll match.
- Not recorded: right / middle clicks, drag and drop, hover-only menus, `<select>` choices,
  date/time/color/range/file inputs, scrolling inside scrollable containers (only the window),
  keys other than Enter / Tab / Escape, keyboard shortcuts with modifiers.
- Actions done before a new page finishes loading (`status: complete`) are not recorded,
  because the recorder is injected after the load.
- Playback cannot start while DevTools or another debugger client holds the tab's debugger
  session, and it cannot run on `chrome://`, `edge://` or Web Store pages.
- A `navigate` step right after a click/key waits up to 3 s for the click's own navigation to
  reach the recorded URL before navigating by itself. If that navigation is slower (e.g. a slow
  form POST) or the URL differs per run (ids, timestamps), the player still navigates to the
  recorded URL, which can replace the pending request. Watching CDP navigation events is the
  planned fix.
- Playback relies on Chrome keeping the service worker alive while a debugger session is
  attached (Chrome 118+). Very long runs were not tested beyond the automated tests below.

## Development and tests

```bash
cd browser
npm install
npx playwright install chromium   # first time only
npm run lint        # node --check on every .js file + JSON parse of manifest/examples
npm run test:unit   # node --test: lib/script.js validation and normalization
npm run test:e2e    # Playwright + Chromium with the unpacked extension (headless)
npm test            # unit + e2e
```

The end-to-end tests (`test/e2e/extension.test.js`) start a local HTTP server for
`test/fixtures/`, launch Chromium (`channel: 'chromium'`, headless, `--load-extension`), drive
the extension through its service worker (`globalThis.QuickReplay.handle(message)`, the same
API the popup uses), record real Playwright interactions, replay them, and check the page.
Set `QR_HEADED=1` to watch the browser.

### Message API (service worker)

The popup sends `chrome.runtime.sendMessage({ type, ... })`; responses are
`{ ok: true, ... }` or `{ ok: false, error }`. Types: `qr:getState`, `qr:startRecording`
`{tabId?, name?}`, `qr:stopRecording` `{name?}`, `qr:play` `{scriptId | script, tabId?, repeat?,
loopDelayMs?}`, `qr:stopPlayback`, `qr:listScripts`, `qr:getScript` `{id}`, `qr:renameScript`
`{id, name}`, `qr:deleteScript` `{id}`, `qr:importScript` `{text | script}`,
`qr:clearMessages`. State changes are broadcast as `{ type: 'qr:state', state }`.

## Layout

```
browser/
  extension/          load this folder unpacked
    manifest.json
    background.js     service worker: state, message API, recording control
    player.js         CDP player (chrome.debugger)
    recorder.js       content script recorder
    store.js          chrome.storage.local persistence
    popup.html/.css/.js
    lib/script.js     validateScript / normalizeScript / parseScript (no chrome APIs)
  examples/           example script(s)
  scripts/lint.js
  test/unit/          node --test
  test/e2e/           Playwright end-to-end tests
  test/fixtures/      pages used by the e2e tests
```
