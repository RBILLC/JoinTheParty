# JoinTheParty — Architecture Specification

**Phase:** `/wayfinder` (core technical decisions)
**Date:** 2026-07-21
**Status:** Decided — ready for scaffold phase

---

## 1. Product Summary

A listener stands near an external speaker playing music (beach, party, bar). They open JoinTheParty, which:

1. Identifies the song **and the exact playback timestamp** from a microphone capture.
2. Commands the user's Spotify app to play the same track, seeked to the live position.
3. Continuously measures residual sync error and corrects it, so the phone's output stays phase-aligned with the external speaker.
4. Exposes a precision manual nudge UI for AV engineers to trim out fixed latencies (e.g., Bluetooth headphones).

The hard problem is not song ID — it is **latency accounting and continuous correction** across a chain we only partially control (mic input latency → recognition delay → Spotify command latency → output latency → Bluetooth codec latency).

---

## 2. Decision 1 — Mobile Framework

### Verdict: **Native (Swift / Kotlin) shells + a shared C++ DSP core ("SyncCore")**

| Criterion | Native + C++ core | Flutter | React Native |
|---|---|---|---|
| ShazamKit bridging | First-class (Swift native; official Apple AAR on Android) | Manual platform channels, both platforms | Manual native modules, both platforms |
| Spotify App Remote SDK | First-class (official iOS framework + Android AAR) | Unofficial/stale community wrappers | Unofficial/stale community wrappers |
| Real-time audio I/O | AVAudioEngine / Core Audio; Oboe (AAudio) — lowest achievable latency | Bridge unsuitable for real-time; must drop to FFI + native audio anyway | Same — JSI helps but audio still lives in native code |
| DSP + AEC (C/C++ libs) | Direct static linking | `dart:ffi` (workable but adds a second FFI boundary) | JSI/C++ TurboModules (workable, most friction) |
| Deterministic timing | Best — no GC/bridge jitter in the audio path | Dart isolates add scheduling jitter for control code | JS event loop is the worst fit for a timing product |
| UI velocity | Slower (two UIs) | Fastest | Fast |

**Rationale.** Every load-bearing component of this app — ShazamKit, Spotify App Remote, low-latency audio I/O, AEC — is a native-platform SDK or a C/C++ library. A cross-platform UI framework would not share any of that; it would only share the UI (which is small: one main screen, a nudge control, settings) while adding a bridge that injects jitter exactly where this product cannot tolerate it. The code we genuinely want to share — offset estimation, filtering, the correction control loop, AEC integration — is DSP and control logic, and the correct sharing mechanism for that is a C++ core, not a UI framework.

- **SyncCore (C++17)**: platform-independent static library. Contains offset estimator, Kalman filter, correction policy, AEC wrapper, cross-correlation utilities. No platform APIs inside; audio buffers and clock timestamps are passed in. Unit-testable on desktop with recorded fixtures.
- **iOS shell (Swift, SwiftUI)**: AVAudioEngine capture, ShazamKit, Spotify iOS SDK (App Remote), UI. Bridges to SyncCore directly (Swift ↔ C++ interop).
- **Android shell (Kotlin, Compose)**: Oboe/AAudio capture, ShazamKit Android AAR, Spotify Android App Remote SDK, UI. Bridges via JNI.

---

## 3. Decision 2 — Recognition Stack (song + timestamp)

### Verdict: **ShazamKit on both platforms**

Apple ships ShazamKit for **Android** as an official AAR (requires an Apple Developer Program membership for the token), so the "best path for Android" is the same engine as iOS — one recognition behavior, one metadata shape, both platforms.

What ShazamKit gives us that generic recognition APIs don't:

- **`matchOffset`** — position within the catalog track where the captured query matched.
- **`predictedCurrentMatchOffset`** — `matchOffset` extrapolated to "now," compensating for capture/processing elapsed time. This is our primary timestamp source.
- **`frequencySkew`** — detects if the external source is playing at a slightly wrong speed (worn turntable, cheap speaker DSP). If skew is non-negligible we warn the user that perfect lock is impossible via seek-only correction.
- **ISRC + metadata** — used to find the *same recording* on Spotify.

