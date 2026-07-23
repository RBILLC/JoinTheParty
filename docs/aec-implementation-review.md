# AEC Implementation Review — CORE-05 + CORE-06 + INT-04 · 2026-07-22

**Scope:** the acoustic-echo-cancellation layer — stubbed WebRTC APM wrapper, the self-hearing guard, and route-driven AEC switching on Android.
**PM decision applied:** no real WebRTC source tree yet; the APM is a signature-mimicking stub (same delete-and-swap contract as the Spotify/ShazamKit stubs), so the *pipeline* is real and tested while the DSP is a passthrough.
**Status:** desktop core 5/5 suites (incl. the new guard test), Android unit suites + `assembleDebug` green.

---

## 1. CORE-05 — APM wrapper

- `core/third_party/webrtc_stub/webrtc_apm.{h,cpp}`: mimics the modern APM surface — `webrtc::AudioProcessing` (Create/ApplyConfig/`ProcessStream`/`ProcessReverseStream`/`set_stream_delay_ms`) and `StreamConfig` with 10 ms framing. Passthrough implementations; header documents the swap contract (vendoring the real `webrtc-audio-processing` later touches only CMake + this directory).
- `core/src/aec/aec.{h,cpp}` — `SyncCoreAec`, worker-thread-owned (AEC never runs on the audio callback; the worker consumes the ring):
  - **Capture path:** in `SC_AEC_FULL`, every drained block runs through `ProcessStream` in APM's mandatory 10 ms chunks, with sub-chunk remainders carried across calls (correct framing regardless of Oboe burst sizes; adds < 10 ms pipeline delay — irrelevant to recognition, noted as a caveat for speaker-mode *calibration* which wants ±5 ms).
  - **Reference path:** `sc_push_reference` now actually flows — copied on the control plane into a worker command, chunked into `ProcessReverseStream`. This is the synthesized-reference feed from arch §7.2.
  - Modes: OFF / PLATFORM_ONLY leave audio untouched (platform AEC lives below us); FULL engages the APM path. Mode flips clear carried alignment.

## 2. CORE-06 — Self-hearing guard (C++)

The failure it prevents: phone-speaker playback reaches the mic, recognition locks onto **our own output**, and the loop reports perfect sync forever while the room drifts away.

Logic in the session worker, on every fix (after the settle-window check):

```
if aec_mode == SC_AEC_FULL
   and last_commanded_position_ms >= 0
   and |fix.match_offset_ms − last_commanded_position_ms| <= 30 ms   (PM-confirmed window)
then reject: SC_EVT_FIX_REJECTED { SC_REJECT_SELF_HEARING }
```

- The commanded-position reference is kept fresh from **two** sources: `sc_notify_local_playback` (play started) and every `sc_notify_seek_issued` target (each seek re-commands a position).
- Headphone routes (AEC OFF) never trigger the guard — with no acoustic path from our output to the mic, a matching offset is *real* sync, and the test suite pins that behavior.
- **Documented v1 limitation:** near a true lock, the external source legitimately sits inside ±30 ms of our own position, so the guard can reject valid fixes in speaker mode. The disambiguator — "and our own output dominates capture energy" (arch §7.3) — needs real APM echo metrics and lands with the un-stubbing. Until then the deadband + drift model coast through the rejected fixes.

Test coverage (`test_synccore.cpp::test_self_hearing_guard`): in-window fix rejected with the right reason and no estimate emitted; out-of-window fix accepted; identical self-match with AEC off accepted with zero rejects.

## 3. INT-04 — Android route-based AEC toggling

- Route classification already flows `AudioRouteObserver → SessionViewModel.onRouteChanged`; that path now also sets the engine mode: **SPEAKER → `AecMode.FULL`, wired/Bluetooth → `AecMode.OFF`** (the observer stays a pure classifier; the ViewModel owns engine calls — same seam as trim/latency replay). `setAecMode` joined the `SyncEngine` interface (both test fakes extended).
- `sc_set_aec_mode` is now forwarded onto the worker (previously it only stored control-plane state), switching `SyncCoreAec` live.
- **UI hint:** when a fix is rejected as self-hearing, the session screen shows one line of quiet fine print under the meter — *"Hearing our own speaker — listening past it…"* — in `ink3`, never a warning color (this is expected behavior, not an error). The ViewModel clears the hint on the next accepted estimate.
- Unit test: route change to SPEAKER records `FULL`, then to Bluetooth records `OFF`.

## 4. Build output

Desktop core (clang/Ninja):

```
100% tests passed, 0 tests failed out of 5
```

Android (`:app:testDebugUnitTest :app:assembleDebug`):

```
BUILD SUCCESSFUL in 15s
```

## 5. Follow-ups

- Un-stub the APM (vendor `webrtc-audio-processing`, pinned) — then add the energy-dominance condition to the guard and re-run the CORE-05 attenuation AC (≥15 dB loopback) which is meaningless against a passthrough.
- Feed the real synthesized reference (cached rendering of the playing track) once playback exists end-to-end (INT-02).
- Speaker-mode calibration timing interacts with the AEC carry (<10 ms) — revisit when calibration UX (INT-03) lands.
