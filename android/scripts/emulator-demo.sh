#!/usr/bin/env bash
# End-to-end demo on a running emulator/device with the DEBUG build installed:
#   1. enable the accessibility service
#   2. import examples/android-playground-demo.json and replay it on the Playground screen
#   3. record a new script by tapping/swiping (adb input) through the capture layer, then replay it
# Evidence: logcat counters from the Playground screen and screenshots in android/build/demo/.
set -euo pipefail
export MSYS_NO_PATHCONV=1  # Git Bash on Windows: do not rewrite /sdcard paths

ADB="${ADB:-adb}"
PKG=dev.quickreplay
HERE="$(cd "$(dirname "$0")" && pwd)"
# pwd -W gives a native path on Git Bash (adb.exe cannot read /d/... paths).
ROOT="$(cd "$HERE/../.." && (pwd -W 2>/dev/null || pwd))"
OUT="$HERE/../build/demo"
mkdir -p "$OUT"

cmd() { "$ADB" shell am broadcast -n "$PKG/.debug.DebugCommandReceiver" -a "$PKG.debug.$1" "${@:2}" | sed -n 's/.*data="\(.*\)"/\1/p'; }
counts() { "$ADB" logcat -d -s QuickReplayPlayground | grep -cE " $1 #" || true; }
shot() { "$ADB" exec-out screencap -p > "$OUT/$1.png"; echo "  screenshot: build/demo/$1.png"; }
playground() {
  "$ADB" shell am force-stop "$PKG" >/dev/null 2>&1 || true
  # force-stop also stops the accessibility service; re-enable it.
  "$ADB" shell settings put secure enabled_accessibility_services "$PKG/$PKG.service.QuickReplayService"
  "$ADB" shell settings put secure accessibility_enabled 1
  sleep 2
  "$ADB" shell am start -W -n "$PKG/.ui.PlaygroundActivity" >/dev/null
  sleep 1
  "$ADB" logcat -c
}
wait_idle() {
  for _ in $(seq 1 120); do
    if "$ADB" logcat -d -s QuickReplay | grep -qE "playback (finished|stopped)"; then return 0; fi
    sleep 1
  done
  echo "  timeout waiting for playback" >&2; return 1
}

echo "[1] enable service"
"$ADB" shell settings put secure enabled_accessibility_services "$PKG/$PKG.service.QuickReplayService"
"$ADB" shell settings put secure accessibility_enabled 1

echo "[2] import + replay example script (7 steps x 2 loops)"
"$ADB" shell mkdir -p "/sdcard/Android/data/$PKG/files/import"
"$ADB" push "$ROOT/examples/android-playground-demo.json" "/sdcard/Android/data/$PKG/files/import/" >/dev/null
playground
echo "  $(cmd IMPORT --es file android-playground-demo.json)"
echo "  $(cmd PLAY)"
wait_idle
"$ADB" logcat -d -s QuickReplay | grep "playback finished" | tail -1 | sed 's/^.*QuickReplay: /  /'
echo "  playground saw: taps=$(counts tap) longPresses=$(counts longPress) swipes=$(counts swipe) (expected 6/2/4)"
shot 1-example-replayed

echo "[3] record through the capture layer, then replay"
playground
echo "  $(cmd RECORD_START)"
sleep 1
"$ADB" shell input tap 250 1350
sleep 0.8
"$ADB" shell input tap 850 1500
sleep 0.8
"$ADB" shell input swipe 540 2050 540 1400 300
sleep 0.8
"$ADB" shell input swipe 540 1750 540 1750 1000   # long press (kept clear of the floating panel)
sleep 1.5
shot 2-recording
REC_TAPS=$(counts tap); REC_LONG=$(counts longPress); REC_SWIPES=$(counts swipe)
echo "  forwarded to app while recording: taps=$REC_TAPS longPresses=$REC_LONG swipes=$REC_SWIPES (expected 2/1/1)"
echo "  $(cmd RECORD_STOP)"
"$ADB" logcat -c
echo "  $(cmd PLAY)"
wait_idle
echo "  replayed recording: taps=$(counts tap) longPresses=$(counts longPress) swipes=$(counts swipe) (expected 2/1/1)"
shot 3-recording-replayed
cmd DUMP >/dev/null
"$ADB" logcat -d -s QuickReplayDebug | tail -n 60 > "$OUT/recorded-script.log"
echo "  recorded script logged to build/demo/recorded-script.log"