**Catalog mapping (Shazam → Spotify):** query the Spotify Web API with `isrc:<code>`. Matching by ISRC (not title/artist) matters because a different master/edit of the same song has different silence padding and timing, which would poison the offset. If ISRC lookup fails, fall back to title+artist search and flag the session as "loose sync" (iterative correction will still converge, but initial seek confidence is lower).

**Fallback path (Android or ShazamKit outage):** ACRCloud, which returns `play_offset_ms` and ISRC. Abstracted behind a `RecognitionProvider` interface so the sync engine doesn't care which engine produced the (trackId, offset, timestamp) tuple.

---

## 4. Decision 3 — Playback Stack

### Verdict: **Spotify App Remote SDK (iOS + Android), auth via OAuth PKCE**

- App Remote drives the user's installed Spotify app: `play(trackUri)` then `seekTo(positionMs)` / `seek(toPosition:)`. Requires Spotify installed and a Premium account (seek is Premium-gated) — both are hard product requirements, surfaced at onboarding.
- **Known limitation, embraced by the design:** App Remote command latency is variable (tens to a few hundred ms) and reported playback position has jitter. We do not fight this with one perfect seek; the iterative correction loop (§6) treats the first seek as a coarse aim and converges from there.
- **No playback-rate control** is available through App Remote. Therefore all corrections are **seek-based**, which drives the correction policy in §6 (deadband + micro-seek, never continuous rate warping).

---

## 5. Decision 4 — Audio / DSP Stack

| Concern | Choice |
|---|---|
| Capture (iOS) | AVAudioEngine input tap, 48 kHz mono float |
| Capture (Android) | Oboe (AAudio backend), `VOICE_RECOGNITION` preset for an unprocessed feed when AEC is off; `VOICE_COMMUNICATION` when platform AEC is wanted |
| Open-source AEC | **WebRTC AudioProcessing Module (AEC3)** — see §7 |
| Cross-correlation / alignment | GCC-PHAT in SyncCore (KISS FFT), used for latency self-calibration |
| Filtering / estimation | 2-state Kalman filter (offset error, clock-drift rate) in SyncCore |
| Latency introspection | iOS: `AVAudioSession` input/output latency values; Android: AAudio timestamps (`AudioTimestamp`) for input/output latency |

All buffers flow through SyncCore with a monotonic-clock timestamp attached at the audio-callback boundary, so every estimate is expressed on one timebase per device.

---

## 6. Constraint Handling — Iterative Sync Correction

### 6.1 The timing model

```
external_position(t)  = matchOffset + (t − t_match) × (1 + skew)
local_position(t)     = spotify_reported_position + output_chain_latency + user_nudge
sync_error(t)         = local_position(t) − external_position(t)      // + = we are ahead
```

`output_chain_latency` = device DAC latency (queryable) + Bluetooth latency (NOT queryable — the reason the manual nudge exists) + Spotify app internal buffering (estimated by calibration, §6.4).

### 6.2 Control loop (runs in SyncCore)

1. **Coarse aim (once):** on first match, compute target = `predictedCurrentMatchOffset` + estimated command latency (prior: last calibrated value, default 250 ms), issue `play + seekTo`.
2. **Measure:** every 8–12 s (and immediately after any seek), run a fresh recognition pass on the mic feed to get a new external-position fix. Each fix yields one `sync_error` observation.
3. **Filter:** feed observations into the Kalman filter estimating `[offset_error, drift_rate]`. Drift is real: the speaker's clock and the phone's DAC clock differ by ±10–100 ppm ≈ up to ~20 ms over a 3-minute song.
4. **Correct (policy):**
   - `|error| < 25 ms` → do nothing (deadband; a seek is more disruptive than the error).
   - `25 ms ≤ |error| < 2 s` → micro-seek: `seekTo(current + error)`, then hold corrections for 3 s while the estimate re-settles.
   - `|error| ≥ 2 s` → assume track restarted/skipped externally; re-run full recognition from scratch.
