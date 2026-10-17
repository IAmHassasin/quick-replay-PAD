# Quick Replay script format — `quickreplay/v1`

A script is a single UTF-8 JSON document. Machine-readable schema:
[`spec/quickreplay.schema.json`](../spec/quickreplay.schema.json).

There is **no limit** on the number of steps or on `repeat`.
Step types are platform specific: Android scripts may only contain `tap`, `longPress`, `swipe`
and `wait`; browser scripts only `navigate`, `click`, `type`, `key`, `scroll` and `wait`.
Unknown extra fields are ignored by readers.

## Top level

| Field | Type | Required | Notes |
|---|---|---|---|
| `format` | `"quickreplay/v1"` | yes | |
| `name` | string | yes | Display name |
| `platform` | `"android"` \| `"browser"` | yes | |
| `repeat` | integer ≥ 0 | no (default 1) | `0` = loop forever until stopped |
| `loopDelayMs` | integer ≥ 0 | no (default 0) | Pause between loops |
| `screen` | `{ width, height }` | android: yes | Pixel size of the recording screen; player scales x/y to the current screen |
| `startUrl` | string | no | browser only: opened before step 1 of every loop, unless the tab is already on that URL (so page state carries across loops on the same page) |
| `createdAt` | ISO-8601 string | no | |
| `steps` | array of steps | yes | |

Every step may have `delayBeforeMs` (integer ≥ 0, default 0): wait before running the step.
Recorders fill it with the real time elapsed since the previous step.

## Android steps

| `type` | Fields | Notes |
|---|---|---|
| `tap` | `x`, `y`, `durationMs?` (default 50) | |
| `longPress` | `x`, `y`, `durationMs` | |
| `swipe` | `points: [{x, y, t}]` (≥ 2) | `t` = ms since the stroke started; same shape is used for any free-form path |
| `wait` | `ms` | |

Coordinates are pixels in the `screen` coordinate space.
A gesture longer than `GestureDescription.getMaxGestureDuration()` (60 s) is clamped.

## Browser steps

| `type` | Fields | Notes |
|---|---|---|
| `navigate` | `url` | |
| `click` | `selector?`, `x`, `y`, `button?` (`left` default), `clickCount?` | `x`/`y` are viewport CSS px used when `selector` is missing or does not resolve |
| `type` | `selector`, `text`, `sensitive?` | Replaces the field value. Recorders never store password values: they write `text: ""` with `sensitive: true`, and players skip such a step with a warning unless the user fills `text` in |
| `key` | `key` (DOM `KeyboardEvent.key`, e.g. `Enter`) | Sent to whatever element has focus at that point, i.e. it depends on the previous steps |
| `scroll` | `x`, `y` | Window scroll position |
| `wait` | `ms` | |

## Example (android)

```json
{
  "format": "quickreplay/v1",
  "name": "Tap twice then scroll",
  "platform": "android",
  "repeat": 3,
  "screen": { "width": 1080, "height": 2400 },
  "steps": [
    { "type": "tap", "x": 540, "y": 1200 },
    { "type": "tap", "x": 540, "y": 1400, "delayBeforeMs": 400 },
    { "type": "swipe", "delayBeforeMs": 500,
      "points": [ { "x": 540, "y": 1800, "t": 0 }, { "x": 540, "y": 700, "t": 300 } ] }
  ]
}
```
