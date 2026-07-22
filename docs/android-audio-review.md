# Android Audio & Session Assembly Review — NAT-02 + UI-05 · 2026-07-22

**Scope:** production audio capture (Oboe, C++-only RT path) and the assembled session screen.
**Verification:** clean `assembleDebug` + `testDebugUnitTest` green on the Windows host; on-emulator smoke test — tapping "Join the party" transitions IDLE → LISTENING **with the OS mic-in-use indicator lit**, i.e. the Oboe input stream demonstrably opened and is feeding `sc_push_capture`.

---

## 1. NAT-02 — Oboe capture (`cpp/audio_capture.{h,cpp}`)

`synccore_android::OboeCapture`, owned by the JNI `BridgeHandle`, created with the session and started/stopped via new `SyncEngine.startCapture()/stopCapture()`.

- **Stream:** 48 kHz mono Float, `InputPreset::VoiceRecognition` (unprocessed-ish feed while AEC is off, per arch §5), `PerformanceMode::LowLatency`, Exclusive with Shared fallback. If the device negotiates anything other than 48 kHz mono, the stream is never started (`formatSupported() == false`) — the v1 engine accepts only 48 kHz (core ABI contract).
- **RT path is pure C++:** `onAudioReady` → `sc_push_capture`, which only memcpys into the lock-free ring. No JNI, no allocation, no locks, no logging on the audio thread. The Kotlin `pushCapture` entry point remains for tests/bring-up only.

### Monotonic clock synchronization

Every block needs the monotonic-ns timestamp of its **first frame** (SyncCore owns no clocks; capture timestamps *are* session time).

- **Primary:** `stream->getTimestamp(CLOCK_MONOTONIC)` returns a HAL correspondence pair `(framePosition, timeNs)`. Since `getFramesRead()` already includes the current callback's frames, the callback's first frame index is `framesRead − numFrames`; its distance to `framePosition` is converted to ns at 48 kHz and applied to `timeNs`. Recomputed fresh from the HAL pair every callback — nothing accumulates, so integer rounding cannot drift.
- **Fallback** (normal for the first callbacks after start, before the HAL pair is valid): `clock_gettime(CLOCK_MONOTONIC)` minus `numFrames/48000` s. All math in integer ns.

### Route-loss resilience

Route changes close input streams. `onErrorAfterClose` fires on an Oboe-internal thread that must not reopen synchronously, so a restart thread is spawned: `restart_thread_mutex_` guards the thread object, `lifecycle_mutex_` guards the stream, `stop()` joins the restarter *before* taking the stream lock (deadlock-free), and an atomic `restart_in_flight_` prevents overlap. `nativeDestroy` extends the bridge's destruction-order contract: **capture stops (stream closed, restarter joined) before `sc_destroy` begins** — no `sc_push_capture` can be in flight into a dying session.

### Route observation (`audio/AudioRouteObserver.kt`)

`AudioDeviceCallback` on `AudioManager` classifies the active output (BT A2DP/SCO/BLE → BLUETOOTH, wired/USB headset → WIRED, else SPEAKER), builds stable route ids (`"bluetooth:<product>"`, `"wired"`, `"speaker"`), debounces duplicates, and fires into `SessionViewModel.onRouteChanged` — which replays the persisted per-route trim *and* command-latency prior into the engine (PM decision).

### Build fix worth knowing

Oboe's Prefab package requires `-DANDROID_STL=c++_shared` in the **Gradle DSL's** cmake arguments — AGP selects which prebuilt oboe variant to expose *before* CMake runs, so this cannot be set from `CMakeLists.txt` (verified: forced CACHE variables don't work; the failure is "User is using a static STL but library requires a shared STL"). Documented at the edit site; the APK now ships `liboboe.so` + `libc++_shared.so` per ABI.

## 2. UI-05 — Session screen (`ui/session/SessionScreen.kt`)

Stateless composable: `SessionScreen(state, meterFrames, onJoinTap, onTrimChange, onTrimCommit)` — the Activity owns platform concerns (mic permission via `ActivityResultContracts`, route observer lifecycle) and passes projections; the ViewModel owns all session logic.

- **Phase rendering** through a single 200 ms `Crossfade` keyed on a *coarse phase group*, so LOCKED↔DRIFTING flapping never retriggers the fade (only the meter reacts): IDLE = nothing but the centered brass "Join the party" pill (the invitation is the screen); LISTENING/MATCHING = one quiet line of text, no spinners; AIMING/CONVERGING/LOCKED/DRIFTING = track identity + SyncMeter + NudgeWheel (fed `state.nudgeMs`/`state.routeName`); LOST = "Lost the room — listening again…"; NEEDS_SPOTIFY/NEEDS_PREMIUM/ERROR = quiet placeholders pending the UI-06 concierge screens.
- **One-warm-accent rule (§4):** the brass button exists only in IDLE, where no meter exists; in session, the only brass on screen is whatever the meter earns at lock.
- **Capture wiring:** `startListening()` now starts the native capture first and only then transitions — LISTENING with a dead mic would be a lie; `reset()` stops capture. Mic-permission denial leaves the app in IDLE with the Join button as the retry point.

## 3. Build output

`:app:testDebugUnitTest :app:assembleDebug` after full assembly (8/8 unit tests):

```
BUILD SUCCESSFUL in 15s
```

Clean-rebuild verification during NAT-02 (all three ABIs linking Oboe):

```
BUILD SUCCESSFUL in 20s
41 actionable tasks: 41 executed
```

## 4. Follow-ups

- `FakeSyncEngine` gained `startCapture/stopCapture` (test-compile requirement); a dedicated capture-lifecycle unit test would be worth adding with NAT-06.
- Emulator smoke covers stream-open only; input *latency* numbers and device-matrix behavior (exclusive-mode denial, 44.1 kHz-only devices) belong to the INT-02/field-test pass.
- The remaining Android critical path: NAT-06 (ShazamKit AAR — still gated on RES-01), NAT-08/AUTH-02 (Spotify), then INT-02.