5. **Converge & relax:** once three consecutive fixes land inside the deadband, stretch the measurement interval (battery), tighten again if error grows.

Recognition-based fixes (step 2) rather than continuous mic/reference cross-correlation are the primary measurement because Spotify's audio is DRM-protected — we cannot decode the track to build a local reference waveform. ShazamKit re-matching gives us absolute position fixes without needing one.

### 6.3 Handling `frequencySkew`

If ShazamKit reports skew beyond ~0.05 %, seek-only correction turns into a sawtooth (drift, seek, drift, seek). The Kalman drift estimate schedules micro-seeks *pre-emptively* at the deadband edge so the sawtooth stays inside ±25 ms, and the UI shows a "source running fast/slow" indicator.

### 6.4 Latency self-calibration (one-time, per output route)

To estimate Spotify command latency + output latency without user effort: in a quiet-moment calibration flow, the app plays a short chirp via Spotify seek-to-known-position, captures it with the mic, and GCC-PHAT-correlates against the expected chirp to measure the true command→audible delay. Stored per output route (speaker / wired / each named BT device) and used as the coarse-aim prior. Bluetooth latency that calibration can't fully capture is what the manual nudge is for.

---

## 7. Constraint Handling — Acoustic Echo Cancellation

**Scenario:** the user plays through the phone's own speaker. The mic now hears (external speaker + our own playback), and recognition fixes would lock onto our own output — a feedback loop that reports "perfect sync" forever.

### Verdict: **layered AEC — platform AEC first line, WebRTC AEC3 (open-source) as the software layer**

1. **Platform AEC (first line).** iOS voice-processing I/O and Android's HAL-level `AcousticEchoCanceler` operate at the OS layer, where the *system output mix* — including the Spotify app's audio, which our process can never touch — is available as the echo reference. This is the only layer that can see the true reference signal, so it does the heavy lifting.
2. **WebRTC AEC3 (open-source requirement, second line).** Compiled into SyncCore from the WebRTC AudioProcessing Module (BSD-licensed). Platform AEC leaves residual echo, and on some Android devices hardware AEC is absent or poor. AEC3 runs on the post-platform-AEC mic feed. Reference signal: since the Spotify PCM is inaccessible, we feed AEC3 a *synthesized* reference — we know exactly which track and which position our device is playing, so a cached preview/analysis rendering of the same audio segment, time-shifted by the known output latency, acts as the far-end reference. It is not sample-exact, but AEC3's adaptive filter needs correlation, not perfection, and residual suppression of 15–25 dB is enough for the fingerprinting front-end.
3. **Recognition-side guard (last line).** Before accepting a sync fix while in speaker mode, SyncCore checks whether the measured offset equals our *own* commanded position within ±30 ms. A fix that exactly matches our own playback (and arrived while our own output dominates the capture energy) is discarded as self-hearing. This makes the system safe even when both AEC layers underperform.

Speaker mode is detected from the audio route; headphone routes bypass AEC entirely (mic hears only the external speaker — the clean case).

---

## 8. Constraint Handling — Manual AV Nudge UI

A precision phase-trim control for AV engineers, layered on top of automatic sync:

- **Control:** horizontal fine-adjust wheel (rotary, inertial) with **±5 ms detents**, haptic tick per detent; flick gesture for ±50 ms coarse steps; numeric readout in ms with direct text entry; range ±750 ms (covers worst-case Bluetooth codecs).
- **Semantics:** the nudge is an *additive term on the sync target* (`user_nudge` in §6.1), applied by the same correction loop — it does not fight the automatic corrections, it re-aims them. Applied as one micro-seek per settled adjustment (debounced 400 ms after the wheel stops), not per detent.
- **Persistence:** stored per output route (e.g., "AirPods Pro: −180 ms", "phone speaker: 0"), auto-recalled when that route reconnects.
- **Pro affordances:** A/B mute toggle (mute local output to compare against the external speaker), and a live sync-error meter (±ms bar) fed by the Kalman estimate so an engineer can see convergence.

