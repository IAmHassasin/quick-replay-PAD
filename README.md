# Quick Replay

Open-source record & replay automation in the spirit of Click Assistant, **without limits** on
script length, duration or loop count.

| Part | What it is | Status |
|---|---|---|
| [`android/`](android) | Android app: floating panel, record your touches, replay them on any app (Accessibility API, no root) | MVP, demo on emulator |
| [`browser/`](browser) | Chrome/Edge extension (MV3): record clicks/typing/keys/scroll/navigation, replay with trusted input | MVP, Playwright e2e |
| [`spec/`](spec) + [`docs/script-format.md`](docs/script-format.md) | Shared JSON script format `quickreplay/v1`; combined scripts `quickreplay-sequence/v1` (Android) | v1 |

Design and verified technical decisions: [`docs/PLAN.md`](docs/PLAN.md).

## Android

### Download

Prebuilt APKs are attached to [GitHub Releases](https://github.com/IAmHassasin/quick-replay/releases)
(latest: [v0.1.3](https://github.com/IAmHassasin/quick-replay/releases/tag/v0.1.3)). Download
`quick-replay-<version>.apk` on the phone, allow "Install unknown apps" for the browser or file
manager, and install it. Requires Android 8.0+ (API 26).

The released APK is the **debug build** (signed with a debug key, not published on Play Store), so
Play Protect may warn before installing. Updating from a build signed with a different key requires
uninstalling the old app first; saved scripts are lost unless exported as JSON beforehand.

To publish a new version: bump `versionCode` / `versionName` in `android/app/build.gradle.kts`,
run `./gradlew testDebugUnitTest exportApk`, then
`gh release create v<version> "android/dist/quick-replay-<version>.apk" --title "Quick Replay v<version>"`
(copy the exported `Quick Replay <version>.apk` to that name first; GitHub replaces spaces in
asset names).

### Build

Requirements: JDK 17+ (21 tested), Android SDK with platform 36.

```bash
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
# APK: android/app/build/outputs/apk/debug/app-debug.apk
./gradlew exportApk
# Same debug APK copied to android/dist/"Quick Replay <versionName>.apk" (git-ignored)
```

### Use

1. Install the APK, open **Quick Replay**, tap **Enable service** and turn on *Quick Replay* in
   Accessibility settings. A floating panel appears (drag it by the `⋮⋮` handle).
2. **REC** → use your phone normally; every tap, long press and swipe is recorded and passed
   through to the app underneath → **SAVE**. Each recorded action is shown on screen (blue ripple =
   tap, orange ring with timer = long press, green arrow = swipe, with a label such as
   `#3 Tap 80 ms`) and on the panel (`REC · 3 · ✓ Tap 80 ms`). A yellow border means the last
   touch is still being forwarded; touches in that short window are not recorded.
3. **PLAY** replays the active script; **STOP** stops it; **PAUSE** / **RESUME** (shown while
   playing) holds the script and continues where it stopped, including the rest of a running pause.
   Replayed gestures are drawn the same way (Settings → *Show touches while replaying*).
   **☰** opens a menu: open the app, change the active script or combined script, rename it.
   Settings → *Show floating panel* hides the panel when idle; it still appears while a script plays.
4. In the app: **Settings** on a script sets its name, repeat count / loop forever and pause between
   loops; the **Settings** card sets the repeat count for new recordings. **Combined scripts** chain
   recordings (e.g. A ×1 → B ×3 → C ×2) into one playable, exportable script. **How to use** opens
   the in-app guide; **Playground** is a test screen; scripts import/export as JSON.

Timing: pauses and press durations are recorded in ms and replayed on a timeline from the start of
the run, so latency does not add up. Measured on the Android 15 emulator: recording
"tap, 3 s, tap, 0.5 s, tap" replays the tap-to-tap intervals within ~15 ms of the recording, and
30 taps 250 ms apart take 7 248–7 260 ms (ideal 7 250).

How recording works: Android does not tell an app where the user touches other apps, so while
recording a transparent full-screen layer receives the touch, records it, becomes "not touchable"
and forwards the same gesture with `dispatchGesture`. Gestures injected by the service carry
`TOOL_TYPE_UNKNOWN` and are ignored by the recorder.

### Automated demo (emulator or device, debug build)

```bash
ADB=adb bash android/scripts/emulator-demo.sh
```

It enables the service, imports [`examples/android-playground-demo.json`](examples/android-playground-demo.json),
replays it on the Playground screen, records a new script through the capture layer with
`adb shell input`, replays that recording and checks the Playground counters from logcat. It then
replays the combined script [`examples/android-sequence-demo.json`](examples/android-sequence-demo.json)
and records "tap, 3 s, tap, 0.5 s, tap" to compare recorded and replayed intervals.
Screenshots go to `android/build/demo/`. The debug build contains an exported broadcast
receiver used by this script (`.debug.DebugCommandReceiver`); release builds do not.

### Limitations (v1)

- Single finger only (no pinch / multi-touch).
- While a recorded gesture is being forwarded (its duration + ~120 ms) the capture layer lets
  touches through, so a touch made in that window reaches the app but is not recorded.
- Swipes replay along the recorded path with the recorded total duration; speed changes inside
  one swipe are not reproduced.
- A single gesture is limited by Android to `GestureDescription.getMaxGestureDuration()` (60 s);
  longer holds are clamped. Scripts themselves have no length limit.
- Import accepts files up to 50 MB (millions of steps) to protect memory.
- Google Play restricts Accessibility API usage; distribution is planned via GitHub/F-Droid.

## Browser

See [`browser/README.md`](browser/README.md).

## Status & handoff (2026-10-17)

| Area | State |
|---|---|
| Android app | Working MVP; unit tests 14/14, lint without errors; emulator demo (Android 15) passes: replay 6 taps / 2 long presses / 4 swipes, record → forward → replay 2/1/1, panel buttons verified |
| Browser extension | Working MVP; lint OK, unit 14/14, Playwright e2e 5/5 (headless Chromium only) |
| Script format | `quickreplay/v1` schema validated with Ajv against the examples; `quickreplay-sequence/v1` (combined scripts, Android) added 2026-10-17, its schema not yet Ajv-validated |
| Android 0.1.3 (2026-10-17) | Pause/resume, panel menu (open app, change script, rename), hide panel setting, rename in app; unit tests 32/32, emulator demo steps 1–6 pass |
| Android 2026-10-17 update | Repeat settings, recording/replay visualizer, combined scripts, timeline timing, in-app guide; unit tests 28/28, lint 0 errors, emulator demo steps 1–5 pass |
| Security review | Done; fixed playback state race, debug receiver now requires `DUMP`, navigation grace period in the browser player |
| Not done | Multi-touch, per-segment swipe timing, visual step editor, `<select>` recording, branded Chrome/Edge testing |

First-time repo setup:

1. `refs/`, `android/build/`, `android/.gradle/`, `android/local.properties`, `browser/node_modules/`
   are git-ignored. Check `git status` before the first commit.
2. Create `android/local.properties` with `sdk.dir=C\:/path/to/Android/Sdk` (Android Studio does
   this automatically).
3. Set the commit identity required by the company (`@dssolution.jp` email) before committing.

Research notes, decisions, gotchas and next steps for maintainers (and Claude Code):
[`docs/CLAUDE.md`](docs/CLAUDE.md).

## License

MIT, see [LICENSE](LICENSE). Reference projects in `refs/` (git-ignored) were only read for
design ideas; no code was copied from GPL sources.
