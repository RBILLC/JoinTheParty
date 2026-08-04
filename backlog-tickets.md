# JoinTheParty — Development Backlog

**Phase:** `/to-tickets`
**Sources:** `architecture-spec.md` · `technical-requirements.md` · `ui-ux-design-system.md`
**Date:** 2026-07-21

> **Tracking moved to GitHub Issues (2026-08-04):** all open work now lives at
> <https://github.com/RBILLC/JoinTheParty/issues> — one issue per not-started ticket and per
> partial ticket's remaining work. This file stays as the historical record of ticket
> definitions and completed work; statuses are no longer updated here.
>
> Mapping: RES-02 #1 · SCAF-02 #2 · SCAF-04 #3 · CORE-05 #4 · CORE-07 #5 · NAT-01 #6 ·
> NAT-02 #7 · NAT-03 #8 · NAT-05 #9 · NAT-06 #10 · NAT-07 #11 · NAT-08 #12 · AUTH-01 #13 ·
> AUTH-02 #14 · AUTH-03 #15 · AUTH-04 #16 · AUTH-05 #17 · UI-01 #18 · UI-03 #19 · UI-04 #20 ·
> UI-05 #21 · UI-06 #22 · INT-01 #23 · INT-02 #24 · INT-03 #25 · INT-05 #26 · CFX-03 #27 ·
> CTL-01 #28 · CTL-02 #29 · CTL-03 #30 · INT-06 #31

## Status (updated 2026-07-21)