---

## 9. Constraint Handling — Session lifetime (backgrounding)

**Constraint:** the mic capture + correction loop must survive the user backgrounding the app (checking Spotify, locking the phone) or the party experience breaks mid-sync. Android kills backgrounded processes doing mic/CPU work within seconds, and a bound service dies with its last bound client.

### Verdict: **dedicated foreground service (`SessionForegroundService`, type `microphone`) owning only lifetime + notification, session state held separately in a process-scoped `SessionGraph`**

- **Foreground service, not a bound-only service.** A service bound to `MainActivity` dies exactly when we need survival — screen off, task switch. A foreground service with an active notification is the OS-sanctioned way to keep mic capture alive once the Activity is gone.
- **The service owns lifetime, not state.** `SessionGraph` — process-scoped, anchored in `JoinThePartyApplication` — holds SyncCore, recognition, Spotify controller, and engine lifetime; the service only starts/stops itself and posts the notification. This keeps `SessionViewModel` ignorant of whether a service is running, and lets Activity recreation (rotation, task switch) reattach to the same live session instead of losing it — something today's Activity-owned session cannot do.
- **`stopWithTask="false"`, notification Stop is the exit.** Letting a task swipe kill the service would silently drop an in-progress sync mid-party — worse than the standard foreground-service behavior of surviving swipe. The notification's Stop action gives the user an explicit way out, so the survival behavior doesn't need fighting.
- **Alternative rejected — keep-screen-on + Activity-owned lifetime (today's design).** Works only while the Activity survives, which stops being true the moment the OS can background or kill the process (an incoming call, checking another app) — the exact failure this ticket exists to close. It's also the reason the keep-screen-on hack exists; the foreground service makes it unnecessary.
- **Accepted gap:** a mic-type FGS cannot be *started* from the background on API 34+. Not a problem here — the only start trigger, `startListening`, always fires from a foreground user tap.

---

## 10. Constraint Handling — Output-chain latency

**Constraint:** `output_chain_latency` (§6.1) can't be measured by one technique for every route — headphones are acoustically invisible to the mic, and the calibration chirp itself was found to be measuring the wrong signal path.

### Verdict: **route-behavior-selected method (MEASURED / BY_EAR / ESTIMATED); chirp forced through the same deep-buffer transport as real playback; perceptual tone-match as the headphone method (and a universal fallback); referee as verifier, not controller**

- **Calibration must traverse the playback path, not shortcut around it.** `AudioTrackChirpPlayer`'s static mono buffer rides Android's fast mixer; Spotify's own audio doesn't (`FLAG_DEEP_BUFFER`, stereo, 44.1 kHz — field-test-7 measured 207 ms acoustic latency there vs. 3 ms engine-reported on the fast-mixer path). A latency number from the wrong path is worse than no number — it's confidently wrong and nothing downstream cross-checks it. Fix is transport-only: same chirp waveform (f0/f1/duration/fades unchanged, so the correlator's reference still matches), `PERFORMANCE_MODE_POWER_SAVING`/`MODE_STREAM`/stereo/44.1 kHz. The tone-match tone (below) rides the same fixed transport for the same reason.
- **Headphones are structurally unmeasurable, not just hard to measure.** No acoustic signal reaches the mic at any volume — there is nothing to correlate, at any SNR. Rather than gate on a device-class lookup, the guided flow simply *tries* the chirp on every non-speaker route and lets `ChirpDetector`'s existing 8 s arm timeout be the signal: no detection auto-falls to `BY_EAR` tone-match. Zero new API surface, and it's agnostic to what the route actually is (headphones, a muted device, a bad mic day).
- **Tone-match is adjust-until-aligned, not tap-along.** Tapping in time with a beat measures the user's motor-response latency (~50–100 ms) stacked on top of the audio latency being measured — a second unknown that would need its own calibration. Perceptual alignment (dial an offset until what's heard matches what's seen) has no motor-response term, so the dialled value is a materially cleaner latency estimate. Expected accuracy ±30 ms (human audio/visual alignment tolerance — asymmetric; lag is forgiven more readily than lead) plus one display-frame (~16 ms) carried as a known, accepted systematic term. Offered on every route, not only headphones — a chirp-distrustful user always has an ears-only fallback.
- **The referee verifies; it does not steer.** Continuously nudging `output_latency_prior_ms` from the acoustic residual was rejected: the residual is attributable to output latency only while the estimator is LOCKED and converged (§6.2's deadband means position error is near-zero then); sampling while converging/drifting would fold position error into a latency correction, and the two failure modes become indistinguishable. The referee instead samples periodically while locked, requires agreement across ≥3 windows plus its own `peak_ratio` confidence gate, and only ever writes to the stored profile — never the live prior. A drifted profile prompts a UI-level redo instead of auto-correcting silently.
- **The referee autocorrelates the capture alone — there is no reference signal.** SyncCore has no decoded copy of what Spotify is playing (exactly why AEC is a passthrough stub, §7) and `sc_push_reference` is never called in production, so a reference cross-correlation was never buildable. Instead: while playing through a speaker/BT-speaker route, the mic hears two copies of the same song — ours and the room's. Autocorrelating the 12 s post-AEC capture history (`sc_copy_recent_capture`) via a ported `analyze_window`/`lag_analyzer` module finds the peak at the lag between those two copies — the acoustic error a listener actually perceives, and the exact technique that has graded every field test to date.
- **Eligibility and self-invalidation fall out of the signal, not a route check.** Headphones put no copy of our audio into the mic, so there's no second peak and `peak_ratio` fails by construction — cheaper to skip early via a cached `acousticallyReachable` flag, but the `peak_ratio` gate is the real mechanism. The same gate protects against a silent or track-changed room: a low-lag reading with only one source playing is reverb, not sync (field testing's ~85 ms reverb noise floor with nothing playing) — `peak_ratio`, not the lag value, is what tells the two apart.
- **Search range 40–2500 ms, and the ceiling is load-bearing.** Widening past 2500 ms lets the analyzer lock onto harmonics of the music's own periodicity — spurious multi-second readings, observed in field testing at a 4000 ms ceiling.
- **Alternative rejected — A2DP codec-class latency priors.** Considered for headphone routes: an SBC/AAC/aptX/LDAC seed table via `BluetoothCodecStatus` (API 28+, needs `BLUETOOTH_CONNECT`). Rejected — codec name alone doesn't fix latency, chipset and firmware do, so the table would buy a confident-looking number with no way to verify it, behind a permission and an OS floor, for a value that's still a guess. Deferred, not cancelled (risk §13.7).
- **Alternative rejected — always play a mid-session verification chirp.** Works, but is audible and disruptive; passive autocorrelation of capture data the mic is already producing wins.

