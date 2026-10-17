# Quick Replay — notes for Claude / maintainers

Context, decisions and lessons from the first build session (2026-10-17). Read this before
changing the project. Plan: [`PLAN.md`](PLAN.md). Script format: [`script-format.md`](script-format.md).
User docs: [`../README.md`](../README.md), [`../browser/README.md`](../browser/README.md).

## What it is

Open-source (MIT) record & replay automation, Click Assistant style, with no limit on steps or
loops. Two clients share one JSON format `quickreplay/v1` (`spec/quickreplay.schema.json`):

- `android/` — Kotlin app, AccessibilityService + floating panel (REC / PLAY / SAVE).
- `browser/` — Chrome/Edge MV3 extension, plain ES modules, no build step.

## Layout (Android)

```
android/app/src/main/java/dev/quickreplay/
  model/     Script, Step (tap/longPress/swipe/wait), ScriptJson (org.json), scaling to other screens
  engine/    GesturePlan (step → gesture spec, clamped), Player (coroutines, unlimited loops)
  record/    StrokeRecorder (touch stream → tap/longPress/swipe), RecordingSession (pauses)
  service/   QuickReplayService (AccessibilityService), ReplayState (StateFlow)
  overlay/   OverlayPanel (draggable panel), CaptureLayer (full-screen recording layer)
  store/     ScriptStore (JSON files in filesDir, active script in prefs)
  ui/        MainActivity (library, import/export, repeat), PlaygroundActivity (test surface)
android/app/src/debug/  DebugCommandReceiver (adb automation, debug builds only, needs DUMP)
android/scripts/emulator-demo.sh  end-to-end demo + evidence screenshots in android/build/demo/
```

Pure logic (model/engine/record) has no Android dependencies and is unit-tested on the JVM.

## Commands

```bash
# Android (JDK 17+; this machine: JDK 21 at "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot")
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
ADB=adb bash scripts/emulator-demo.sh        # needs a running emulator/device with the debug APK

# Browser
cd browser
npm install && npx playwright install chromium
npm run lint && npm test                     # unit (node --test) + e2e (Playwright, headless Chromium)
```

Toolchain: Gradle 8.14.3 wrapper, AGP 8.13.2, Kotlin 2.2.21, compileSdk/targetSdk 36, minSdk 26.
Android Studio's bundled JBR is Java 25, which Gradle 8.14 cannot run on — use JDK 17/21.
`android/local.properties` (git-ignored) must escape the drive colon: `sdk.dir=C\:/Users/.../Android/Sdk`.

Emulator used for the demo: AVD `QR_API35` (Pixel 6, `system-images;android-35;google_apis;x86_64`,
1080×2400, Android 15). Install packages with `sdkmanager --package_file=<file>`: on Windows the
`;` in package ids is split by the `.bat` wrapper when passed as an argument.

## Verified facts and decisions

| Topic | Finding | Source |
|---|---|---|
| Inject touches without root | `AccessibilityService.dispatchGesture` (API 24+) | Android API, Smart-AutoClicker `GestureExecutor.kt` |
| Overlay permission | `TYPE_ACCESSIBILITY_OVERLAY` from the service needs no `SYSTEM_ALERT_WINDOW` | Smart-AutoClicker `OverlayManager.kt` |
| Watch touches on other apps | Impossible with coordinates: `FLAG_WATCH_OUTSIDE_TOUCH` only gives `ACTION_OUTSIDE` | Smart-AutoClicker `UserTouchWatcher.kt` |
| ⇒ Recording | Full-screen `CaptureLayer` receives the touch, records it, becomes `FLAG_NOT_TOUCHABLE`, forwards the same gesture with `dispatchGesture`, then becomes touchable again | design, demo-verified |
| Our own injected events | Carry `MotionEvent.TOOL_TYPE_UNKNOWN`; real touches and `adb shell input` do not | Smart-AutoClicker comment + demo |
| Limits | One gesture ≤ `GestureDescription.getMaxGestureDuration()` (60 s); scripts are sequences of gestures, so no script-level limit | Android API |
| Browser trusted input | `chrome.debugger` + CDP `Input.*` gives `isTrusted` events; recorder only records trusted events | e2e tests |

## Gotchas learned (do not regress)

- **Pass-through race**: window flag changes reach the input dispatcher asynchronously. Without the
  80 ms `PASS_THROUGH_SETTLE_MS` wait, the forwarded gesture landed on the capture layer itself.
  The capture layer also ignores `TOOL_TYPE_UNKNOWN` events.
- **`WindowManager.removeView` detaches asynchronously**: `isAttachedToWindow` stayed true, so
  `hide()` + `show()` (needed to keep the panel above the capture layer) silently skipped the
  re-add and the panel vanished while recording. Overlays track a `shown` flag and use
  `removeViewImmediate`.
- **`uiautomator dump` disables other accessibility services** (the service reconnects and state
  resets). Use screenshots, logcat or the debug receiver instead.
- **Playback generations**: a cancelled playback's `finally` must not overwrite a newer playback's
  state (`playbackGeneration`).
- **Demo coordinates** must avoid the floating panel (default position x=12dp, y=360dp), or `adb
  input` taps press REC/SAVE.
- Git Bash: set `MSYS_NO_PATHCONV=1` for `adb shell` paths like `/sdcard/...`, and pass native
  paths (`pwd -W`) to `adb push`.
- Debug receiver is exported but protected by `android.permission.DUMP` (adb shell has it, apps
  cannot get it); release builds do not contain it.
- Browser: control messages are accepted only from extension pages; recorded steps only from the
  recording tab's top frame. Password values are never recorded (`sensitive: true`).

## Known limitations (also in README)

- Android: single finger; touches during a forward window (~120 ms + gesture duration) are not
  recorded; swipe speed profile inside one swipe is not reproduced; import capped at 50 MB.
- Browser: top frame only; no `<select>`, drag, hover, inner-container scroll; a `navigate` step
  after a click waits only 3 s for the click's own navigation (slow POST / dynamic URLs can still
  double-navigate — the reviewer's scenario was not reproducible with link GETs in Chromium).
- Browser extension tested only in Playwright's headless Chromium, not branded Chrome/Edge.

## Next steps

1. Android: multi-touch, per-segment swipe timing (continued strokes, API 26+), visual step
   editor (drag targets like Click Assistant), move UI strings to resources (lint `SetTextI18n`).
2. Browser: watch CDP navigation events instead of the 3 s grace period; record `<select>`.
3. Shared: CLI to validate/convert scripts, cross-platform example library.
4. Distribution: GitHub releases / F-Droid (Google Play restricts Accessibility API usage).

## Conventions and rules

- English only in code, comments, logs, UI text and docs. No telemetry, no network calls.
- Do not copy code from GPL references (Smart-AutoClicker is GPL-3.0; this project is MIT).
- Company rule: commits must use a `@dssolution.jp` author/committer email.

## Reference repos (git-ignored `refs/`, re-clone if needed; read-only)

```bash
mkdir -p refs && cd refs
git clone --depth 1 https://github.com/Nain57/Smart-AutoClicker.git   # GPL-3.0, gestures/overlays
git clone --depth 1 https://github.com/nestorm001/AutoClicker.git     # minimal accessibility clicker
git clone --depth 1 https://github.com/puppeteer/replay.git           # Apache-2.0, Chrome Recorder steps
git clone --depth 1 https://github.com/A9T9/RPA.git                   # UI.Vision browser macro recorder
```