| Ticket | Status | Commit |
|---|---|---|
| SCAF-01 | ✅ Done | `4e59105` |
| CORE-01 | ✅ Done | `4e59105` |
| CORE-02 | ✅ Done | `a1b79fe` |
| CORE-03 | ✅ Done (+ online command-latency learning, added after closed-loop sim findings) | `9d54de9` |
| CORE-04 | ✅ Done | `5c15379` |
| SCAF-04 | 🟡 Partial (tokens + codegen; shell wiring blocked on SCAF-02) | `702eea3` |
| SCAF-03 | ✅ Done (NDK 28.2 pinned — r27 unavailable; see docs/android-implementation-review.md) | — |
| NAT-04 | ✅ Done — instrumentation suite 4/4 on Pixel_10_Pro AVD (2026-07-22) | — |
| CORE (extra) | ✅ `sc_get_command_latency_ms` — persisted learned latency (PM decision) | `9f9a84f` |
| UI-01 | 🟡 Done except Instrument Sans font file (styles structured, platform sans placeholder) | `acf8bc6` |
| UI-03 | 🟡 Done — implemented + emulator-verified in 3 states; pending: Layout Inspector recomposition-count evidence, TalkBack pass | `acf8bc6` |
| UI-04 | 🟡 Done — drum/detents/haptics/debounce, emulator drag-verified; deferred: numeric entry, A/B button, reset confirm | — |
| UI-02 | ✅ Done — SessionViewModel + allowlisted state machine, SyncEngine seam, per-route DataStore persistence, 8/8 JVM tests | — |
| NAT-02 | 🟡 Done — Oboe C++ RT capture + HAL timestamping + route observer, emulator-verified (mic indicator); pending: 30-min drop test + reference-device matrix per AC | — |
| UI-05 | 🟡 Done — SessionScreen assembled, IDLE→LISTENING emulator-verified; gates are placeholders pending UI-06 | — |
| AUTH-02 | 🟡 Done — PKCE + EncryptedSharedPreferences + rotation handling, RFC known-answer tested; pending: real client id, live auth run | — |
| AUTH-05 | 🟡 Done — detector + gate wiring + recognition-only fallback; Premium probe lands with real App Remote | — |
| NAT-08 | 🟡 Done against signature-faithful stubs (real AAR pending); seek echoes notifySeekIssued (unit-tested) | — |
| RES-01 | ✅ Passed (PM decision 2026-07-22) — ShazamKit for Android is a go | — |
| NAT-06 | 🟡 Done against ShazamKit stubs — predictedCurrentMatchOffset/skew mapping, RequestFix-driven cadence + bootstrap; real AAR + audio feed pending | — |
| AUTH-03/04 | 🟡 Client seams done (mocked responses behind production-shaped HttpBackendClient); server deployment pending | — |
| UI-06 | 🟡 Done — onboarding + concierge gates emulator-verified; pending: once-per-session gate memory, Spotify brand attribution | — |
| CORE-05 | 🟡 Pipeline done against stub APM (PM decision); un-stub + ≥15dB attenuation AC pending real webrtc-audio-processing | `b344da8` |
| CORE-06 | ✅ Done — ±30ms guard (PM-confirmed), seek-refreshed reference, headphone bypass, C-API tested; energy condition deferred to real APM | `b344da8` |
| INT-04 | 🟡 Route→AEC wiring + UI hint done (unit-tested); 10/10 speaker-mode field trials AC needs real APM + device | `b344da8` |
| INT-06 | 🟡 06a/06b/06c implemented; **field test 7 passed** FGS start, notification text, Stop action, screen-off + adb-loss survival. Pending: 10-min soak (only ~104 s of music), task-swipe. See docs/field-test-7-int06.md | `729052a` `c29c517` `2f113a9` `1161065` |
| CAL-01 | ✅ **Field-verified 2026-07-28** — cold single press measures 153 ms MEASURED on the phone speaker (deep-buffer range; the fast path would read ~40 ms). Needed two more fixes found on device: the MODE_STREAM start threshold silenced the chirp entirely, and the detector armed on a stale session clock | `5cea89f` `99216e1` `35cef47` |
| CAL-02 | ✅ Done — shared `dsp/fft` helper, `dsp/lag_window` ported, `lag_analyzer` consumes it (selftest green), new DSP tests | `77144c2` |
| CAL-03 | ✅ Done — `sc_sample_latency_residual` + `SC_EVT_LATENCY_RESIDUAL`, autocorrelation over the 12 s history, converged-gated, AEC restored on every path, no write to control state | `20ff81e` |
| CAL-04 | ✅ Done — `CalibrationProfile` JSON record per route (atomic, corrupt reads as absent), shell-side ≥3-window referee aggregation, drift flag | `0bd89c3` |
| CAL-05 | ✅ Done — `sc_get_input_level` + JNI + `SyncEngine.inputLevel()`; idle release measures elapsed time (the assumed 2 ms poll period decayed ~7x too slowly under Windows timer granularity) | `77144c2` |
| CAL-06 | ✅ Done — phase-word opacity tracks the mic level in LISTENING/MATCHING; draw-phase read so the screen root never recomposes; closes ux-audit gap #8 | `9d2cd48` |
| CAL-07 | ✅ Done — tone-match (adjust-until-aligned, percussive click, deep-buffer transport) + the shared `CaliperScale` | `a70a2e5` |
| CAL-08 | ✅ Done — device shelf + detail, provenance never rendered alike, one-warm-accent held structurally | `a2b864d` |
| CAL-09 | ✅ Done — gate raised on an unknown route and it genuinely holds the aim (not recognition); decline writes ESTIMATED | `d4bbc4b` `3b4338b` |
| CAL-10 | ✅ Done — 3 commits within 25 ms of median, above a 30 ms floor; always asks, folds in as BY_EAR and zeroes the wheel; 7-day decline via stored timestamp | `d4bbc4b` |
| CFX-01 | ✅ Done — route snapshotted at measurement start; a route change cancels in-flight and the result is discarded, never relabelled | `2e610bd` |
| CFX-02 | ✅ Done — recalibrate disabled with a reason when the viewed device isn't connected; empty-state action relabelled honestly | `2e610bd` |
| CFX-03 | 🟡 Done — ReadOut/Input semantics (stateDescription + progressBarRangeInfo/setProgress a11y path), `· Connected` text tell shelf+detail; pending: TalkBack/device pass (no instrumentation tests in repo) | — |
| CFX-04 | ✅ Done — sheet requires ACTIVE; gate/sheet mutual exclusion is structural, gate wins | `e82a518` |
| CFX-05 | ✅ Done — single quiet "Devices" entry on IDLE | `e82a518` |
| CFX-06 | ✅ Done — one route-neutral gate variant; Failed copy no longer assumes a speaker | `e82a518` |
| CFX-07 | ✅ Done — `beginCalibration()==false` lands in Failed instead of a dead tap | `e82a518` |
| CFX-08 | ✅ Done — drift "Later" dismisses in place, matching "Keep as is" | `e82a518` |
| CFX-09 | ✅ Done — store sorts by `updatedAtMs`, shell promotes the connected device | `e82a518` |
| CTL-01 | 🟡 Done — CTL-01a/01b: agreement-starvation sentinel + Wittenmark turn-off trigger + `SC_EVT_ACTIVE_PROBE`/echo/verdict in core, pause-resume-echo in the shell; 8/8 ctest + 129/129 JVM + assembleDebug; pending: the device pass (audible probe + forced self-match on the field rig, per CTL-01b's AC) | `7d0cc28` |
| CTL-02 | ✅ Done — CTL-02a/02b: persistence gate + residual ring in `CorrectionPolicy` per tech-req §2.7; closed-loop sims reproduce FT8's Vienna (one seek, −50 ms landing) and hold the deadband-150 lesson (0 seeks under scatter); five-cycle field re-verification pending | `5f03d08` |
| CTL-03 | ✅ Done — CTL-03a/03b: `comb_ratio` in `analyze_window`+CLI (graded path byte-identical), large-correction hold in `CorrectionPolicy`; phantom sim 0 large seeks / genuine-jump sim exactly 1; field re-verification pending | `9237e3a` |
| DSP-01a | ✅ Done — `OnsetStrengthRing` (`core/src/dsp/oss_ring.h/.cpp`) per §2.10: incremental OSS + on-demand tempogram, provisional constants named, zero-alloc guard; 8 tests incl. the orchestrator-added frozen-ring stability pin; 9/9 ctest suites | `cd1099f` |
| DSP-01b | ✅ Done — worker wiring (drain tap + residual-cadence estimate + kTrackLost epoch reset), `beat_comb_corroborated` (k=1..4) + `sc_test_get_beat_state` hook, `lag_analyzer --tempo`; orchestrator rewrote the flaky comb-wiring test onto coherent beat-aligned copies; 9/9 ctest ×3 | `2f63485` |
| DSP-02a | ✅ Done — `whiten_beta = 0.5` trailing param (legacy branch verbatim, byte-identity + anti-unification pins), `lag_analyzer --beta` in both modes with `(0,1]` guard, combined column order `...,beta,beat_period_ms`; on-device behavior unchanged; 9/9 ctest | `0306c0d` |
| DSP-02b | ✅ Done — sweep β∈{0.5–0.8} over a **substitute corpus** (4 clean WAVs + 2 synthetic room fixtures; §2.11's field corpus/FT8 captures not on disk — report §0 states this); Criterion 1 PASS narrowly (0 flips/regressions on 109 healthy-lock windows, but clean-audio flips climb with β incl. a wild 267.9 ms peak at β≥0.7), Criterion 2 PASS via peak_ratio margin; recommends a future spec amendment consider β=0.7 contingent on real-field-corpus re-validation; docs-only diff, no default flipped | `30f54d9` |
| DSP-03a | ✅ Done — `SC_EVT_ACTIVE_DUCK`/`sc_notify_duck_executed` ABI + deferred worker matched-filter dip detector + duck tier behind `duck_tier_first` (default false = shipped pause-first, zero existing-test edits; promotion is a future post-field-pass flip per §2.12); orchestrator fixed a false-clear on the insufficient-history path + added the duck-expiry pin; 16 new tests, 9/9 ctest, sims byte-identical | `1ec8b56` |
| DSP-03b | ✅ Done — JNI `SC_EVT_ACTIVE_DUCK`→`Event.ActiveDuck` + `notifyDuckExecuted` echo; `SessionViewModel.onActiveDuck` (probe-mirroring gates + muted/null-controller, NonCancellable volume restore, echo skipped on cancel) over a new `StreamVolumeController` seam (API 28+ gated); 7 JVM tests (136 total), assembleDebug green; duck-first promotion still gated on the CTL-01 device pass | `d54aeba` |
| Everything else | ⬜ Not started | — |

**PM decisions logged 2026-07-21:** deadband stays 25 ms globally · learned command latency persists across sessions (ABI getter added) · self-hearing guard window confirmed ±30 ms. **Pivot:** MVP critical path moves to Android (INT-02 chain); SCAF-02/iOS deferred until a Mac is available.
**MVP definition:** one device (iOS first — no token vendor needed for ShazamKit) recognizes a live speaker, plays the same track via Spotify, converges to lock, meter + wheel functional. Android reaches parity in the same epics via its own tickets.

---

## Epic 0 — Risk & Research (run first; cheap, de-risks everything)

### RES-01 · Confirm ShazamKit Android commercial terms & quotas
**Description:** Verify Apple's ShazamKit Android AAR redistribution/commercial terms, request quotas, and token TTL rules (tech-req §3.2, arch §13.3). Outcome is a written go/no-go; fallback decision is ACRCloud.
**Acceptance criteria:**
- Written summary of license terms, quota limits, and token constraints committed to `docs/`.
- Go/no-go decision recorded; if no-go, ACRCloud selected and NAT-06 re-scoped.
**Dependencies:** none. **Blocks:** NAT-06, AUTH-04.

### RES-02 · App Remote seek latency & jitter benchmark
**Description:** Build `tools/latency-bench`: scripted measurement of Spotify App Remote command→audible latency and seek settle-time distribution on 2 iOS + 2 Android reference devices (arch §13.2). Results set the estimator's priors and settle window.
**Acceptance criteria:**
- Bench rig runs a scripted seek sequence and logs measured latencies to CSV.
- Distribution report (median/p90) per device committed to `docs/`; default `command_latency_prior_ms` and settle-window values chosen from data.
**Dependencies:** none (uses throwaway script + any Spotify account, not the app).

---

## Epic 1 — Scaffold & Tokens

### SCAF-01 · Monorepo scaffold + core build system
**Description:** Create the repo layout from arch §12 (`core/`, `ios/`, `android/`, `backend/`, `tools/`, `docs/`). Root `core/CMakeLists.txt` (CMake ≥ 3.28) building an empty `synccore` static lib + desktop test target. CI pipeline runs the desktop tests on push.
**Acceptance criteria:**
- `cmake --build` produces `libsynccore` for host + a runnable (empty) test binary.
- CI green on a trivial test; folder layout matches arch §12.
- Version-pin policy files in place (Gradle version catalog stub, vendored `third_party/` README).
**Dependencies:** none.

### SCAF-02 · iOS app scaffold + bridge target
**Description:** Xcode project (iOS 17 floor, Swift 5.10+), SwiftUI entry point, `Bridge` module with C interop compiling `synccore` via CMake, mic permission plumbing (`NSMicrophoneUsageDescription`), `LSApplicationQueriesSchemes` for Spotify.
**Acceptance criteria:**
- App builds and runs on device; calls a stub `sc_*` symbol from Swift and logs its return.
- Debug/Release configs both link SyncCore; CI builds the app.
**Dependencies:** SCAF-01.

### SCAF-03 · Android app scaffold + NDK wiring
**Description:** Gradle project (Kotlin 2.x, Compose BOM, minSdk 24, NDK r27 pinned), `cpp/` JNI target consuming `core/CMakeLists.txt`, mic permission plumbing, `<queries>` entry for `com.spotify.music`.
**Acceptance criteria:**
- App builds and runs on device; JNI calls a stub `sc_*` symbol and logs its return.
- `externalNativeBuild` uses the same core CMake file as desktop; CI builds the app.
**Dependencies:** SCAF-01.

### SCAF-04 · `tokens.json` + DesignTokens codegen
**Description:** Author `tokens.json` from `ui-ux-design-system.md` (Billet palette §2, type ramp §3, spacing/radius §4, spring + haptic constants §5). Codegen script emits `DesignTokens.swift` (enum) and `DesignTokens.kt` (object); wire into both app builds. Bundle Instrument Sans variable font.
**Acceptance criteria:**
- Both shells render a token-audit debug screen (all colors, all text styles) using only generated tokens.
- Changing a hex in `tokens.json` + regen changes both apps; no color/TextStyle literals in shell UI code (lint rule or grep check in CI).
- Instrument Sans renders with `tnum` verified on a numeral sample.
**Dependencies:** SCAF-02, SCAF-03.

---

## Epic 2 — SyncCore DSP

### CORE-01 · C ABI: session lifecycle, ring buffer, event pump
**Description:** Implement `synccore.h` exactly as specced (tech-req §1.2): `sc_create/destroy`, config validation (48 kHz mono only), RT-safe SPSC ring buffer behind `sc_push_capture`, worker thread + event callback registration, all input setters storing state. No DSP yet — events can be synthetic.
**Acceptance criteria:**
- Header compiles as C99 and C++17; ABI doc comment per function.
- TSAN/ASAN-clean tests: create/destroy cycles, push from a dedicated "audio" thread while control calls race, callback ordering.
- `sc_push_capture` verified allocation-free (instrumented allocator test).
**Dependencies:** SCAF-01.

### CORE-02 · Timing model + Kalman estimator
**Description:** Implement the §6.1 timing model and 2-state Kalman filter (offset error, drift ppm) consuming `sc_recognition_fix_t` + `sc_player_state_t`; emit `SC_EVT_SYNC_ESTIMATE` at ≤15 Hz with drift-model interpolation between fixes.
**Acceptance criteria:**
- Unit tests with synthetic fix sequences: converges within 3 fixes on clean data; tracks injected 50 ppm drift within ±5 ppm after 60 s simulated time; confidence drops on stale fixes.
- Estimate stream cadence verified ≤15 Hz; `converged` flag = 3 consecutive in-deadband fixes.
**Dependencies:** CORE-01.

### CORE-03 · Correction policy engine
**Description:** Implement §6.2 policy: 25 ms deadband, micro-seek emission (`SC_EVT_CORRECTION`), 3 s settle suppression after `sc_notify_seek_issued`, ≥2 s error → `SC_EVT_TRACK_LOST`, adaptive `SC_EVT_REQUEST_FIX` cadence (8–12 s, stretched after convergence), skew-driven pre-emptive micro-seeks (§6.3).
**Acceptance criteria:**
- Simulation tests: no corrections inside deadband; sawtooth stays within ±25 ms under 0.05% skew; no measurement accepted during settle window; lost-track fires at 2 s error.
- Cadence test: request-fix interval stretches after `converged`, tightens on drift.
**Dependencies:** CORE-02.

### CORE-04 · GCC-PHAT correlator + chirp calibration
**Description:** Vendor KissFFT (pinned tag). Implement GCC-PHAT cross-correlation and the chirp generate/detect calibration path (§6.4): `sc_begin_calibration` → emits chirp spec → correlates mic capture → `SC_EVT_CALIBRATION_RESULT` with measured chain latency.
**Acceptance criteria:**
- Correlator finds a known offset in fixture audio within ±2 ms at 20 dB SNR, ±5 ms at 6 dB.
- Calibration round-trip test (synthetic loopback with injected 180 ms delay) reports 180 ± 5 ms.
**Dependencies:** CORE-01.

### CORE-05 · WebRTC APM (AEC3) vendored build + wrapper
**Description:** Vendor a pinned `webrtc-audio-processing` extraction into `core/third_party`; CMake build for host/iOS/Android; thin C++ wrapper (init, process capture frame, inject reference frame) behind SyncCore's AEC module.
**Acceptance criteria:**
- Builds on all three targets from the single CMake tree; license file retained.
- Loopback test: known reference mixed into capture at 0 dB is attenuated ≥ 15 dB post-AEC.
**Dependencies:** CORE-01.

### CORE-06 · Reference synthesis + self-hearing guard
**Description:** Implement `sc_push_reference` time-alignment into AEC3 (§7.2) and the recognition-side self-hearing guard (§7.3): reject fixes matching own commanded position ±30 ms while own-output energy dominates; emit `SC_EVT_FIX_REJECTED`.
**Acceptance criteria:**
- Guard test: fix equal to commanded position during speaker-mode playback → rejected with reason; genuine external fix (offset > 30 ms) → accepted.
- AEC mode transitions (`SC_AEC_OFF/PLATFORM_ONLY/FULL`) switch processing without capture dropouts.
**Dependencies:** CORE-03, CORE-05.

### CORE-07 · Fixture regression suite + CI gate
**Description:** Record/collect real-world fixtures (`tools/fixtures`): beach/party captures at varied SNR, speaker-mode self-hearing captures, drifting-clock captures. Desktop suite replays fixtures through the full core and asserts convergence metrics. Becomes the mandatory gate for any `third_party` upgrade (tech-req §4 version policy).
**Acceptance criteria:**
- ≥ 10 fixtures spanning SNR 6–30 dB committed (or LFS-stored) with ground-truth offsets.
- CI job replays suite; regression thresholds (convergence time, final error) enforced.
**Dependencies:** CORE-03, CORE-04, CORE-06.

---

## Epic 3 — Native Audio & Bridges

### NAT-01 · iOS audio capture
**Description:** AVAudioEngine input tap → 48 kHz mono float, monotonic timestamps at the callback boundary, `AVAudioSession` config (measurement/voiceProcessing modes per AEC state), route-change observation → route model (speaker/wired/BT + name), reported input/output latency surfacing.
**Acceptance criteria:**
- Capture runs 30 min without drops (instrumented counter); buffers carry monotonic ns timestamps.
- Route changes (plug/unplug BT) publish updated route + latency within 1 s.
**Dependencies:** SCAF-02.

### NAT-02 · Android audio capture
**Description:** Oboe (AAudio) input stream, low-latency/exclusive with shared fallback, `VOICE_RECOGNITION` vs `VOICE_COMMUNICATION` preset switch per AEC mode, `AudioTimestamp`-derived monotonic timestamps, route observation via `AudioDeviceCallback`.
**Acceptance criteria:** same as NAT-01, on two reference devices (one flagship, one budget).
**Dependencies:** SCAF-03.

### NAT-03 · Swift ↔ SyncCore bridge
**Description:** Bridge module: capture thread → `sc_push_capture` direct; event callback → `AsyncStream<SCEvent>` (buffered `.bufferingNewest(8)`) with main-actor delivery; typed Swift mirrors of all `sc_*` structs; separate MeterFrame stream (tech-req §2.2).
**Acceptance criteria:**
- Round-trip test on device: pushed synthetic audio yields events on the main actor with correct payloads.
- Zero allocations on the capture path (Instruments verified); callback→UI marshaling < 5 ms p95.
**Dependencies:** CORE-01, SCAF-02, NAT-01.

### NAT-04 · JNI ↔ SyncCore bridge
**Description:** JNI layer mirroring NAT-03: capture thread pushes directly from Oboe callback; events → `callbackFlow` on `Dispatchers.Default`; conflated MeterFrame flow; typed Kotlin data classes for all payloads.
**Acceptance criteria:** same round-trip + allocation criteria as NAT-03 (Perfetto/heap-track verified on the audio path).
**Dependencies:** CORE-01, SCAF-03, NAT-02.

### NAT-05 · RecognitionProvider — ShazamKit iOS
**Description:** `RecognitionProvider` protocol + ShazamKit implementation: session per sync-session, driven only by `SC_EVT_REQUEST_FIX`; maps `SHMatchedMediaItem` (`predictedCurrentMatchOffset`, `frequencySkew`, ISRC) → `sc_recognition_fix_t`; surfaces no-match/rate states.
**Acceptance criteria:**
- Live test against a real speaker: fix delivered with offset accurate within ±150 ms of ground truth (validated vs. a stopwatch recording).
- No recognition requests occur outside `SC_EVT_REQUEST_FIX` triggers (log-audited).
**Dependencies:** SCAF-02, NAT-01, NAT-03.

### NAT-06 · RecognitionProvider — ShazamKit Android
**Description:** Same contract as NAT-05 via the vendored ShazamKit AAR; developer-token acquisition from backend (AUTH-04), refresh on `InvalidToken`; ACRCloud fallback stub behind the provider interface.
**Acceptance criteria:** same live-test criteria as NAT-05; token refresh path exercised by forcing an expired token.
**Dependencies:** SCAF-03, NAT-02, NAT-04, RES-01, AUTH-04.

### NAT-07 · SpotifyController — iOS App Remote
**Description:** App Remote connect lifecycle (`authorizeAndPlayURI` wake, reconnect on foreground), `play(uri)`, `seekTo(ms)` with `sc_notify_seek_issued` + `sc_notify_local_playback` echoes, player-state subscription → `sc_submit_player_state`, error taxonomy → `needsSpotify`/`needsPremium` signals.
**Acceptance criteria:**
- On device: play + seek round trip works; every seek is echoed to SyncCore (assert via core log).
- Kill-Spotify / no-Premium-account scenarios produce the correct typed failures.
**Dependencies:** SCAF-02, AUTH-01.

### NAT-08 · SpotifyController — Android App Remote
**Description:** Android mirror of NAT-07 via `SpotifyAppRemote.connect` (App Remote + Auth AARs pinned together, tech-req §4).
**Acceptance criteria:** same as NAT-07 on Android reference devices.
**Dependencies:** SCAF-03, AUTH-02.

---

## Epic 4 — Auth & APIs

### AUTH-01 · Spotify PKCE flow — iOS
**Description:** Authorization Code + PKCE per tech-req §3.1: CSPRNG verifier, S256 challenge, `ASWebAuthenticationSession`, token exchange, Keychain storage (`AfterFirstUnlockThisDeviceOnly`), proactive refresh with refresh-token rotation overwrite.
**Acceptance criteria:**
- Full auth → token → refresh cycle on device; rotated refresh token verified persisted.
- Tokens never logged; scope set exactly `app-remote-control user-read-playback-state user-modify-playback-state`.
**Dependencies:** SCAF-02.

### AUTH-02 · Spotify PKCE flow — Android
**Description:** Android mirror: Custom Tabs, Keystore-backed `EncryptedSharedPreferences`, same refresh semantics.
**Acceptance criteria:** same as AUTH-01.
**Dependencies:** SCAF-03.

### AUTH-03 · Backend: ISRC → Spotify URI map service
**Description:** Backend skeleton + `GET /v1/track-map?isrc=`: server-side client-credentials token against Spotify Web API `search?q=isrc:`, 30-day cache, rate limiting; deployed to a staging environment. Client `TrackMapClient` in both shells with title+artist fallback flagging "loose sync" (arch §3).
**Acceptance criteria:**
- Endpoint returns URI for 20 known ISRCs; cache hit path verified; secret exists only server-side.
- Shell clients resolve ISRC→URI and propagate the loose-sync flag.
**Dependencies:** SCAF-01 (repo), SCAF-02/03 for clients.

### AUTH-04 · Backend: ShazamKit developer-token vendor
**Description:** `POST /v1/tokens/shazam`: ES256 JWT minting (kid/iss from Apple key, 24 h TTL), gated by Play Integrity (Android caller); Android client caching + expiry−1 h refresh (tech-req §3.2).
**Acceptance criteria:**
- Minted token accepted by ShazamKit Android on device; signing key demonstrated absent from the app bundle.
- Play Integrity rejection path returns typed error; client backs off.
**Dependencies:** AUTH-03 (backend skeleton), RES-01.

### AUTH-05 · Session preconditions: installed / Premium detection
**Description:** Session-start checks: Spotify installed (canOpenURL / package query), Premium capability probe (seek rejection mapping), producing `needsSpotify` / `needsPremium` phase transitions per tech-req §2.4.
**Acceptance criteria:**
- Each of the 4 states (installed±, premium±) verified on device produces the correct phase.
- Checks complete < 1 s and never block the listening path when Spotify is healthy.
**Dependencies:** NAT-07, NAT-08.

---

## Epic 5 — "Billet" UI & Integration

### UI-01 · Theme foundation
**Description:** Wire generated DesignTokens into SwiftUI/Compose theme layers: text styles, shapes (24 squircle), machined depth modifiers (top inner highlight + shadow, recessed-well inverse), motion tokens (`settle`, `heavy`, `heat`), haptic vocabulary wrappers, Reduced Motion switch (ui §4–5).
**Acceptance criteria:**
- Component gallery screen shows buttons (4 variants), surfaces, wells, type ramp on both platforms, pixel-reviewed against the design doc.
- Reduced Motion flips springs to crossfades globally (demonstrated in gallery).
**Dependencies:** SCAF-04.

### UI-02 · Session stores + phase state machine
**Description:** `SessionStore` (@Observable, main-actor) and `SessionViewModel` (StateFlow) implementing the §2.4 phase machine, consuming bridge event streams; separate conflated MeterFrame streams; per-route nudge persistence (UserDefaults / Proto DataStore).
**Acceptance criteria:**
- State-machine unit tests cover every legal transition + lost-track auto-restart (max 3 → error).
- Two-stream rule enforced: meter stream demonstrably bypasses the store (test doubles).
**Dependencies:** NAT-03, NAT-04.

### UI-03 · Sync Meter — "phase horizon"
**Description:** Recessed meter well per ui §6.1: reference + local lines, log-mapped offset, heat-scale color from confidence×convergence, fusion at lock with `brassBright` + lock-thunk haptic, `heroMs` readout, engraved ± scale, accessibility mirroring.
**Acceptance criteria:**
- Driven by scripted MeterFrame sequences: drift, converge, fuse, split all render per spec at ≤15 Hz.
- **Zero recompositions/re-renders of the session-screen root during meter animation** (Layout Inspector / Instruments evidence attached, tech-req §2.3).
- VoiceOver/TalkBack announce state ("12 milliseconds behind, converging").
**Dependencies:** UI-01, UI-02.

### UI-04 · Nudge Wheel — "trim dial"
**Description:** Knurled drum edge per ui §6.2: 1:1 drag with 5 ms detents (9pt travel), detent haptics, inertial flick with heavy friction (off under Reduced Motion), ±750 ms rubber-band stops, long-press numeric entry, double-tap zero (confirm > 100 ms), 400 ms debounced commit → `sc_set_user_nudge_ms`, per-route persistence display, A/B hold-to-mute button.
**Acceptance criteria:**
- Detent count matches ms delta exactly across a full-range drag (automated UI test).
- Commit produces exactly one micro-seek per settled adjustment (core log audit); value restored on route reconnect.
- A/B mutes local playback while held, restores on release, rigid-click haptics both ways.
**Dependencies:** UI-01, UI-02.

### UI-05 · Session screen assembly
**Description:** Compose the four-element session screen (track identity, meter, readout, wheel) with phase-driven states (listening/matching/aiming visuals), `heat` transitions, and the single quiet settings entry point (ui §4).
**Acceptance criteria:**
- All phases from §2.4 render distinct, reviewed states; at most one warm accent element at any time (design review sign-off).
- Cold start → listening in < 2 s on reference devices.
**Dependencies:** UI-03, UI-04.

### UI-06 · Onboarding + Premium/installed gates
**Description:** Three-sentence onboarding with phase-horizon motif; `needsPremium` concierge screen (copy per ui §6.4, Spotify attribution per their guidelines, recognition-only degradation mode); `needsSpotify` mirror; once-per-session gate repetition cap.
**Acceptance criteria:**
- Both gates reachable via forced states; deep links open Spotify targets; "Keep identifying songs" mode shows live track + position without playback.
- Copy matches design doc verbatim; no gate re-prompts within a session after dismissal.
**Dependencies:** UI-01, AUTH-05.

### INT-01 · End-to-end sync session — iOS (MVP milestone)
**Description:** Wire the full loop on iOS: capture → ShazamKit fix → ISRC map → App Remote play/seek → estimator → corrections → locked, with meter + wheel live against a real external speaker.
**Acceptance criteria:**
- Against a reference speaker: lock (converged, |error| < 25 ms) within 30 s of app open, sustained 5 min including ≥ 1 automatic micro-seek.
- Wheel trim of −180 ms audibly re-aims playback and re-locks.
- Demo video + measured error log attached.
**Dependencies:** CORE-03, NAT-01, NAT-03, NAT-05, NAT-07, AUTH-01, AUTH-03, UI-05.

### INT-02 · End-to-end sync session — Android
**Description:** Android mirror of INT-01.
**Acceptance criteria:** same as INT-01 on both reference devices.
**Dependencies:** CORE-03, NAT-02, NAT-04, NAT-06, NAT-08, AUTH-02, AUTH-04, UI-05.

### INT-03 · Calibration flow
**Description:** Per-route chirp calibration UX (quiet-moment flow), invoking CORE-04, storing per-route latency priors, feeding `sc_config_t` / `sc_set_output_route` on session start (arch §6.4).
**Acceptance criteria:**
- Calibrating a BT route measurably improves first-seek accuracy vs. uncalibrated (bench evidence, ≥ 50 ms median improvement on a high-latency codec).
- Stored priors auto-applied on route reconnect.
**Dependencies:** CORE-04, UI-05, INT-01.

### INT-04 · Speaker-mode AEC integration
**Description:** Route-driven AEC activation (platform AEC + AEC3 + self-hearing guard) when output route = phone speaker; reference synthesis feed wiring (arch §7).
**Acceptance criteria:**
- Speaker-mode session against an external speaker locks to the *external* source (never self-locks) in 10/10 trials.
- `SC_EVT_FIX_REJECTED` observed doing its job in at least one trial; headphone routes verified AEC-bypassed.
**Dependencies:** CORE-06, INT-01 (iOS), INT-02 (Android).

### INT-05 · Field test & fixture harvest
**Description:** Structured field sessions (beach/outdoor, noisy indoor, low SNR): measure lock rate, time-to-lock, drift behavior; harvest new fixtures into CORE-07's suite; file defects.
**Acceptance criteria:**
- ≥ 3 environments × both platforms tested with metrics recorded; new fixtures added; defect list triaged into backlog.
**Dependencies:** INT-01, INT-02, INT-04, CORE-07.

### INT-06a · SessionGraph — process-scoped session ownership
**Description:** Move the object graph `SessionViewModel.Companion.Factory` builds (SyncCore, ACRCloudProvider/EnginePcmWindowSource, HttpBackendClient, AudioTrackChirpPlayer, AppRemoteSpotifyController, NudgeStore) plus `AudioRouteObserver` into a process-scoped `SessionGraph` (`session/SessionGraph.kt`) anchored in a new `JoinThePartyApplication`; re-scope `SessionViewModel` onto SessionGraph's `CoroutineScope(SupervisorJob() + Dispatchers.Default)` — no more `viewModelScope` / `onCleared` → `engine.close()`; `MainActivity` reattaches to the live instance instead of `by viewModels { Factory }`. Single-owner rule for `engine.close()` per tech-req §2.5.
**Acceptance criteria:**
- Existing `SessionViewModel` JVM unit tests still pass unmodified against the injected scope.
- Activity recreation (rotation) reattaches to the live session without losing phase or track.
- `engine.close()` has exactly one caller (`SessionGraph`), invoked only once phase is terminal (`idle`/`error`, not transient `lost`) and `SessionForegroundService` has stopped (tech-req §2.5, arch §9).
**Dependencies:** INT-02, UI-02.

### INT-06b · SessionForegroundService + notification
**Description:** Mic-type foreground service (`service/SessionForegroundService.kt`, `foregroundServiceType="microphone"`) owning lifetime + notification only, per tech-req §2.5 / arch §9: `startForegroundService` from the session flow when phase leaves `idle` (always foreground-tap-triggered — mic FGS can't start from background on API 34+); `stopSelf` when phase returns to `idle`/terminal `error`; `android:stopWithTask="false"` so a task swipe doesn't kill an active session. One silent `IMPORTANCE_LOW` "session" notification channel; `NotificationCompat` content per the §2.5 phase→text mapping (incl. track title/artist); Stop action → `ACTION_STOP` intent → `SessionViewModel.reset()`; notification updates on phase/track change only, never per-second position. Manifest gains `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`, and the service declaration.
**Acceptance criteria:**
- Session survives screen-off and 10+ minutes backgrounded with mic capture live (field-verified).
- Notification text reflects each phase transition per the §2.5 table, including track title/artist.
- Stop action ends the session and removes the notification.
- Task-swipe does not kill an active session.
**Dependencies:** INT-06a.

### INT-06c · Activity as pure viewer + permission flow
**Description:** `MainActivity` requests `POST_NOTIFICATIONS` (API 33+) alongside `RECORD_AUDIO` in one flow; `AppRemoteSpotifyController.activityContext` handoff moves from `onCreate`/`onDestroy` to `onStart`/`onStop` (tech-req §2.5), set only while the Activity can render App Remote's consent UI; keep-screen-on workaround removed (grep-verified none remains, arch §9); backgrounded-consent failure surfaces `needsSpotify` with the notification's "Action needed" copy as the recovery path.
**Acceptance criteria:**
- Fresh install grants both permissions (`RECORD_AUDIO`, `POST_NOTIFICATIONS`) in one flow.
- Denying notifications still allows a working session (notification suppressed, FGS runs) per the §2.5 permission matrix.
- No Activity reference outlives `onStop` in `AppRemoteSpotifyController`.
**Dependencies:** INT-06b.

---

## Epic 6 — Per-device calibration

Calibration outgrew INT-03's single ticket once the chirp-path bug, the acoustic referee, per-device profile storage, and a full review surface were specced (tech-req §2.6, arch §10, ui-ux §6.5) — enough independent, separately-testable surface area to warrant its own epic rather than further INT-0x sub-lettering.

### CAL-01 · Chirp plays the playback path
**Description:** Fix `AudioTrackChirpPlayer` to traverse Spotify's own deep-buffer playback route instead of Android's fast-mixer path: request `PERFORMANCE_MODE_POWER_SAVING`, `CONTENT_TYPE_MUSIC`, stereo, 44.1 kHz, `MODE_STREAM`, large buffer. Chirp waveform (f0/f1/duration/fades) is unchanged so the correlator's reference still matches — this is a transport-only fix (tech-req §2.6, arch §10). This is the correctness bug: a chirp measured on the fast mixer reports a path music never takes. Also remove the stale `TODO(INT-03b)` comment block at `SessionViewModel.kt:571` documenting the now-fixed gap.
**Acceptance criteria:**
- Two-phone mic rig (docs/field-test-protocol.md): re-run the field-test-7 chirp measurement on the fixed player; mic-measured acoustic latency and the chirp's own `SC_EVT_CALIBRATION_RESULT.measured_latency_ms` agree within the correlator's stated ±5 ms band (`test_correlate.cpp`'s chirp-loopback tolerance) — replacing field-test-7's 207 ms (mic) vs. 3 ms (engine) discrepancy documented in docs/field-test-7-int06.md.
- Before/after comparison (same route, same device) committed to `docs/`, citing the field-test-7-int06.md finding it supersedes.
- `AudioTrack` construction verified (logged/dumped `AudioAttributes`/`AudioFormat`) to confirm `PERFORMANCE_MODE_POWER_SAVING` + stereo + 44.1 kHz + `MODE_STREAM` — not inferred from latency alone.
- `TODO(INT-03b)` comment at `SessionViewModel.kt:571` removed; grep-verified no remaining reference to it in-tree.
**Dependencies:** none.

### CAL-02 · Shared FFT helper + ported analyzer
**Description:** Factor the duplicated kissfft alloc/pad/forward/inverse sequence out of `core/src/correlate/correlate.cpp` and `core/tools/lag_analyzer.cpp` into one internal helper under `core/src/`. Port `lag_analyzer.cpp`'s `analyze_window`/`next_pow2` into a core-owned module under `core/src/correlate/` (tech-req §2.6), built on the new shared helper, so GCC-PHAT and the ported single-buffer autocorrelation share one FFT plumbing implementation instead of two copies.
**Acceptance criteria:**
- `lag_analyzer` CLI still builds; its registered ctest `lag_analyzer_selftest` (`lag_analyzer --selftest`, `core/CMakeLists.txt`) still passes unmodified in behavior.
- New DSP tests (in `core/tests/test_correlate.cpp` or a new `test_lag_analyzer.cpp` registered the same way) covering the ported `analyze_window`: known-lag autocorrelation recovery within tolerance, using the existing synthetic-signal pattern — inline LCG PRNG per `test_correlate.cpp`'s `Lcg` struct, no fixture files, no WAV assets.
- Both `correlate.cpp` and the ported module call the same shared helper (code inspection: the alloc/pad/forward/inverse sequence exists in exactly one place).
- No regression: `test_gcc_phat_accuracy_20db`/`_6db` still pass at existing tolerances.
**Dependencies:** none.

### CAL-03 · Acoustic referee C ABI
**Description:** Add `sc_status_t sc_sample_latency_residual(sc_session_t*)` (non-RT) and `SC_EVT_LATENCY_RESIDUAL { int32_t residual_ms; float peak_ratio; bool valid; }`. Runs CAL-02's ported `analyze_window` over `sc_copy_recent_capture`'s 12 s post-AEC capture history, `min_lag_ms=40, max_lag_ms=2500` (ceiling load-bearing — must not be widened; harmonics lock-on above it per docs/sync-test-results.md). `valid=false` unless `SC_EVT_SYNC_ESTIMATE.converged` is currently true (LOCKED) and `peak_ratio > 4.0`. Forces `sc_set_aec_mode(SC_AEC_OFF)` for the sampled window and restores the prior mode afterward. Reads capture history only — no new audio captured or played, never writes to `output_latency_prior_ms` (referee verifies, never steers — arch §10).
**Acceptance criteria:**
- C-ABI roundtrip test in `core/tests/test_correlate.cpp`, styled on `test_session_calibration_roundtrip`: synthetic capture with two embedded copies of a signal at a known lag (simulating room + own-output echo) → `sc_sample_latency_residual` → `SC_EVT_LATENCY_RESIDUAL.residual_ms` within ±5 ms of the injected lag, `valid=true`.
- Gating test: calling `sc_sample_latency_residual` while unconverged (no prior `converged==true` estimate) yields `valid=false`, even with a clean high-`peak_ratio` signal present.
- Single-source test: capture with only one copy of the signal (no echo) → `peak_ratio` below 4.0 → `valid=false` (mirrors docs/sync-test-results.md's ~85 ms reverb-only false-positive finding).
- AEC-mode test: `sc_aec_mode_t` is `SC_AEC_OFF` for the sampled window's duration and restored to its prior value immediately after (test hook/log of mode-set calls).
- `output_latency_prior_ms` is unchanged after any `sc_sample_latency_residual` call regardless of `valid`.
**Dependencies:** CAL-02.

### CAL-04 · Calibration profile store
**Description:** Replace the orphaned flat `outlatency:<routeId>` Int DataStore key with `stringPreferencesKey("calibration_profile:<routeId>")` holding a `CalibrationProfile` JSON record (gson) per tech-req §2.6 — `schemaVersion`, `routeId`/`routeClass`/`deviceName`, `method` (MEASURED|BY_EAR|ESTIMATED), `latencyMs`, `confidence`, `sampleCount`, `acousticallyReachable`, `createdAtMs`/`updatedAtMs`, `refereeSamples` (bounded ring, cap 20), `drifted`. No migration path, per the `setpoint2` precedent. Shell-side referee aggregation lives here: calls `sc_sample_latency_residual` (CAL-03) periodically while locked, requires agreement across ≥3 valid windows before appending one sample to the ring, and sets `drifted=true` when a sample's residual exceeds ±50 ms of the profile's current `latencyMs`.
**Acceptance criteria:**
- Round-trip test: write a `CalibrationProfile`, read it back, field-for-field equality (gson serialize/deserialize).
- One atomic write per profile update — a concurrent mid-write read never observes a half-populated JSON blob.
- `refereeSamples` ring caps at 20; a 21st append evicts the oldest entry.
- Aggregation test: 2 valid `SC_EVT_LATENCY_RESIDUAL` windows do not write a ring sample; a 3rd in agreement does; a 3rd that disagrees resets the agreement count instead of writing.
- Drift test: a ring sample whose `residual_ms` differs from `latencyMs` by > 50 ms sets `drifted=true`; ≤ 50 ms leaves it false.
- Old `outlatency:` key absent from the DataStore schema (grep/test); its presence in an existing installed DataStore file is silently ignored, not migrated.
**Dependencies:** CAL-03.

### CAL-05 · Input level signal
**Description:** Add `sc_status_t sc_get_input_level(sc_session_t*, float* out_level)` per tech-req §2.1: a polled getter, normalized 0..1, attack ~10 ms / release ~300 ms exponential envelope, computed in the worker thread alongside the existing post-AEC `append_history` call (`synccore.cpp:210/214`), written to a `std::atomic<float>` (relaxed store/load) — no lock, no allocation, callable from any thread, reports silence when capture is idle. JNI binding + `SyncEngine.inputLevel(): Flow<Float>` polled at ≤15 Hz, joining the existing high-frequency stream family alongside `meterFrames` (never folded into `SyncState`, never observed by the session screen root).
**Acceptance criteria:**
- Unit test: known synthetic input envelope (step up, step down) → level converges toward the new value with the specified attack/release time constants within a stated tolerance (e.g. within 10% of expected exponential value at one time-constant elapsed).
- `sc_get_input_level` returns ~0 when called before capture starts and after capture stops — no stale/garbage value.
- Allocation-free, lock-free verified: instrumented-allocator test calling `sc_get_input_level` from a non-audio thread while capture runs, same style as CORE-01's `sc_push_capture` allocation test.
- JNI round-trip test: `SyncEngine.inputLevel()` emits at ≤15 Hz and reflects a scripted capture-level change against a fake/test engine.
- `sc_get_input_level` returns a live value before any `sc_submit_recognition_fix` and during `sc_begin_calibration` — not gated on a fix or convergence.
**Dependencies:** none.

### CAL-06 · Mic-reactive Listening/Matching
**Description:** Implement the phase-word opacity treatment in Listening/Matching per ui-ux §6.1's "Before the meter" subsection: `ink2` at rest, brightening toward `ink` as CAL-05's input level rises, opacity `= 0.55 + 0.45 × level` (level 0..1), eased through `settle` (ω = `settleOmega`) so a stray syllable doesn't flicker it. No scale/bounce/glow/gradient; no color change outside ink/ink2/ink3 (`brass` stays reserved for sync heat). At silence the word holds its 0.55 floor. Reduced Motion: level quantizes to dim/bright, crossfading over `reducedMotionCrossfadeMs` (200 ms) only on state change. Closes docs/ux-audit-2026-07.md gap #8 (meter stream dormant through listening/matching with no signal the mic is hearing anything).
**Acceptance criteria:**
- Driven by scripted `inputLevel` sequences (silence, rising, falling): phase-word opacity tracks `0.55 + 0.45 × level` through the `settle`-damped transition, verified against the expected curve at sampled time points, not just start/end.
- At sustained silence, opacity settles at 0.55, not lower.
- No opacity/motion change carries into `AIMING`+ once `MeterFrame` exists — the treatment is inert once the meter appears.
- Reduced Motion on: opacity takes only two values (dim/bright) across a scripted level sweep, crossfading 200 ms on transitions only.
- No color other than ink/ink2/ink3 is ever applied to the phase word during these phases (token audit).
**Dependencies:** CAL-05.

### CAL-07 · Tone-match (by ear) calibration
**Description:** Implement the `BY_EAR` flow per ui-ux §6.5: adjust-until-aligned (not tap-along) — a periodic tone (`toneMatchPeriodMs` 1200 ms) plays through the active route via CAL-01's fixed deep-buffer transport (a wrong-path tone gives a wrong-path offset, same failure mode as the original chirp bug), while the caliper scale (0–600 ms axis) doubles as the drag control: a cursor in the connected device's line color tracks the drag, striking full `brassBright` for `toneMatchStrikeMs` (100 ms) per tone repetition, paired with an `abClick` haptic tick. Dialled value becomes `latencyMs`, `method=BY_EAR`, stated accuracy ±30 ms (`byEarAccuracyMs`), never implied tighter. Extends `CalibrationSheet.kt`'s existing four-state shape (Idle/Running/Success/Failed) with §6.5's by-ear copy; reached automatically when the chirp's 8 s arm timeout elapses with no detection (no device-class check) and via a new Quiet "Try by ear instead" exit on the acoustic flow's Failed state; available on any route. Reduced Motion: strike flash replaced by a static engraved-style mark per cycle, `abClick` becomes the primary beat reference.
**Acceptance criteria:**
- Chirp-timeout-to-by-ear transition verified on every route type (not just headphones) — a `ChirpDetector` 8 s timeout with no detection auto-transitions the sheet with no device-class branch in the code.
- Accuracy validated with the two-phone mic rig (docs/field-test-protocol.md): a dialled tone-match result on a known route compares to mic-measured ground truth within ±30 ms across a sample of trials.
- Drag input and settled-line read-out are the same caliper component (one composable/view used in both modes), not two implementations.
- Haptic audit: `abClick` fires once per tone repetition during Running; `lockThunk` is never invoked by this flow.
- Reduced Motion: no `brassBright` flash occurs, replaced by the static mark; tone playback and `abClick` timing are unaffected.
- Success copy states "±30 ms" sourced from `DT.Calibration.byEarAccuracyMs`, not a hardcoded literal.
**Dependencies:** CAL-01.

### CAL-08 · Device shelf + detail UI
**Description:** Build the calibration review surface per ui-ux §6.5: device shelf (one row per known device — name, latency, provenance line, compact caliper strip `shelfStripHeightPt` 20dp) and device detail (hero `latencyMs` readout, full caliper well `detailScaleHeightPt` 72dp in a `recess` well, provenance line, "Calibrate again" secondary pill, drift banner, trim-promotion banner — never both at once). Caliper renders per §6.5's tick/settled-line vocabulary: real solid-hairline ticks for Measured/By ear, zero dashed-hairline ticks for Estimated; ticks compound under ordinary alpha blending (`tickAlpha` 0.35); settled line `brass` for the connected device, `ink2` otherwise, solid (real ticks) or dashed (Estimated) stroke. Empty state per §6.5's copy deck verbatim, "Calibrate phone speaker" primary action. Reached only from the single quiet entry point (ui-ux §4) — never on the session screen. The app has no list component or navigation framework, so shelf/detail/guided-calibration are panes the existing calibration sheet swaps, not separate pushed screens/routes.
**Acceptance criteria:**
- Shelf renders zero/one/many-sample cases per §6.5's rules — no line for "never seen," dashed zero-tick line for Estimated, single full-alpha tick for one sample, compounding ticks for many — reviewed against the wireframe's three example rows (Living room speaker / AirPods Pro / Kitchen speaker) with matching provenance qualifiers.
- Copy audit: shelf qualifiers ("measured {relative time}" / "not measured yet" / "set by ear, {relative time}"), empty-state body/primary, and detail-pane provenance line match ui-ux §6.5's copy deck verbatim (string diff).
- Exactly one `brass` settled line on screen at a time (connected device only); every other known device renders `ink2` (test with ≥2 known devices, one connected).
- Detail-pane hero value never renders `brass` (always `ink`) — token audit.
- Drift and trim-promotion banners are mutually exclusive — a state with both conditions true renders only one.
- Sheet open + pane swaps use `heavy` (or `reducedMotionCrossfadeMs` crossfade under Reduced Motion); caliper ticks/settled line play one `settle` reveal on first pane appearance only, not re-triggered while the pane stays open (log-verified).
**Dependencies:** CAL-04.

### CAL-09 · First-contact gate
**Description:** Implement the guided first-contact flow per ui-ux §6.5/tech-req §2.6: an unknown `routeId` (no stored profile) becoming the active output at session start gates playback with a guided prompt before playback starts (recognition proceeds unaffected) — two device-class copy variants (acoustic-capable: "Calibrate now" → guided acoustic flow; headphone-class: "Calibrate by ear" → guided tone-match flow), each with a Quiet "Not now" decline and fine-print "We'll use a generic default until you do." Declining writes a profile with `method=ESTIMATED`, `latencyMs=150`, `sampleCount=0` (so the UI re-offers next session), provenance qualifier "not measured yet" (never "estimated from…" anything).
**Acceptance criteria:**
- Copy audit: both device-class variants (title/body/primary/quiet/fine-caption) match ui-ux §6.5's "First-contact gate" section verbatim.
- Decline path: unknown routeId, gate declined → `CalibrationProfile` with `method=ESTIMATED`, `latencyMs=150`, `sampleCount=0`; `output_latency_prior_ms` set to 150 on the session's `sc_config_t`/`sc_set_output_route` call.
- Re-offer: a subsequent session on the same routeId with `sampleCount=0` re-shows the gate rather than treating it as handled.
- Gate never blocks recognition: scripted test shows `listening`/`matching` phase progress is identical whether the gate is showing, accepted, or declined.
- Device-class copy selection follows the same no-lookup rule as CAL-01/07 — acoustic offered first, falling back to by-ear only on chirp-timeout, never a device-class permission/API check (code inspection).
**Dependencies:** CAL-04.

### CAL-10 · Trim promotion
**Description:** Implement wheel-trim promotion per tech-req §2.6/ui-ux §6.5: detect ≥3 wheel-trim commits on the same `routeId`, all within ±25 ms of their median, with `|median| > 30 ms` (above the 25 ms correction deadband) → surface the Device-detail trim-promotion banner ("You've nudged this by about {median} ms, three times running. Make that the calibration?"). Accept ("Use this offset"): fold the median into `latencyMs`, set `method=BY_EAR`, append it to the profile as a By-ear tick, reset the wheel trim to 0, show the "Folded into the calibration — the wheel's back at zero" confirmation. Decline ("Keep as is"): suppress the prompt for that `routeId` for a 7-day cooling-off period. Never adopts silently.
**Acceptance criteria:**
- Detection test: scripted wheel-commit sequences (varying counts/spreads/medians) trigger the banner exactly when ≥3 commits fall within ±25 ms of their median and `|median| > 30 ms`; do not trigger when either bound is violated (e.g. 3 commits ±40 ms apart, or a tight cluster with `|median| = 20 ms`).
- Accept path: profile's `latencyMs` updates to the median, `method` becomes `BY_EAR`, a ring sample is appended, wheel trim resets to 0 — verified end-to-end against CAL-04's store.
- Decline path: prompt does not re-appear for the same `routeId` within 7 days of decline (time-mocked test); re-appears after cooldown if the trigger condition still holds.
- No silent adoption: no code path writes `latencyMs` from wheel data without the banner having been shown and accepted (state-machine audit).
- Trim-promotion and drift banners are mutually exclusive (shared with CAL-08) — trim-promotion state never also sets `drifted=true`.
**Dependencies:** CAL-04.

---

## Epic 7 — Calibration UX fixes (CFX)

A UX audit of the shipped calibration feature (CAL-01..CAL-10, all done) found nine follow-up defects, verified in code with specific locations. None of them are new calibration mechanisms — all are corrections to contracts CAL-01..CAL-10 already implemented, per the amendments in tech-req §2.6 and ui-ux §6.5. **CFX-01/02/03 are correctness and accessibility bugs — wrong data silently written, the wrong device silently measured, a flow structurally unreachable by screen-reader users — and are ordered first as such; they are not polish.** CFX-04 through CFX-09 are UI-state and consistency defects, ordered roughly by how directly they touch correctness (state leaking/overlapping) versus pure surface consistency.

This suite has 83 JVM tests and no instrumentation tests; acceptance criteria below are written to be JVM-testable wherever the defect is state/logic-shaped, and each ticket calls out explicitly where a criterion genuinely needs a device or a TalkBack pass instead.

### CFX-01 · Wrong-device attribution at calibration completion — correctness
**Description:** `onCalibrationResult()` (`SessionViewModel.kt:952`) and `commitByEar()` (`:924`) read `_syncState.value.routeId`/`routeName` at COMPLETION time, not at measurement start. If the active route changes mid-chirp or mid-tone-match, the result is written against whatever route is connected when it lands — with MEASURED/BY_EAR provenance and full confidence. Silent data corruption: a calibration for the Bluetooth speaker can end up filed against a phone speaker that connected seconds later. Fix per tech-req §2.6's "Route attribution" contract: snapshot `routeId`/`routeName`/`routeClass` when the measurement starts (`startCalibration()`/`startByEarCalibration()`) and thread that snapshot through to completion, instead of re-reading live state. A route change observed before completion invalidates the in-flight measurement — auto-cancel (mirroring `cancelCalibration()`/`cancelByEarCalibration()`), return the sheet to Idle scoped to the newly-connected route, and show "Device changed — calibration cancelled." Also closes the companion gap the audit found in the same shape: `acceptFirstContactGate()` (`:1027`) gets the same route-staleness guard `declineFirstContactGate()` (`:1076`) already applies to its live-engine call — a route change between the gate raising and the user accepting must dismiss the gate as stale rather than calibrating whichever device is now connected.
**Acceptance criteria:**
- JVM unit test (`SessionViewModelTest.kt`): start acoustic calibration on route A (fake engine), simulate `onRouteChanged` to route B before the `CalibrationResult` event arrives, then deliver the result → no `CalibrationProfile` is saved for route A or route B; `_syncState.value.calibration` lands on `Idle` (or an equivalent explicit "cancelled" terminal), not `Success`.
- JVM unit test: same shape for `commitByEar` — start by-ear on route A, route changes to B mid-`ByEarRunning`, the commit ("That's it") arrives after → no profile write for either route.
- JVM unit test (regression): start and complete a measurement with no intervening route change → the profile still writes against the route it started on, exactly as today.
- JVM unit test: `acceptFirstContactGate()` when the gate's `routeId` no longer equals `_syncState.value.routeId` at accept time → `engine.beginCalibration()`/`tonePlayer.start()` are never invoked (spy/fake call-count assertion), the gate clears, no profile is written.
- JVM unit test (regression): `acceptFirstContactGate()` when the route hasn't changed still starts calibration exactly as today.
**Dependencies:** CAL-04, CAL-09.

### CFX-02 · Recalibrate / empty-state targeting — correctness
**Description:** `SessionViewModel.requestRecalibrate()` (`:1227`) correctly refuses to start a measurement when the reviewed device isn't the connected route — but `SessionScreen.kt:207-215`'s `onRequestRecalibrate` lambda unconditionally sets `showCalibration = true` regardless of that check, swapping the sheet into guided calibration titled with the name of whatever device is CURRENTLY CONNECTED (`routeName = state.routeName` feeds `CalibrationSheet`'s title, `:184`). A user who opened Device detail for device X, believing they're about to recalibrate X, silently measures whatever device Y happens to be plugged in — the same failure shape as CFX-01, one layer up, at the UI-wiring boundary instead of the data-write boundary. The shelf empty state has the identical defect: "Calibrate phone speaker" (`DeviceShelf.kt:67`, wired at `SessionScreen.kt:216-228`) never switches the active route to the phone speaker — it starts guided calibration on whatever route is already active, under a "phone speaker" label. Fix per tech-req §2.6's "Recalibration targeting": (1) disable the "Calibrate again" pill when `detail.profile.routeId != connectedRouteId`, with an inline reason (ui-ux §6.5); (2) `SessionScreen`'s `onRequestRecalibrate` opens the guided-calibration pane only when `SessionViewModel.requestRecalibrate()` actually started something, not unconditionally; (3) the empty-state action either switches to the phone-speaker route before calibrating, or is relabelled to describe what it actually calibrates.
**Acceptance criteria:**
- JVM unit test (regression): `requestRecalibrate()` called with `deviceReview.profile.routeId != state.routeId` leaves `_syncState.value.calibration` at `Idle` — existing behavior, kept green.
- Compose state/semantics test: the "Calibrate again" pill's enabled/disabled state is asserted directly from composable state (`detail.profile.routeId == connectedRouteId`), and the "Reconnect this device to recalibrate it" string is present exactly when disabled — testable as a state/string assertion without a full render.
- Copy audit: the disabled-reason string matches ui-ux §6.5 verbatim.
- New unit test on the `SessionScreen` wiring layer (or an extracted plain-function version of the `onRequestRecalibrate` lambda, if refactored for testability): given the ViewModel's `requestRecalibrate()` did *not* start calibration (mismatched route), the guided-calibration pane does not open.
- **Needs a device pass:** end-to-end confirmation that "Calibrate phone speaker" actually switches to and calibrates the phone's built-in speaker (or, if relabelled instead, that the guided flow's title matches the new label) — JVM tests can only assert which route-switch call, if any, the handler issues against a fake route controller, not that real audio hardware responds.
**Dependencies:** CAL-08.

### CFX-03 · CaliperScale semantics + connected-state non-colour encoding — correctness / accessibility
**Description:** `CaliperScale` (`CaliperScale.kt:103-252`) is a bare `Canvas` with `detectHorizontalDragGestures` and no `Modifier.semantics` anywhere in the file — no exposed value, no role, no accessibility action. Because drag is the only way to move the Input-mode cursor and enable "That's it," and By ear is the sole calibration path ever offered on a route the chirp can't measure (Method taxonomy, tech-req §2.6), this makes an entire calibration path structurally unreachable via TalkBack. Fix per tech-req §2.6's CaliperScale accessibility contract: add a value/state-description semantics node (ms) to both `ReadOut` and `Input` modes, and add TalkBack-operable increment/decrement custom accessibility actions to `Input` mode that move the cursor by one step and invoke `onCursorChange` — a drag-free path to the same commit. Separately: connection state is conveyed purely by `brass` vs. `ink2` line colour (`DeviceShelf.kt`'s `connected` flag into `CaliperScale`; `DeviceDetail.kt:96-111`) with no textual tell, while provenance three doors down gets three redundant encodings. Fix: append a "Connected" qualifier to the shelf row's provenance line and the detail pane's title area per ui-ux §6.5's amendment, wherever `connected == true`.
**Acceptance criteria:**
- Compose semantics unit test: a `CaliperScale` in `ReadOut` mode with a non-null `settledValueMs` exposes a semantics value/state description containing the ms value — assert via the Compose UI testing semantics tree if the project's test dependencies support headless semantics assertions; otherwise **flag explicitly as needing a device pass**, since this repo has no instrumentation tests today and this may be the ticket that first requires adding one.
- Compose semantics unit test: a `CaliperScale` in `Input` mode exposes custom accessibility actions whose invocation calls `onCursorChange` with `cursorMs` offset by exactly one defined step (increment) and the inverse (decrement) — invokable directly as a semantics-action lambda in a unit test, no live TalkBack session required.
- **Needs a device/TalkBack pass:** end-to-end confirmation that a TalkBack user can reach, adjust, and commit a by-ear value using only accessibility gestures. This is explicitly called out — it is the actual bar the fix exists to clear, and no JVM test substitutes for it.
- Unit test: `DeviceShelfRow`/`DeviceDetail` composable state — given `connected = true`, the rendered provenance/title string contains "Connected"; given `connected = false`, it's absent.
- Copy audit: the connected-state string matches ui-ux §6.5's wording verbatim.
**Dependencies:** CAL-07, CAL-08.

### CFX-04 · Sheet lifecycle + first-contact-gate mutual exclusion — correctness
**Description:** `showCalibration`/`showDeviceReview` (`SessionScreen.kt:122-127`) are composable-local `remember` state nothing in `SessionViewModel` can close, and both sheets are rendered as siblings of the phase `Crossfade` (`:138-244`) rather than gated by it — so a calibration sheet stays open and interactive over "Lost the room" (`PhaseGroup.LOST`) or a concierge gate (`PhaseGroup.CONCIERGE`). Separately, the gate sheet (`state.firstContactGate?.let { ... }`, `:238-244`) and the calibration sheet (`if (showCalibration || showDeviceReview)`, `:182`) are independent conditions in the same `Box`, so both `ModalBottomSheet`s can be eligible and rendered at once today. Fix per tech-req §2.6's "Sheet lifetime & precedence": (1) close the calibration/review sheet whenever the observed `PhaseGroup` leaves `ACTIVE` for `LOST` or `CONCIERGE`; (2) the calibration sheet's open condition must exclude the moment `firstContactGate` is non-null, and the gate must be resolved before the sheet is allowed to open — gate wins per the defined precedence.
**Acceptance criteria:**
- State-driven test: given `showCalibration = true` and a scripted `state.phase` transition into `SessionPhase.LOST`/`NEEDS_SPOTIFY`/`NEEDS_PREMIUM`/`ERROR`, the calibration sheet's visibility condition evaluates false after the transition — verifiable against a fake `StateFlow<SyncState>` without rendering to a device.
- State-driven test: given `showCalibration = true` and `state.firstContactGate` becoming non-null, the calibration sheet's visibility condition evaluates false while the gate is showing.
- State-driven test: given `state.firstContactGate` non-null and a tap that would otherwise open the calibration sheet, the sheet does not become visible until the gate resolves (accept or decline clears `firstContactGate`).
- Regression: existing sheet open/close flows (CAL-08/CAL-09's tests) continue to pass unmodified.
- **Needs a device pass** only for visual confirmation that no overlay flicker/double-sheet artifact occurs during the transition itself — the state-machine correctness above is fully JVM-testable.
**Dependencies:** CAL-08, CAL-09.

### CFX-05 · Calibration entry points reachable outside ACTIVE — correctness
**Description:** "Devices" and "Calibrate" (`SessionScreen.kt:503-514`) live in `ActiveContent`, rendered only for `PhaseGroup.ACTIVE` (AIMING/CONVERGING/LOCKED/DRIFTING). `IdleContent` (`:308-335`) offers only Join and Connect Spotify — a device cannot be calibrated before joining a party, and calibrations cannot be reviewed without an active session. Per tech-req §2.6's "Entry points" amendment and ui-ux §6.5's empty-state copy (written for exactly this moment — "Play something through a speaker or headphones and JoinTheParty will get to know it"), this is a design goal the shipped `IdleContent` doesn't support. Fix: add the same quiet entry point to `IdleContent`, wired to the same `onOpenDeviceShelf`/`showDeviceReview` mechanism `ActiveContent` already uses.
**Acceptance criteria:**
- Composable-wiring test: `IdleContent` (or `SessionScreen` in the `IDLE` phase group) exposes a tappable element wired to `onOpenDeviceShelf`, verified by invoking the click handler and asserting `onOpenDeviceShelf` fires — same style as the existing `onJoinTap`/`onConnectSpotify` wiring tests.
- Regression: `ActiveContent`'s existing "Devices"/"Calibrate" entry points are unchanged.
- Design review (not JVM-testable, called out explicitly): the IDLE entry point matches the "single quiet entry point" styling (ui-ux §4) rather than introducing a second visual idiom.
**Dependencies:** CAL-08.

### CFX-06 · Evidence-based first-contact gate copy
**Description:** `firstContactVariant()` (`SessionViewModel.kt:1019`) maps `WIRED` → headphone copy, everything else → acoustic copy, asserting a route class the app cannot know in advance. Bluetooth speakers get "keeps everyone in sync on this speaker… ten seconds," then the chirp times out at 8 s and asks for fifteen more; a 3.5mm cable into a PA — an ordinary party rig — gets the false claim "Headphones can't be heard by the phone's mic" and is routed away from the acoustic measurement that would work. Per tech-req §2.6 and ui-ux §6.5's corrected copy, the gate collapses to one route-neutral variant that always attempts the acoustic flow (`acceptFirstContactGate()`'s `ACOUSTIC` branch, unconditionally) and relies on the flow's existing chirp-timeout auto-fallback (Method taxonomy) to reach By ear — never branching on `SyncCore.Route` up front. `FirstContactVariant`/`firstContactVariant()` and the `HEADPHONE` branch of `acceptFirstContactGate()` are removed as part of the simplification; `FirstContactGateTest.kt`'s per-variant copy assertions are updated to the single variant.
**Acceptance criteria:**
- Copy audit (`FirstContactGateTest.kt`): the gate's title/body/primary/quiet/fine-caption strings are identical regardless of the connected route's `SyncCore.Route` — one constant set, not two.
- Code inspection: no remaining reference to `SyncCore.Route`/`WIRED` in the gate-copy-selection path (grep-verified, matching CAL-01/07/09's existing "no device-class lookup" audit convention).
- JVM unit test: `acceptFirstContactGate()` always calls `startCalibration()` (never `startByEarCalibration()` directly), regardless of route class; By ear is reached only via the existing, separately-tested chirp-timeout transition.
- Regression: `declineFirstContactGate()`'s behavior (writes `ESTIMATED`, staleness-guards the live-engine call) is unchanged.
**Dependencies:** CAL-09.

### CFX-07 · Surface `beginCalibration()` failure
**Description:** `startCalibration()` (`SessionViewModel.kt:839`: `if (!engine.beginCalibration()) return`) is a complete silent no-op when the engine refuses to arm calibration — no state transition, no error copy. Tapping "Start calibration" produces nothing, indistinguishable from a broken button. Fix: route this failure into the existing `CalibrationState.Failed` state (the same one chirp-detection failure already uses) instead of returning early, reusing Failed's "Try again"/"Try by ear instead" recovery per ui-ux §6.5's amendment — no new UI state needed.
**Acceptance criteria:**
- JVM unit test: with a fake `engine.beginCalibration()` returning `false`, `startCalibration()` transitions `_syncState.value.calibration` to `Failed` (not left unchanged at `Idle`), and `chirp?.play()` is never called.
- JVM unit test (regression): `engine.beginCalibration()` returning `true` still transitions to `Running` and plays the chirp exactly as before.
- Copy audit: the Failed-state copy shown for this path is the existing "Couldn't hear the chirp — turn the volume up and try again" / "Try by ear instead" strings — no new string introduced.
**Dependencies:** none.

### CFX-08 · Consistent sibling-banner dismissal
**Description:** The drift banner's "Later" is wired to `onBackToShelf` (`CalibrationSheet.kt:126`: `onDismissBanner = onBackToShelf`), navigating the pane back to the device shelf, while trim promotion's "Keep as is" (`SessionViewModel.declineTrimPromotion`, `~:1200`) dismisses its banner in place on the same Device-detail pane. The two pills are visually identical siblings (same position, same weight, same pane) with different behavior underneath. Fix per ui-ux §6.5's amendment: both dismiss in place — change `CalibrationSheet`'s drift-banner wiring to call a dismiss-in-place handler (matching trim promotion's shape) instead of `onBackToShelf`.
**Acceptance criteria:**
- Unit test: tapping the drift banner's "Later" leaves `deviceReview` on the same `DeviceReviewPane.Detail` (profile unchanged) with the drift banner cleared — not `DeviceReviewPane.Shelf`.
- Unit test (regression): trim promotion's "Keep as is" continues to dismiss in place exactly as today.
- Code inspection: `CalibrationSheet.kt`'s drift banner no longer references `onBackToShelf` for its dismiss action.
**Dependencies:** CAL-08, CAL-10.

### CFX-09 · Deterministic shelf order, connected device first
**Description:** `NudgeStore.allCalibrationProfiles()` (`NudgeStore.kt:125-133`) maps `prefs.asMap().entries` directly with no sort, so shelf row order follows `DataStore`'s internal map iteration — unordered and not guaranteed stable across writes — and the connected device isn't prioritized. Per tech-req §2.6's "Shelf ordering" (split across two layers, since the store itself has no notion of "connected"): `allCalibrationProfiles()` sorts by `updatedAtMs` descending, giving every caller a stable base order; `SessionViewModel.openDeviceShelf()`, which knows `connectedRouteId`, then moves the connected device's profile (if present) to the front of that already-sorted list before exposing it to the shelf.
**Acceptance criteria:**
- JVM unit test (`NudgeStore`'s test file): given N stored profiles with distinct `updatedAtMs`, `allCalibrationProfiles()` returns them ordered by `updatedAtMs` descending.
- JVM unit test: calling `allCalibrationProfiles()` twice with no intervening writes returns the same order both times (determinism, not incidental to map iteration).
- JVM unit test (`SessionViewModelTest.kt`): `openDeviceShelf()` with a `connectedRouteId` matching one of the stored profiles places that profile first in `state.deviceReview`'s list, with the remaining profiles in the store's `updatedAtMs`-descending order behind it.
- JVM unit test: no stored profile matches `connectedRouteId` (e.g. a brand-new, still-unknown route is active) → order falls back to plain `updatedAtMs` descending with no crash.
**Dependencies:** CAL-04, CAL-08.

---

## Epic 8 — Control loop (CTL)

Field test 8 (docs/field-test-8-results.md) measured three distinct control-loop failure classes in the same session: a self-match sentinel gap (CTL-01), a stable-but-inaccurate residual sitting inside a widened deadband forever (this epic), and a single large uncorroborated correction standing uncorrected (CTL-03). The composite design reconciling event-triggered control, NTP's step/slew discipline, and adaptive playout thresholds into one mechanism lives in docs/research-closed-loop-control.md §5; tech-req §2.7 specs the first of the three mechanisms in full, and that's this epic's scope — CTL-02a (the policy-internal ring + persistence trigger) and CTL-02b (closed-loop proof + the shell comment it unblocks). CTL-01 (self-match sentinel) and CTL-03 (large-correction/comb-ambiguity gate) keep their own rows in the status table above; all three CTL items now have specs — CTL-01 via tech-req §2.9, split into CTL-01a/CTL-01b below, and CTL-03 via tech-req §2.8, split into CTL-03a/CTL-03b below. CTL-02's ring/persistence machinery is not a substitute for either and must not be stretched to cover them (tech-req §2.7's scope note). All CTL-02 acceptance criteria below are desktop-ctest-testable — no device pass required to land; a field re-verification (the five-cycle repeatability protocol, field-test-8-results.md's addendum) is listed as the post-landing check, not a landing blocker. CTL-01 is the one epic member whose full acceptance needs a device pass: the probe is an audible App Remote action, so JVM/ctest cover all of its decision logic (sentinel, turn-off trigger, verdict) and the field protocol covers the rest.

### CTL-02a · Persistence gate + residual ring in `CorrectionPolicy`
**Description:** Implement tech-req §2.7's mechanism exactly. `CorrectionPolicy` gains a fixed-size ring (default N=8) of `est.error_ms`, appended only from `on_estimate` calls where `est.valid && est.converged` and the policy is not settling — every ring entry is fresh, non-coasted fix evidence. The ring clears on `reset()`, on every emitted seek (a correction changes the operating point — post-seek residuals are a new cluster), and on any non-converged estimate (loss of convergence invalidates the cluster's premise). New `PolicyConfig` fields: `confirm_min_fixes = 3`, `confirm_window_ns = 20 s`, `confirm_agree_ms = 60`, `confirm_floor_ms = 125` — the last a deliberate resolution of RFC 5905's own internal discrepancy (Figure 27's table vs. Appendix A.5.5.6's `STEPT .128`), picked on purpose per §2.7 rather than left ambiguous. Persistence trigger: while converged, when the ring holds ≥ `confirm_min_fixes` samples spanning ≥ `confirm_window_ns`, every sample is within `confirm_agree_ms` of the cluster mean, |mean| exceeds `confirm_floor_ms`, and `est.confidence ≥ min_confidence_to_correct` (existing FT4 guard, unchanged) → emit exactly one correction computed from the cluster mean (not the instantaneous `est.error_ms`) through the existing drift-centered target formula in `on_estimate`, then clear the ring immediately. Corroboration-hungry cadence: while converged with a live above-floor cluster open, fix-request cadence drops from `fix_interval_max_ns` (30 s) to `fix_interval_base_ns` (10 s) — the same constant already used for the non-converged case, reverting once the cluster clears. Core-only, in `CorrectionPolicy`; no C ABI change, no shell change.
**Acceptance criteria** (all in `core/tests/test_policy.cpp`, existing `CHECK`/`make_est` conventions):
- Vienna-class unit test: policy configured `deadband_ms=350`; converged estimates with constant ~285 ms error and confidence 0.9 fed at 10 s spacing → NO seek before both `confirm_min_fixes` and `confirm_window_ns` are met, then EXACTLY ONE seek whose target is computed from the cluster mean; ring cleared after (the next estimate does not immediately re-fire during/after settle).
- Churn-class unit test: converged estimates scattered at beat-comb spread (e.g. alternating +280/−250/+300 ms, all inside 350) → the persistence trigger never fires regardless of how many arrive.
- Floor test: constant ~60 ms converged residual (configure `deadband_ms=350` so 60 is sub-deadband) → never fires (below `confirm_floor_ms`).
- Confidence test: Vienna-class cluster but confidence 0.19 → never fires (FT4 guard holds).
- Clearing tests: a non-converged estimate mid-accumulation clears the ring (subsequent agreeing fixes start over); an instantaneous out-of-deadband seek clears it too.
- Cadence test: converged + above-floor cluster → `current_fix_interval_ns() == fix_interval_base_ns`; cluster clears → back to `fix_interval_max_ns`.
- Inertness regression: every existing test in `test_policy.cpp` passes UNMODIFIED (core defaults `deadband_ms=25 < confirm_floor_ms=125` → the mechanism is structurally inert at core defaults, tech-req §2.7).
**Dependencies:** none.

### CTL-02b · Closed-loop proof + shell comment redirect
**Description:** Closed-loop simulation evidence in the `test_closed_loop_sawtooth_within_deadband` style (estimator + policy + simulated world), plus the `SessionGraph.kt` `ENGINE_DEADBAND_MS` comment update §2.7's Unchanged paragraph promises — pointing at tech-req §2.7 instead of the bare forward reference to "CTL-02" (`SessionGraph.kt:144-155`), keeping the measured history intact (350 stays; the deadband-150 experiment story stays).
**Acceptance criteria:**
- Closed-loop Vienna sim: `deadband_ms=350` on both estimator and policy; world starts with ~285 ms true error and fixes reading it faithfully (small noise) → system converges (declares LOCKED at ~285 residual, reproducing FT8), then the persistence gate fires within ≤90 s of convergence and final true |error| lands under `confirm_floor_ms`; total corrections ≤2.
- Closed-loop stability sim: same config, fixes scattered ±300 ms alternating around zero → zero persistence corrections over ≥5 min simulated (no churn reintroduced at 350 — the deadband-150 lesson holds).
- Existing sawtooth sim (`test_closed_loop_sawtooth_within_deadband`) unchanged and green.
- `SessionGraph.kt`'s comment references tech-req §2.7 and no longer describes the accuracy gap as unaddressed; no functional Kotlin change (comment-only diff verified by `./gradlew.bat :app:testDebugUnitTest` passing untouched).
**Dependencies:** CTL-02a.

### CTL-03a · Comb-flatness score in `analyze_window` + CLI column
**Description:** Per tech-req §2.8 Part A: `WindowLag` (`core/src/dsp/lag_window.h`) gains `double second_lag_ms = 0;` and `double comb_ratio = 0;` — the best peak's autocorrelation value divided by the strongest peak found outside a ±20 ms exclusion neighborhood around the best lag, the exclusion existing so the best peak's own shoulder never scores as its own competitor. This is a second pass over the autocorrelation array `analyze_window` already computes — no new FFT, no new buffer. The existing argmax, `peak_ratio`, and `found = peak_ratio > 4.0` stay byte-identical (the header's "do not 'improve' the math here without re-running the field-test corpus" warning). `lag_analyzer` (`core/tools/lag_analyzer.cpp`) appends `comb_ratio` as the LAST column of both CSV modes — file mode's `window_start_s,lag_ms,peak_ratio,confident` and `--stream` mode's `t_s,lag_ms,peak_ratio,confident,rms_db` — so existing positional parsers reading the first N columns don't break; `--selftest` behavior is unchanged. One-sentence honesty note per §2.8: recognition fixes never flow through `analyze_window` (they arrive as `match_offset_ms`/`frequency_skew`/`provider_confidence` with no landmark scatterplot), so this score has no live correction-path consumer yet — it's field-rig diagnostics now, and seeding input for CTL-01/the comb-ambiguity hypothesis bank later. This ticket and CTL-03b land together as the CTL-03 pair from tech-req §2.8, but touch disjoint files and carry no ordering dependency on each other.
**Acceptance criteria** (in `core/tests/test_lag_window.cpp`, existing synthetic-signal conventions — inline LCG PRNG, no fixture files, no WAV assets):
- Two-copy test: signal + one delayed copy at a known lag (as in `test_known_lag_recovered`) → `comb_ratio` well above 2 (one dominant tooth), `lag_ms` unchanged from that test's existing expectation.
- Flat-comb test: a signal constructed with several near-equal periodic copies (comb teeth ≥ 400 ms apart) → `comb_ratio` below 2.0 and `second_lag_ms` landing on a genuine competing tooth, not within the ±20 ms exclusion of the best lag.
- Exclusion test: a single-copy signal whose autocorrelation peak has a broad shoulder → `second_lag_ms` is NOT within ±20 ms of `lag_ms` (the shoulder never scores as the competitor).
- Byte-identical regression: every existing test in `test_lag_window.cpp` (`test_known_lag_recovered`, `test_single_source_lag_does_not_reproduce`, `test_min_lag_ge_max_lag_yields_not_found`) passes unmodified; the registered `lag_analyzer_selftest` ctest passes unmodified.
- CLI: both CSV headers (file mode and `--stream`) end with `,comb_ratio` and emitted rows carry the value — verified by code inspection plus `--selftest`/sample output.
**Dependencies:** none.

### CTL-03b · Large-correction corroboration hold in `CorrectionPolicy`
**Description:** Per tech-req §2.8 Part B: new `PolicyConfig` fields `large_correction_threshold_ms=1000`, `large_corroborate_agree_ms=150` (a deliberate deviation from the ~50 ms first suggested — Field Test 2's measured ±100–150 ms single-fix recognition noise would starve real large corrections indefinitely at 50 ms; §2.8's rationale), and `large_pending_max_age_ns=30 s`. A proposed seek with `|e| ≥ large_correction_threshold_ms` (but below `lost_threshold_ms`, which keeps its existing checked-first precedence — track-lost still fires immediately off one estimate) is held as a pending `{error, timestamp}` record instead of firing; fix cadence tightens to `fix_interval_min_ns` while the record is pending. The next fresh estimate (each `on_estimate` call follows a genuinely new accepted fix, never a coasted interpolation) fires the seek — computed from that fresh error, not the stale pending one — if it agrees with the pending error within `large_corroborate_agree_ms` and is still ≥ threshold. A disagreeing large error replaces the pending record; an error that drops below threshold clears it outright. The record also clears on `reset()`, on any emitted seek, on track-lost, and on expiry past `large_pending_max_age_ns`. The estimator is untouched — its `outlier_gate_ms`/`outlier_gate_max_p00` gate already corroborates large innovations when confident, but is inactive by design at mid-uncertainty, which is structurally why FT8's 1259 ms overshoot got through; this hold closes exactly that gap at the policy layer. This ticket and CTL-03a land together as the CTL-03 pair from tech-req §2.8, but touch disjoint files and carry no ordering dependency on each other.
**Acceptance criteria** (in `core/tests/test_policy.cpp`, existing `CHECK`/`make_est` conventions):
- Single-large hold: `make_est(1200)` at confidence 0.9 → `kNone`, and `pol.current_fix_interval_ns() == fix_interval_min_ns`.
- Corroborated pair: `make_est(1200)` then `make_est(1210)` (within 150 ms) on the next call → `kSeek`, with `seek_to_ms` computed from the fresh 1210 ms error, verified by exact arithmetic the same way `test_correction_outside_deadband` pins its `seek_to_ms`.
- Disagreeing pair: `make_est(1200)` then `make_est(1500)` → `kNone` (record replaced, not accumulated); a subsequent `make_est(1520)` then → `kSeek`.
- Sub-threshold clears: `make_est(1200)` then `make_est(300)` (with `deadband_ms=350` configured so 300 stays sub-deadband) → `kNone`, and a later `make_est(1210)` does not corroborate the long-gone 1200 ms record — it opens a fresh hold instead.
- Expiry: `make_est(1200)`, then a corroborating `make_est(1210)` arriving after `large_pending_max_age_ns` has elapsed → `kNone` (the stale record expired; the 1210 becomes the new pending record rather than firing).
- Track-lost precedence: `make_est(2500)` → `kTrackLost` immediately off the single estimate, exactly as today.
- The one existing-test change tech-req §2.8 authorizes ("Deliberate test change"): `test_track_lost_threshold`'s first `CHECK` — `make_est(1999.0)` expecting an immediate `kSeek` — is updated, with a comment citing tech-req §2.8, to expect the corroboration hold (`kNone` on the first 1999 ms estimate) and then fire `kSeek` on a second, agreeing estimate. Every other existing test in `test_policy.cpp` passes byte-unmodified.
- Closed-loop phantom sim (in the `test_closed_loop_sawtooth_within_deadband`/`test_closed_loop_vienna_persistence` style): an otherwise-clean fix stream with exactly one corrupted fix injected at +1259 ms while the filter is still mid-uncertainty (fix #2 or #3 after start, reproducing FT8's conf-0.74 acceptance) → zero seeks of magnitude ≥ 1000 ms ever fire, and the final true `|error|` settles ≤ 25 ms.
- Closed-loop genuine-jump sim: a fix stream where the true error really does step to ~1200 ms (e.g. the room seeks) and stays there → the hold fires after the second agreeing fix, within roughly two fix intervals of the jump, and the loop re-converges; total large seeks (magnitude ≥ 1000 ms) over the run == 1.
**Dependencies:** none.

### CTL-01a · Referee sentinel, probe scheduling & verdict in core
**Description:** Per tech-req §2.9: `CorrectionPolicy` gains (i) `on_referee_window(lag_ms, valid, now_ns)` — ring of 8 recent referee windows, `last_referee_agree_ns` updated when any 3 ringed lags mutually agree within `referee_agree_ms` (50); sentinel = converged && ≥`referee_starve_min_windows` (4) windows && now−last_agree ≥ `referee_starve_ns` (45 s); (ii) `on_tick(est, playback_live, now_ns)` — Wittenmark turn-off trigger: valid estimate with confidence < `min_confidence_to_correct` continuously for `probe_turnoff_dwell_ns` (20 s) with no accepted fix in the span; (iii) probe scheduling — both triggers behind one `probe_cooldown_ns` (120 s) rate limit, never while outstanding/settling/paused, surfaced to the worker as a probe-request the worker turns into `SC_EVT_ACTIVE_PROBE {pause_ms}` (appended at enum END); (iv) `sc_notify_probe_executed()` echo (new ABI, mirrors `sc_notify_seek_issued`) stamps the probe epoch and snapshots `probe_pre_error_ms`; (v) verdict — mean shift of first `probe_verdict_min_fixes` (2) post-echo estimates vs snapshot within `probe_verdict_window_ns` (20 s): ≤ −pause_ms/2 → genuine (clear; recovery composes with §2.7's persistence gate — 200 > 125 floor); > −pause_ms/2 → self-match → `kTrackLost` (existing lost flow); <2 fixes → inconclusive+cooldown; seeks suppressed while a probe is outstanding. All 8 new `PolicyConfig` fields per §2.9's table, all state cleared in `reset()`. Worker wiring in `synccore.cpp`: feed `on_referee_window` right after the `SC_EVT_LATENCY_RESIDUAL` dispatch in `kSampleLatencyResidual` (~633–651); call `on_tick` from `tick()`; emit the event; implement `sc_notify_probe_executed`. `synccore_abi_c_check` gains the new event + call.
**Acceptance criteria** (`core/tests/test_policy.cpp` unless noted, existing conventions):
- Sentinel starvation test: converged estimates + referee windows at mutually-disagreeing lags (spread > 50 ms) spanning ≥45 s and ≥4 windows → probe requested exactly once (cooldown blocks a second); agreeing windows (any 3 within 50 ms) keep `last_referee_agree_ns` fresh → never requested.
- Sentinel needs convergence: same starving windows while not converged → no probe.
- Turn-off trigger test: valid low-confidence (0.19) estimates via `on_tick` sustained ≥20 s with no accepted fix → probe requested; an accepted fix mid-dwell resets the dwell.
- Rate-limit tests: no second probe within 120 s; no probe while settling; no probe while playback paused.
- Verdict genuine: request → echo → two post-echo estimates shifted by ≈−200 ms → no track-lost, probe state cleared, and the −200 residual subsequently reaches §2.7's persistence gate in the same test (composition pinned).
- Verdict self-match: two post-echo estimates essentially unshifted → `kTrackLost` returned.
- Verdict inconclusive: no echo (request expires) or <2 fixes in window → no verdict, cooldown applies.
- Seek suppression: an out-of-deadband estimate arriving while a probe is outstanding → `kNone` (track-lost magnitude still fires `kTrackLost`).
- Epoch: `reset()` clears all sentinel/probe state (starvation accumulated pre-reset never fires post-reset).
- `test_synccore.cpp`: C-ABI roundtrip — drive a session to emit `SC_EVT_ACTIVE_PROBE` (synthetic starvation via repeated `sc_sample_latency_residual` on crafted capture is acceptable, OR expose the trigger through policy-level integration if the acoustic route is impractical — state which); `sc_notify_probe_executed` accepted; `synccore_abi_c_check` covers the new enum value, payload struct, and function.
- All existing tests byte-unmodified and green (sentinel/probe inert without referee windows or turn-off dwell — no existing test feeds either).
**Dependencies:** none.

### CTL-01b · Android shell: probe execution + echo
**Description:** Per tech-req §2.9's shell contract: the JNI bridge (`android/app/src/main/cpp/synccore_jni.cpp`) maps `SC_EVT_ACTIVE_PROBE {pause_ms}` onto a new typed event in `SyncCore.kt`'s `Event` sealed interface (alongside its `LatencyResidual`/`Correction` siblings), delivered the same way through `onNativeEvent`'s `when (type)`; `SyncEngine`/`SyncCore` gain `notifyProbeExecuted()` (mirroring `notifySeekIssued`) over a new `nativeNotifyProbeExecuted`. `SessionViewModel.onEngineEvent` (`android/app/src/main/java/com/jointheparty/app/ui/session/SessionViewModel.kt`, its `when (event)` around the existing `SyncCore.Event.Correction`/`.LatencyResidual` arms) handles the new event: if playback is live (`spotify?.lastKnownPlayerState?.isPaused == false`) and no calibration is running (`_syncState.value.calibration` is not `Running`/`ByEarRunning`) → `spotify.pause()` → `delay(pause_ms)` → `spotify.resume()` → `engine.notifyProbeExecuted()`; otherwise do nothing (no echo — inconclusive by design per §2.9, same shape as `requestRecalibrate`'s honest silent-decline on a mismatched route).
**Acceptance criteria** (JVM tests, existing `SessionViewModelTest.kt` conventions with fake engine/controller):
- Happy path: ActiveProbe event with pause_ms=200 → exactly the sequence pause, delay(200) (virtual time), resume, notifyProbeExecuted — order asserted via fake call log.
- Already-paused: event while player paused → zero controller calls, no echo.
- Mid-calibration: event while calibration Running → zero controller calls, no echo.
- No virtual-time hang: the delay uses the test dispatcher's virtual time (the suite stays ~10 s — cite `maybeSampleReferee`'s doc comment in `SessionViewModel.kt` on the free-running-timer hang this must avoid).
- Device pass (explicitly flagged, not JVM-testable): one real probe on the field rig (docs/field-test-protocol.md) — audible ~200 ms gap, echo logged, and a forced self-match (room seek while LOCKED) ending in track-lost → re-listen within ~30 s, per the protocol's guidance on driving checkpoints and reading the trace live rather than after the fact.
**Dependencies:** CTL-01a.

---

## Epic 9 — DSP & Probe Upgrades

Three independent spec sections (tech-req §2.10–§2.12, promoted from research-dsp-upgrades.md; docs/to-spec-review.md carries the flagged deviations and provisional markers below) decompose into six tickets. §2.10 (OSS beat-period tracker) splits into a DSP/consumer pair, DSP-01a/01b, mirroring CTL-03a/03b's DSP-module/CLI-column split. §2.11 (parameterized β-PHAT) splits into a tooling ticket and a corpus-sweep ticket, DSP-02a/02b — DSP-02b's deliverable is sweep data plus a written promotion *recommendation*, explicitly not a default-value change; per §2.11 the on-device default flip requires a *future* spec section this epic does not authorize. §2.12 (volume-duck active probe) splits into a core/ABI ticket and a Kotlin actuator ticket, DSP-03a/03b, composing with CTL-01a/01b's already-landed sentinel/probe machinery in `CorrectionPolicy`; per §2.12's field-sequencing note, the duck becomes the default probe tier only after the CTL-01 pause probe is field-proven on-device — DSP-03b lands the mechanism, not that promotion. The three chains (DSP-01, DSP-02, DSP-03) touch disjoint files and carry no ordering dependency on each other.

### DSP-01a · C++ onset-strength ring & tempogram (§2.10)
**Description:** New `core/src/dsp/oss_ring.h/.cpp` implementing incremental onset-strength tracking per tech-req §2.10: `OnsetStrengthRing::push(samples, n)` computes per-frame spectral flux (frame N=1024, hop H=512 at 48 kHz, Hann window, the existing kissfft `RealFft(1024)` wrapper), log compression `Y(m,k)=ln(1+γ·|X(m,k)|)`, half-wave-rectified flux `Δ(m)`, causal local-mean removal via a 94-sample running-sum delay line (W≈47, ±0.5 s), storage in a fixed M=1125-sample ring (~12 s, mirroring the PCM history's span). `OnsetStrengthRing::estimate_beat_period()` runs on-demand unbiased normalized autocorrelation over lag bins ℓ∈[24,112] (250–1200 ms / 240–50 BPM), harmonic-sum `s(ℓ)=r̂(ℓ)+0.5·r̂(2ℓ)` for octave disambiguation, parabolic sub-bin interpolation, returning `BeatEstimate{period_ms, salience, stable}` with `stable` requiring the last 3 estimates to agree within ±10 ms spanning ≥20 s (reusing the §2.7 `confirm_window_ns` idiom rather than a new agreement rule). All buffers sized at init, zero allocation after init — the same worker-thread, non-RT fixed-allocation discipline `CorrectionPolicy`'s existing rings already follow (CTL-02a's error ring, CTL-01a's referee ring).
**Acceptance criteria:**
- Allocation test: instrumented-allocator check that `push`/`estimate_beat_period` perform zero heap allocation after `OnsetStrengthRing` construction, same style as CORE-01's `sc_push_capture` allocation test.
- Synthetic click-track tests (`core/tests`, inline LCG PRNG/synthetic generation, no fixture files or WAV assets, per `test_correlate.cpp`'s convention): a click track at a known BPM (e.g. 120 BPM → 500 ms period) → `estimate_beat_period().period_ms` within a few ms of truth once `stable`.
- Octave-ambiguity test: a click track constructed so the raw autocorrelation peak sits at a tempo octave (e.g. a strong double-time subdivision) → the harmonic-sum reinforcement `s(ℓ)=r̂(ℓ)+0.5·r̂(2ℓ)` selects the correct fundamental period, not the octave.
- No-beat test: synthetic noise with no periodic structure → `stable` stays false across a run spanning ≥20 s (never latches onto spurious agreement).
- Constants check: `γ=100` and the `0.5` harmonic weight exist as named, explicitly-commented constants marked provisional/field-tunable per §2.10 — not inlined as bare literals, not silently treated as final the way `confirm_floor_ms` was after its RFC 5905 grounding resolved.
- Code inspection: `salience` is never read by any condition gating `stable` or feeding a consumer as evidence — diagnostics/CSV-only, for the same reason `peak_ratio` cannot gate the §2.6 referee.
- No existing test's expected output changes.
**Dependencies:** none.

### DSP-01b · Tempogram consumer wiring (§2.10)
**Description:** Wire `OnsetStrengthRing::push` into the worker drain loop at the same post-AEC tap `append_history` already uses (no new capture tap); call `estimate_beat_period()` on the `kSampleLatencyResidual` cadence — the shared referee "analysis moment," per §2.10's proposed cadence. Implement the §2.8 cross-check: if `|WindowLag.second_lag_ms − k·beat_period_ms| < 30 ms` for a small integer k, the competitor peak `analyze_window` found is corroborated as the music's own beat comb (supporting a low `comb_ratio` reading as ambiguity — the Billie Jean class — rather than a genuine second copy); `second_lag_ms` remains the free, already-shipped cross-check, this is a read-only corroboration layer on top of it. `lag_analyzer --tempo` appends a `beat_period_ms` CSV column LAST, only when the flag is passed (CTL-03a's additive-column precedent). Document, in code comments, the MHT hypothesis-bank seeding contract (`fix_offset ± k·beat_period_ms`, k=1..3) as the downstream consumer — the bank itself is explicitly OUT of this ticket's scope, pending its own future spec (research-closed-loop-control.md §5 item 3).
**Acceptance criteria:**
- Worker wiring test: pushing synthetic post-AEC samples through the worker drain loop feeds `OnsetStrengthRing::push` at the same tap/cadence as `append_history` (log/hook-verified call correspondence).
- Cadence test: `estimate_beat_period()` is invoked once per `kSampleLatencyResidual` analysis moment, not per-frame.
- Cross-check test: a constructed `WindowLag` with `second_lag_ms` at exactly `2×beat_period_ms` → cross-check flags corroboration; `second_lag_ms` far from any `k·beat_period_ms` (k=1..3) within 30 ms → no corroboration.
- CLI test: `lag_analyzer --tempo` output CSV ends with a `beat_period_ms` column; omitting `--tempo` produces the existing column set unchanged (byte-identical to pre-ticket output on the same input).
- Hard-limit tests (restated verbatim from §2.10's standing warnings 3–4): grep/code inspection confirms no code path in this ticket feeds `beat_period_ms`, `BeatEstimate`, or the cross-check result into self-match handling (CTL-01's exclusive territory), and `peak_ratio` is not read anywhere in this ticket's new code.
- MHT hypothesis-bank seeding is documented (header/code comment citing research-closed-loop-control.md §5 item 3) but no hypothesis-bank code is added by this ticket.
- No existing test's expected output changes.
**Dependencies:** DSP-01a.

### DSP-02a · Parameterized β-PHAT & tooling (§2.11)
**Description:** Per tech-req §2.11: add a trailing defaulted parameter `analyze_window(const float* x, size_t n, int rate, double min_lag_ms, double max_lag_ms, double whiten_beta = 0.5)` to `core/src/dsp/lag_window.cpp`/`.h`. At `whiten_beta == 0.5` the existing branch (`const float mag = std::sqrt(power) + 1e-9f; const float p = power / mag;`) runs VERBATIM — the non-negotiable byte-identical rule: never replaced by a generalized `pow(power, 0.5)` call, since that is not bit-identical to the shipped epsilon-guarded division. Non-default betas take a separate path: `p = std::pow(power + 1e-18f, 1.0f - beta_f)`. `lag_analyzer --beta <v>` threads the parameter through both file mode and `--stream` mode; the CSV gains a trailing `beta` column, following the CTL-03a/§2.8 additive-column precedent — appearing only when `--beta` is passed.
**Acceptance criteria:**
- Byte-identical regression: with no `whiten_beta` argument (or an explicit `0.5`), `analyze_window`'s output is byte-identical to pre-ticket output on the existing `lag_analyzer_selftest`/`test_lag_window.cpp` fixtures.
- Every existing test in `test_lag_window.cpp` and `test_correlate.cpp` passes unmodified.
- Non-default path test: `whiten_beta` at 0.6/0.7/0.8 on a synthetic two-copy signal exercises the `pow`-based branch (verified distinct from the default branch's output where beta ≠ 0.5).
- CLI test: `lag_analyzer --beta 0.7` (file mode and `--stream` mode) appends a trailing `beta` column carrying the value; omitting `--beta` produces the existing column count/headers unchanged.
- `--selftest` behavior and output unchanged.
- Code inspection: the β=0.5 branch is textually the pre-ticket legacy code, not a `pow()` call evaluated at 0.5.
**Dependencies:** none.

### DSP-02b · β-PHAT corpus sweep & promotion recommendation (§2.11)
**Description:** Using DSP-02a's `lag_analyzer --beta` tooling, run the β ∈ {0.5, 0.6, 0.7, 0.8} sweep over `docs/sync-test-results.md`'s recordings plus the FT8 captures. Record per-window lag/`peak_ratio`/`comb_ratio` deltas against the β=0.5 baseline. **Per §2.11, the on-device default flip requires a FUTURE spec section only after the promotion criteria are met — this ticket's deliverable is the sweep data plus a written promotion RECOMMENDATION under `docs/`, not a default change.** If the criteria (no lag flips/`found` regressions on healthy-lock windows; measurable reverberant-window gains) pass, the recommendation's output is to trigger a separate spec amendment, which then authorizes a follow-on default-change ticket; if criteria fail, the recommendation documents that and the default stays put with no further action implied. Worded so this ticket cannot be closed by silently editing the default.
**Acceptance criteria:**
- Sweep executed over the full corpus named in §2.11 (docs/sync-test-results.md recordings + FT8 captures) at all four β values; raw per-window sweep output (via `--beta`) retained under `docs/` or an accompanying data directory.
- Written report committed to `docs/` presenting, per β, the lag-flip count, `found`-regression count, and reverberant-window `peak_ratio`/lag-jitter/`comb_ratio` deltas versus the β=0.5 baseline.
- Report states explicitly, for each of §2.11's two promotion criteria, pass/fail against the corpus data — neither criterion left unaddressed.
- Report's recommendation is phrased as a recommendation to open a future spec amendment, not as an instruction or action that changes `whiten_beta`'s default anywhere in `core/`.
- **This ticket's own diff contains zero changes to any default parameter value, any `PolicyConfig` field, or `analyze_window`'s default argument** — verified by diff review; the ticket is not closeable by a default-flip edit.
- No existing test's expected output changes; no on-device behavior changes.
**Dependencies:** DSP-02a.

### DSP-03a · Volume-duck C ABI, worker dip detector & policy verdict (§2.12)
**Description:** Per tech-req §2.12: append `SC_EVT_ACTIVE_DUCK` at the END of `sc_event_type_t` (after `SC_EVT_ACTIVE_PROBE`), payload `sc_evt_active_duck_t { int32_t duck_ms; }`; add `sc_status_t sc_notify_duck_executed(sc_session_t*, int32_t achieved_deci_db)` mirroring `sc_notify_probe_executed`'s echo shape. REQUIRED deliverable named in §2.12: `core/tests/abi_c_check.c`'s exhaustive `event_is_known` switch gains `case SC_EVT_ACTIVE_DUCK:`, plus basic compile/link/contract coverage for `sc_evt_active_duck_t`/`sc_notify_duck_executed`, matching the file's existing `SC_EVT_ACTIVE_PROBE` pattern. Worker-side matched-filter dip detector over `sc_copy_recent_capture`'s existing 12 s post-AEC history (no new capture tap): 20 ms non-overlapping RMS hops → 50 Hz log-envelope `e(j)=10·log10(mean(x²)+ε)`; search window `[echo_ns−250 ms, echo_ns+duck_ms+750 ms]`; rectangular matched filter of width `duck_ms/20 ms` hops; dip depth `D = median(flanking baseline) − mean(template)`; robustness `z = D/(1.4826·MAD)` over the preceding 3 s of envelope. New policy entry point `on_duck_result(dip_db, z, achieved_deci_db, now_ns)` in `CorrectionPolicy`: verdict bands scaled to `achieved_db` — `D≥4 dB ∧ z≥3` → self-dominant → `kTrackLost`; `D≤1.5 dB` → room-dominant → cleared; otherwise (incl. `z<3`) → inconclusive → escalate ONCE to the shipped §2.9 pause probe (never a repeat duck). Both existing §2.9 triggers (`on_referee_window`, `on_tick`) arm a duck request FIRST; pause becomes the escalation tier reached only via the inconclusive path, not a second independent trigger. Duck cooldown 60 s (flagged proposed/field-tunable per §2.12, not derived — matching how `probe_pause_ms` itself was field-tuned); pause `probe_cooldown_ns` = 120 s unchanged. Seek suppression while a probe/duck is outstanding is unchanged from §2.9. All new state (pending-duck record, verdict accumulation) clears in `reset()` (epoch rule, matching §2.7/§2.8/§2.9). `policy.cpp` stays DSP-free — the matched-filter/z-score computation is worker-side only; `on_duck_result` receives a result, never raw capture samples. `SC_EVT_ACTIVE_PROBE`/`sc_evt_active_probe_t`/`sc_notify_probe_executed` are byte-untouched.
**Acceptance criteria:**
- `abi_c_check.c`: `event_is_known` switch compiles with `-Wswitch` exhaustiveness covering the new `SC_EVT_ACTIVE_DUCK` case; contract coverage exercises `sc_evt_active_duck_t`'s field and calls `sc_notify_duck_executed`.
- Self-match sim (`core/tests/test_policy.cpp`, closed-loop style): duck commanded → deep capture-energy dip (D≥4 dB, z≥3) → `on_duck_result` returns `kTrackLost`.
- Room-dominant sim: shallow dip (D≤1.5 dB) → cleared, no track-lost, suspicion state resets.
- Inconclusive→pause-escalation sequence test: mid-band dip or z<3 → inconclusive verdict → the pending escalation is the shipped §2.9 pause probe request, exactly once, not a repeated duck request.
- Trigger-composition test: both `on_referee_window`-starvation and `on_tick`-turnoff triggers arm a duck request first (not a pause request) when neither tier is already outstanding.
- Cooldown tests: duck cooldown enforced at 60 s; pause `probe_cooldown_ns` remains 120 s, unchanged by this ticket (regression-checked against CTL-01a's existing tests).
- Seek-suppression regression: an out-of-deadband estimate arriving while a duck or pause is outstanding still yields `kNone` (except track-lost magnitude), matching §2.9's existing rule.
- Epoch test: `reset()` clears all new duck/verdict state; dip evidence accumulated pre-reset never fires a verdict post-reset.
- Code inspection: no DSP/envelope/matched-filter computation exists in `policy.cpp`; `on_duck_result` only consumes its four scalar arguments.
- Regression: `SC_EVT_ACTIVE_PROBE`, `sc_evt_active_probe_t`, and `sc_notify_probe_executed` are byte-unmodified; CTL-01a's existing tests pass unmodified.
**Dependencies:** CTL-01a (composes with its existing sentinel/probe triggers and ABI enum; already landed).

### DSP-03b · Kotlin volume-duck actuator & echo (§2.12)
**Description:** Per tech-req §2.12: `SessionViewModel` handler for `Event.ActiveDuck(duckMs)` (JNI plumbing: event case in `synccore_jni.cpp`, `Event.ActiveDuck` in `SyncCore.kt`'s sealed interface, `SyncEngine`/`SyncCore.notifyDuckExecuted()` over a new `nativeNotifyDuckExecuted`, mirroring CTL-01b's probe plumbing). `AudioManager` `STREAM_MUSIC` duck via `getStreamVolumeDb`-driven index selection targeting −6 dB (`targetIdx = (original downTo 0).first { ... }`), achieved dB computed from the original/target index dB difference, echoed as a deci-dB int (`(achievedDb*10).roundToInt()`) via `notifyDuckExecuted`. Bounded coroutine shape — duck → `delay(duckMs)` → restore → `notifyDuckExecuted(achievedDeciDb)` — no free-running loop (the `maybeSampleReferee` hang precedent). Same shell gates as `onActiveProbe`: no-op (no echo) when playback is already paused or calibration is `Running`/`ByEarRunning`.
**Acceptance criteria** (JVM tests, `SessionViewModelTest.kt` conventions with fake engine/`AudioManager`):
- Happy path: `ActiveDuck` event with `duckMs=150` → exact sequence: volume set to target index, `delay(150)` (virtual time), volume restored to original, `notifyDuckExecuted(achievedDeciDb)` — order asserted via fake call log.
- Achieved-deci-dB echo test: a fake `AudioManager.getStreamVolumeDb` returning index dB values that don't hit exactly −6 dB (quantization) → the echoed `achievedDeciDb` reflects the actually-commanded index delta, not a hardcoded 60.
- Already-paused: event while player paused → zero `AudioManager` volume calls, no echo — mirrors CTL-01b's probe test.
- Mid-calibration: event while calibration `Running`/`ByEarRunning` → zero volume calls, no echo — mirrors CTL-01b's probe test.
- No virtual-time hang: the `delay` uses the test dispatcher's virtual time (suite stays fast; cite `maybeSampleReferee`'s doc comment on the free-running-timer hang this must avoid) — mirrors CTL-01b's corresponding test.
- JNI round-trip test: `SC_EVT_ACTIVE_DUCK` maps to `Event.ActiveDuck(duckMs)` through `onNativeEvent`'s `when (type)`, and `notifyDuckExecuted` calls through to `nativeNotifyDuckExecuted`/`sc_notify_duck_executed`.
- Build regression: the app's debug build succeeds with the new JNI surface wired.
**Dependencies:** DSP-03a. **Field-sequencing note (§2.12, verbatim constraint):** the CTL-01 device pass runs first with the pause probe as shipped — the duck becomes the default tier only after the triggers are field-proven on-device. This ticket lands the mechanism only; it must not be read as promoting the duck ahead of that pass.

---

## Dependency graph (summary)

```
RES-01 ─────────────────────────────▶ NAT-06, AUTH-04
RES-02 ─────────────▶ (priors for CORE-03 tuning, INT-01 settle window)

SCAF-01 ─▶ SCAF-02 ─▶ SCAF-04 ─▶ UI-01
   │          │  └─▶ NAT-01 ─▶ NAT-03 ─▶ UI-02 ─▶ UI-03/UI-04 ─▶ UI-05 ─▶ INT-01
   │          ├─▶ AUTH-01 ─▶ NAT-07 ─▶ AUTH-05 ─▶ UI-06        ▲
   │          └─▶ NAT-05 ──────────────────────────────────────┘
   ├─▶ SCAF-03 ─▶ (Android mirror chain → INT-02)
   ├─▶ AUTH-03 ─▶ AUTH-04
   └─▶ CORE-01 ─▶ CORE-02 ─▶ CORE-03 ─▶ INT-01
            ├─▶ CORE-04 ─▶ INT-03
            └─▶ CORE-05 ─▶ CORE-06 ─▶ INT-04
                              CORE-07 (gate, feeds INT-05)

INT-02, UI-02 ─▶ INT-06a ─▶ INT-06b ─▶ INT-06c

CAL-02 ─▶ CAL-03 ─▶ CAL-04 ─┬─▶ CAL-08
                             ├─▶ CAL-09
                             └─▶ CAL-10
CAL-01 ─▶ CAL-07
CAL-05 ─▶ CAL-06

CAL-04, CAL-09        ─▶ CFX-01   (route-attribution + accept-gate staleness fix)
CAL-08                ─▶ CFX-02   (recalibrate / empty-state targeting fix)
CAL-07, CAL-08        ─▶ CFX-03   (caliper a11y + connected-state non-colour fix)
CAL-08, CAL-09        ─▶ CFX-04   (sheet lifecycle + gate mutual exclusion)
CAL-08                ─▶ CFX-05   (entry points outside ACTIVE)
CAL-09                ─▶ CFX-06   (evidence-based gate copy)
(none)                ─▶ CFX-07   (surface beginCalibration() failure)
CAL-08, CAL-10        ─▶ CFX-08   (consistent sibling-banner dismissal)
CAL-04, CAL-08        ─▶ CFX-09   (deterministic shelf order, connected first)

CTL-01a ─▶ CTL-01b ; CTL-02a ─▶ CTL-02b ; CTL-03a, CTL-03b independent   (Epic 8)

DSP-01a ─▶ DSP-01b
DSP-02a ─▶ DSP-02b ─▶ (future spec amendment) ─▶ (future default-flip ticket)
CTL-01a ─▶ DSP-03a ─▶ DSP-03b ─▶ (CTL-01 device-pass field-sequencing gate on default-tier promotion)   (Epic 9)
```

## Critical path to MVP-on-device (INT-01, iOS)

**SCAF-01 → CORE-01 → CORE-02 → CORE-03 → INT-01**, joined at INT-01 by the shell chain **SCAF-02 → NAT-01 → NAT-03 → UI-02 → UI-03/04 → UI-05** and the service chain **AUTH-01 → NAT-07** / **AUTH-03** / **NAT-05**.

The core estimator chain (CORE-01→02→03) and the shell chain run **in parallel** after SCAF-01/02; the longest serial spine is whichever finishes last — plan for the estimator chain, since CORE-02/03's simulation test surface is the deepest work. RES-02 should run in week 1 because its measured priors feed CORE-03's tuning and INT-01's settle window. Everything in Epic 4 except AUTH-01/03 (and CORE-04/05/06, NAT-06/08, UI-06) is **off** the MVP critical path and can trail.