---

## 11. Data Flow

```
                       ┌──────────────────────────────────────────────┐
                       │                UI (Swift/Kotlin)             │
                       │  session screen · nudge wheel · sync meter   │
                       └──────────────┬───────────────▲───────────────┘
                                      │ intents       │ state (error, track, status)
                       ┌──────────────▼───────────────┴───────────────┐
                       │        SessionCoordinator (per platform)     │
                       └───┬──────────────┬──────────────┬────────────┘
                           │              │              │
              ┌────────────▼───┐   ┌──────▼───────┐   ┌──▼─────────────────┐
              │ RecognitionPro-│   │ SyncCore C++ │   │ SpotifyController  │
              │ vider          │   │  · Kalman    │   │  · App Remote      │
              │  · ShazamKit   │──▶│  · policy    │──▶│  · play/seekTo     │
              │  · (ACRCloud)  │fix│  · AEC3      │cmd│  · position events │
              └────────▲───────┘   │  · GCC-PHAT  │◀──┤                    │
                       │ mic PCM   └──────▲───────┘pos└────────────────────┘
              ┌────────┴───────┐          │
              │ AudioCapture   │──────────┘ timestamped PCM
              │  AVAudioEngine │
              │  / Oboe        │
              └────────────────┘

External: Spotify Web API (ISRC→URI mapping, OAuth PKCE) — REST, outside the audio path.
```

