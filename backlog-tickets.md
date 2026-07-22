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
| Everything else | ⬜ Not started | — |

**PM decisions logged 2026-07-21:** deadband stays 25 ms globally · learned command latency persists across sessions (ABI getter added) · self-hearing guard window confirmed ±30 ms. **Pivot:** MVP critical path moves to Android (INT-02 chain); SCAF-02/iOS deferred until a Mac is available.
**MVP definition:** one device (iOS first — no token vendor needed for ShazamKit) recognizes a live speaker, plays the same track via Spotify, converges to lock, meter + wheel functional. Android reaches parity in the same epics via its own tickets.

---

## Epic 0 — Risk & Research (run first; cheap, de-risks everything)

### RES-01 · Confirm ShazamKit Android commercial terms & quotas
**Description:** Verify Apple's ShazamKit Android AAR redistribution/commercial terms, request quotas, and token TTL rules (tech-req §3.2, arch §11.3). Outcome is a written go/no-go; fallback decision is ACRCloud.
**Acceptance criteria:**
- Written summary of license terms, quota limits, and token constraints committed to `docs/`.
- Go/no-go decision recorded; if no-go, ACRCloud selected and NAT-06 re-scoped.
**Dependencies:** none. **Blocks:** NAT-06, AUTH-04.

### RES-02 · App Remote seek latency & jitter benchmark
**Description:** Build `tools/latency-bench`: scripted measurement of Spotify App Remote command→audible latency and seek settle-time distribution on 2 iOS + 2 Android reference devices (arch §11.2). Results set the estimator's priors and settle window.
**Acceptance criteria:**
- Bench rig runs a scripted seek sequence and logs measured latencies to CSV.
- Distribution report (median/p90) per device committed to `docs/`; default `command_latency_prior_ms` and settle-window values chosen from data.
**Dependencies:** none (uses throwaway script + any Spotify account, not the app).

---

## Epic 1 — Scaffold & Tokens

### SCAF-01 · Monorepo scaffold + core build system
**Description:** Create the repo layout from arch §10 (`core/`, `ios/`, `android/`, `backend/`, `tools/`, `docs/`). Root `core/CMakeLists.txt` (CMake ≥ 3.28) building an empty `synccore` static lib + desktop test target. CI pipeline runs the desktop tests on push.
**Acceptance criteria:**
- `cmake --build` produces `libsynccore` for host + a runnable (empty) test binary.
- CI green on a trivial test; folder layout matches arch §10.
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
```

## Critical path to MVP-on-device (INT-01, iOS)

**SCAF-01 → CORE-01 → CORE-02 → CORE-03 → INT-01**, joined at INT-01 by the shell chain **SCAF-02 → NAT-01 → NAT-03 → UI-02 → UI-03/04 → UI-05** and the service chain **AUTH-01 → NAT-07** / **AUTH-03** / **NAT-05**.

The core estimator chain (CORE-01→02→03) and the shell chain run **in parallel** after SCAF-01/02; the longest serial spine is whichever finishes last — plan for the estimator chain, since CORE-02/03's simulation test surface is the deepest work. RES-02 should run in week 1 because its measured priors feed CORE-03's tuning and INT-01's settle window. Everything in Epic 4 except AUTH-01/03 (and CORE-04/05/06, NAT-06/08, UI-06) is **off** the MVP critical path and can trail.
