# Android Calibration Flow Review — INT-03 · 2026-07-22

**Scope:** the per-route latency-calibration flow over the existing C++ chirp machinery (CORE-04's `sc_begin_calibration` / `ChirpDetector` / `SC_EVT_CALIBRATION_RESULT`, bridged in NAT-04).
**Status:** all unit suites + `assembleDebug` green (new calibration lifecycle test included).

---

## 1. State management (`SessionViewModel`)

`CalibrationState` — `Idle | Running | Success(latencyMs) | Failed` — lives inside `SyncState` (low-frequency, so it rides the existing store; the two-stream rule is untouched).

- `startCalibration()` → `engine.beginCalibration()` → `Running` (idempotent while running). The engine arms its chirp detector with t0 = current capture time; **chirp playback through the output route is `TODO(INT-03b)`** — until it exists, a run times out after the engine's 8 s window and lands in `Failed`, which is the honest outcome rather than a fake success.
- `cancelCalibration()` → engine cancel → `Idle`. `acknowledgeCalibration()` (sheet dismissed) clears terminal states so reopening starts fresh.
- `Event.CalibrationResult`: `valid` → `Success(ms)`, **persist**, and apply to the live engine immediately (`setOutputRoute(route, ms)`); `!valid` → `Failed`.

## 2. Persistence — and a wiring bug this ticket fixed

`NudgeStore` gains a third per-route field: `outputLatencyFor` / `saveOutputLatency` (`outlatency:<routeId>` beside `trim:` and `latency:`). The measured chain latency is stored per route and replayed by `onRouteChanged` on every reconnect — same lifecycle as the trim.

**The fix:** `onRouteChanged` had been feeding the *Spotify command latency* (`commandLatencyFor`, the PM-decision persistence for `sc_create`'s seek-lead prior) into `sc_set_output_route`'s prior — which the C ABI defines as *output-chain* latency. Two physically different delays, conflated. Now: calibrated output latency → `setOutputRoute`; command latency remains reserved for engine-creation seeding. The route-change unit test was updated to pin the corrected semantics, and `NudgeStore`'s doc comment spells out the distinction.

## 3. UI (`CalibrationSheet` + entry point)

- **Entry point:** a quiet `"Calibrate"` label (`BilletType.label`, `ink3`) under the nudge wheel — settings-tier actions stay whisper-quiet (§4); no icon chrome. Sheet visibility is screen-local state; the ViewModel only knows calibration state, not sheet state.
- **`CalibrationSheet`** (`ModalBottomSheet`, `billet` surface): engraved `CALIBRATE` eyebrow, the active route name as the title ("AirPods Pro" / "Phone speaker"), then one state at a time:
  - Idle — one sentence of what will happen + brass **"Start calibration"**
  - Running — *"Listening for the chirp…"* (a sentence, not a spinner) + outlined Cancel
  - Success — **"Latency measured: 182 ms"** in brass + *"Saved for this route — sync will aim ahead by it automatically."* + Done
  - Failed — *"Couldn't hear the chirp — turn the volume up and try again."* + Try again (error voice per §6.4: what happened, how to fix it, no apology)
- Dismissing in a terminal state acknowledges it (fresh sheet next open); dismissing mid-run leaves the run cancellable via the engine timeout.

## 4. Test coverage

`calibrationLifecyclePersistsMeasuredOutputLatency`: start → engine called + `Running`; valid result → `Success(182)` + persisted under `bluetooth:AirPods Pro` + applied to the live engine (`routeCalls.last() == BLUETOOTH to 182`); acknowledge → `Idle`; invalid result → `Failed`. Plus the updated `routeChangeLoadsPersistedTrimAndLatency` pinning output-latency (not command-latency) replay.

## 5. Build output

```
> Task :app:testDebugUnitTest
> Task :app:assembleDebug

BUILD SUCCESSFUL in 19s
```

## 6. Follow-ups

- **INT-03b:** actually render the CORE-04 chirp waveform through the active output route when `startCalibration()` fires (a small `AudioTrack` playback of `generate_chirp`'s rendering, or via Spotify once INT-02 lands for the full command-chain measurement).
- The INT-03 acceptance metric (calibrated BT route improves first-seek accuracy ≥ 50 ms median) needs a physical Bluetooth device — field-test pass.
