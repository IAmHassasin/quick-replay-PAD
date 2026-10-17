# Quick Replay

Open-source record & replay automation in the spirit of Click Assistant, **without limits** on
script length, duration or loop count.

| Part | What it is | Status |
|---|---|---|
| [`android/`](android) | Android app: floating panel, record your touches, replay them on any app (Accessibility API, no root) | MVP, demo on emulator |
| [`browser/`](browser) | Chrome/Edge extension (MV3): record clicks/typing/keys/scroll/navigation, replay with trusted input | MVP, Playwright e2e |
| [`spec/`](spec) + [`docs/script-format.md`](docs/script-format.md) | Shared JSON script format `quickreplay/v1` | v1 |

Design and verified technical decisions: [`docs/PLAN.md`](docs/PLAN.md).

## Android

### Build

Requirements: JDK 17+ (21 tested), Android SDK with platform 36.

```bash
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

### Use

1. Install the APK, open **Quick Replay**, tap **Enable service** and turn on *Quick Replay* in
   Accessibility settings. A floating panel appears (drag it by the `⋮⋮` handle).
2. **REC** → use your phone normally; every tap, long press and swipe is recorded and passed
   through to the app underneath → **SAVE**.
3. **PLAY** replays the active script; **STOP** stops it. In the app you can pick the active script,
   set the repeat count (`0` = forever), import/export JSON and open the **Playground** test screen.

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
`adb shell input`, replays that recording and checks the Playground counters from logcat.
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
| Script format | `quickreplay/v1` schema validated with Ajv against the examples |
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
