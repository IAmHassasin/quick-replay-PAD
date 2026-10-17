# Quick Replay — Plan

## Goal

Open-source record & replay automation, similar to Click Assistant, with **no limit** on the
number of steps, script duration or loop count, for:

1. **Android** — floating overlay to record your touches and replay them on any app.
2. **Browser** — Chrome/Edge extension to record clicks/typing/scrolling and replay them.

Both share one documented JSON script format (`spec/quickreplay.schema.json`), so scripts can
be versioned, shared, edited by hand or generated.

## Android architecture

```
 MainActivity (script list, import/export, permissions)
        │
 QuickReplayService : AccessibilityService
        ├── OverlayController   floating panel (TYPE_ACCESSIBILITY_OVERLAY, no SYSTEM_ALERT_WINDOW)
        ├── Recorder            full-screen capture layer → gesture → forwarded via dispatchGesture
        ├── Player              plays steps sequentially (coroutine), loops 0 = infinite
        └── ScriptStore         JSON files in app storage, unlimited size
```

### Key technical decisions (verified)

| Topic | Finding | Source |
|---|---|---|
| Injecting touches without root | `AccessibilityService.dispatchGesture(GestureDescription)`, API 24+ | Android docs, `refs/Smart-AutoClicker/.../GestureExecutor.kt` |
| Overlay without extra permission | `TYPE_ACCESSIBILITY_OVERLAY` from the accessibility service context | `refs/Smart-AutoClicker/.../OverlayManager.kt` |
| Passive touch watching gives coordinates? | **No.** `FLAG_WATCH_OUTSIDE_TOUCH` only reports `ACTION_OUTSIDE` (coordinates are hidden for other apps' windows) | `refs/Smart-AutoClicker/.../UserTouchWatcher.kt` |
| ⇒ Recording strategy | Full-screen touchable capture layer records the stroke, then turns itself non-touchable and forwards the same stroke with `dispatchGesture`, so the target app still reacts | design decision |
| Per-gesture limits | `GestureDescription.getMaxGestureDuration()` (60 s) and `getMaxStrokeCount()` | Android API |
| ⇒ "Unlimited" | A script is a list of steps; each step is one gesture ≤ limit, steps are played one after another forever if requested. Very long holds are clamped to the max gesture duration | design decision |
| Different screen sizes | Script stores the recording screen size; player scales coordinates | design decision |

### Step types (Android)

`tap`, `longPress`, `swipe` / `path` (multi-point stroke with timing), `wait`.
Each step has `delayBeforeMs` that preserves the recorded rhythm.

## Browser architecture (Manifest V3)

```
 popup (record / stop / play / loops / import / export)
   │ chrome.runtime messages
 service worker (state, storage, player)
   ├── content script recorder: click, input/change, key (Enter/Tab/Escape), scroll, navigation
   └── player: chrome.debugger + CDP Input.* → trusted events (isTrusted = true)
```

- Selectors: prefer `id`, `data-testid`, `name`, `aria-label`, then a CSS path; coordinates are
  stored as fallback (same idea as Chrome DevTools Recorder / `puppeteer/replay`).
- Storage: `chrome.storage.local` with `unlimitedStorage` permission → no size cap.

## Script format (v1, summary)

```json
{
  "format": "quickreplay/v1",
  "name": "Daily check-in",
  "platform": "android",
  "repeat": 0,
  "screen": { "width": 1080, "height": 2400 },
  "steps": [
    { "type": "tap", "x": 540, "y": 1200, "delayBeforeMs": 300 },
    { "type": "swipe", "points": [{ "x": 500, "y": 1800, "t": 0 }, { "x": 500, "y": 600, "t": 250 }] },
    { "type": "wait", "ms": 1000 }
  ]
}
```

Full spec: `docs/script-format.md`.

## Verification / demo plan

- Android: build debug APK with Gradle, run on emulator `QR_API35`, enable the accessibility
  service through `adb`, load a sample script and replay it against a test screen; screenshots
  as evidence. Unit tests for script parsing/scaling.
- Browser: Playwright loads the unpacked extension in Chromium, records interactions on a local
  test page, replays them and asserts the page state.

## Out of scope for MVP / next steps

- Image/condition based triggers (Smart-AutoClicker territory), OCR.
- Script editor UI with drag-and-drop targets (scripts can be edited as JSON now).
- Play Store distribution (accessibility policy review).

## Reference repos (`refs/`, read-only)

- `Nain57/Smart-AutoClicker` (GPL-3.0) — accessibility gestures + overlays architecture.
- `nestorm001/AutoClicker` — minimal accessibility auto clicker.
- `puppeteer/replay` (Apache-2.0) — Chrome Recorder step model.
- `A9T9/RPA` (UI.Vision) — browser macro recorder extension.

No code is copied from GPL references; this project is MIT licensed.
