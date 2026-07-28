# JoinTheParty — Development Backlog

**Phase:** `/to-tickets`
**Sources:** `architecture-spec.md` · `technical-requirements.md` · `ui-ux-design-system.md`
**Date:** 2026-07-21

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
| CAL-01 | 🟡 Code done — chirp now takes Spotify's deep-buffer transport; **headline AC needs the two-phone mic rig** (chirp-reported vs mic-measured) | `5cea89f` |
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
```

## Critical path to MVP-on-device (INT-01, iOS)

**SCAF-01 → CORE-01 → CORE-02 → CORE-03 → INT-01**, joined at INT-01 by the shell chain **SCAF-02 → NAT-01 → NAT-03 → UI-02 → UI-03/04 → UI-05** and the service chain **AUTH-01 → NAT-07** / **AUTH-03** / **NAT-05**.

The core estimator chain (CORE-01→02→03) and the shell chain run **in parallel** after SCAF-01/02; the longest serial spine is whichever finishes last — plan for the estimator chain, since CORE-02/03's simulation test surface is the deepest work. RES-02 should run in week 1 because its measured priors feed CORE-03's tuning and INT-01's settle window. Everything in Epic 4 except AUTH-01/03 (and CORE-04/05/06, NAT-06/08, UI-06) is **off** the MVP critical path and can trail.