One rule governs the diagram: **everything timing-critical lives in SyncCore or below; everything above it is control-plane and may be slow.**

---

## 12. Folder Structure

```
JoinTheParty/
├── architecture-spec.md
├── core/                          # SyncCore — C++17, no platform deps
│   ├── include/synccore/          # public C API surface (stable ABI for both bridges)
│   ├── src/
│   │   ├── estimator/             # Kalman filter, drift model
│   │   ├── policy/                # correction policy, deadband, self-hearing guard
│   │   ├── aec/                   # WebRTC AEC3 wrapper, reference synthesis
│   │   ├── correlate/             # GCC-PHAT, chirp calibration, latency-
│   │   │                          #   residual referee (ported from
│   │   │                          #   lag_analyzer.cpp), shared kissfft
│   │   │                          #   alloc/pad/fwd/inv helper
│   │   └── clock/                 # monotonic timebase, latency bookkeeping
│   ├── third_party/               # webrtc-apm, kissfft (vendored, pinned)
│   └── tests/                     # desktop unit tests w/ recorded audio fixtures
├── ios/
│   └── JoinTheParty/
│       ├── App/                   # SwiftUI app, session screen, nudge wheel
│       ├── Audio/                 # AVAudioEngine capture, route observation
│       ├── Recognition/           # ShazamKit provider
│       ├── Spotify/               # App Remote controller, PKCE auth
│       └── Bridge/                # Swift ↔ SyncCore interop
├── android/
│   └── app/src/main/
│       ├── java/.../JoinThePartyApplication.kt  # Application subclass; anchors SessionGraph
│       ├── java/.../ui/           # Compose screens, nudge wheel, SessionViewModel
│       ├── java/.../audio/        # Oboe capture, route observation
│       ├── java/.../session/      # SessionGraph (process-scoped session owner)
│       ├── java/.../service/      # SessionForegroundService, notification
│       ├── java/.../recognition/  # ShazamKit AAR provider (ACRCloud fallback)
│       ├── java/.../spotify/      # App Remote controller, PKCE auth
│       └── cpp/                   # JNI bridge to SyncCore
├── backend/                       # thin service: token vending (Shazam/Spotify secrets),
│   └── ...                        # ISRC→URI cache; nothing latency-critical
├── tools/
│   ├── fixtures/                  # captured beach/party recordings for core/tests
│   └── latency-bench/             # scripted end-to-end latency measurement rig
└── docs/
```

---

## 13. Risks & Open Questions (carry into next phase)

1. **Spotify Premium + installed-app requirement** — hard gate; onboarding must detect and explain both.
2. **App Remote seek granularity/jitter** — the whole design assumes it; validate real-world seek settle-time distributions early with `tools/latency-bench`.
3. **ShazamKit Android token/quota terms** — confirm commercial usage terms for the Android AAR before launch.
4. **AEC3 synthesized-reference quality** — the §7 approach needs empirical validation; the recognition-side guard is the safety net if it underdelivers.
5. **Version mismatch (Shazam catalog audio vs Spotify master)** — ISRC matching minimizes it; the iterative loop absorbs the residual, but quantify typical residuals during testing.
6. **Backgrounded Spotify consent** — an App Remote reconnect that needs first-run consent while the app is backgrounded fails closed to `needsSpotify`; the session notification is the only recovery signal until the user returns to the foreground (§9, INT-06).
7. **Flat `ESTIMATED` default (150 ms) absorbs real per-device error.** v1 defers per-codec/per-device latency detection (considered and dropped — see §10) in favor of one generic value plus user-driven `BY_EAR` tone-match. Devices far from 150 ms (e.g., aptX LL, nearer 40 ms) get a materially wrong coarse aim until the user calibrates. Revisit a per-codec or per-device-name prior table if field telemetry shows `ESTIMATED` sessions taking meaningfully longer to converge.
