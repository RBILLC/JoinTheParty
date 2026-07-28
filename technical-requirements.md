# JoinTheParty — Technical Requirements & API Contracts

**Phase:** `/to-spec`
**Upstream:** `architecture-spec.md` (approved)
**Date:** 2026-07-21
**Status:** Ready for ticket breakdown

---

## 1. SyncCore ↔ Shell Boundary (C ABI)

### 1.1 Boundary rules

- SyncCore exposes a **pure C API** (`synccore.h`) — the stable ABI both Swift interop and JNI bind to. C++ never crosses the boundary.
- **Data in, events out.** Shells push timestamped data (audio, recognition fixes, player states); SyncCore emits events via a single registered callback. SyncCore never calls platform APIs.
- **Timebase:** every input carries a monotonic timestamp in ns (`CLOCK_MONOTONIC` / `mach_absolute_time` converted by the shell). SyncCore owns no clock reads.
- **Threading contract:**
  - `sc_push_capture` is **real-time safe**: lock-free SPSC ring buffer, no allocation, no logging. Callable only from the audio I/O thread.
  - All other `sc_*` calls are thread-safe, non-RT (internal mutex).
  - Events are delivered on SyncCore's single internal worker thread. **Shells must marshal to their main thread**; callback must return quickly (< 1 ms), no re-entrant `sc_*` calls from inside the callback.
- **Ownership:** all buffers passed in are copied or consumed before return; all strings/structs passed out are valid only for the duration of the callback.

### 1.2 Header contract (`core/include/synccore/synccore.h`)

```c
typedef struct sc_session sc_session_t;            // opaque

typedef enum { SC_OK = 0, SC_ERR_INVALID_ARG, SC_ERR_BAD_STATE,
               SC_ERR_NO_MEMORY, SC_ERR_UNSUPPORTED_RATE } sc_status_t;

typedef enum { SC_ROUTE_SPEAKER, SC_ROUTE_WIRED, SC_ROUTE_BLUETOOTH } sc_route_t;
typedef enum { SC_AEC_OFF, SC_AEC_PLATFORM_ONLY, SC_AEC_FULL } sc_aec_mode_t;

typedef struct {
    int32_t  sample_rate_hz;        // REQUIRED: 48000 (only supported rate, v1)
    int32_t  channels;              // REQUIRED: 1
    sc_route_t initial_route;
    int32_t  output_latency_prior_ms;   // per-route calibrated prior; -1 = default
    int32_t  command_latency_prior_ms;  // Spotify seek settle prior; -1 = default 250
} sc_config_t;

/* ---- Lifecycle ---- */
sc_status_t sc_create(const sc_config_t* cfg, sc_session_t** out);
void        sc_destroy(sc_session_t*);

/* ---- Real-time input (audio thread ONLY) ---- */
void sc_push_capture(sc_session_t*, const float* mono, int32_t frames,
                     uint64_t capture_mono_ns);

/* ---- Control-plane inputs (any non-RT thread) ---- */
typedef enum { SC_FIX_SHAZAMKIT, SC_FIX_ACRCLOUD } sc_fix_source_t;
typedef struct {
    sc_fix_source_t source;
    int64_t  match_offset_ms;         // position in catalog track at capture time
    uint64_t capture_mono_ns;         // when the matched audio was captured
    double   frequency_skew;          // 0.0 if unknown
    float    confidence;              // [0,1]; provider-normalized
} sc_recognition_fix_t;
sc_status_t sc_submit_recognition_fix(sc_session_t*, const sc_recognition_fix_t*);

typedef struct {
    int64_t  position_ms;             // Spotify-reported track position
    bool     is_paused;
    uint64_t received_mono_ns;        // when the shell received this player state
} sc_player_state_t;
sc_status_t sc_submit_player_state(sc_session_t*, const sc_player_state_t*);

sc_status_t sc_set_user_nudge_ms(sc_session_t*, int32_t nudge_ms);      // ±750 clamp
sc_status_t sc_set_output_route(sc_session_t*, sc_route_t, int32_t latency_prior_ms);
sc_status_t sc_set_aec_mode(sc_session_t*, sc_aec_mode_t);
sc_status_t sc_notify_seek_issued(sc_session_t*, int64_t target_ms,
                                  uint64_t issued_mono_ns);             // suppresses fixes during settle
sc_status_t sc_notify_local_playback(sc_session_t*, int64_t commanded_position_ms);
                                  // arms the self-hearing guard (spec §7.3)

/* ---- AEC reference (synthesized; non-RT thread, chunked) ---- */
sc_status_t sc_push_reference(sc_session_t*, const float* mono, int32_t frames,
                              int64_t track_position_ms);

/* ---- Calibration ---- */
sc_status_t sc_begin_calibration(sc_session_t*);   // emits SC_EVT_CALIBRATION_* events
sc_status_t sc_cancel_calibration(sc_session_t*);

/* ---- Events out ---- */
typedef enum {
    SC_EVT_SYNC_ESTIMATE,      // payload: sc_evt_sync_estimate_t
    SC_EVT_CORRECTION,         // payload: sc_evt_correction_t  (shell must seek)
    SC_EVT_REQUEST_FIX,        // SyncCore wants a fresh recognition pass now
    SC_EVT_FIX_REJECTED,       // self-hearing or low confidence; payload: reason
    SC_EVT_TRACK_LOST,         // error ≥ 2 s or fixes stopped matching
    SC_EVT_CALIBRATION_RESULT, // payload: measured chain latency ms + validity
} sc_event_type_t;

typedef struct {
    double  error_ms;          // + = local ahead of external
    double  drift_ppm;
    float   confidence;        // [0,1]
    bool    converged;         // 3 consecutive fixes inside deadband
    uint64_t last_fix_mono_ns;
} sc_evt_sync_estimate_t;

typedef struct { int64_t seek_to_ms; } sc_evt_correction_t;

typedef void (*sc_event_cb)(sc_event_type_t, const void* payload, void* user_data);
sc_status_t sc_set_event_callback(sc_session_t*, sc_event_cb, void* user_data);
```

**Contract notes (ticket-relevant):**
- SyncCore never seeks. It emits `SC_EVT_CORRECTION`; the shell executes it via App Remote and MUST call `sc_notify_seek_issued` so the estimator suppresses measurements during the ~3 s settle window (spec §6.2).
- `SC_EVT_REQUEST_FIX` drives the adaptive measurement cadence (8–12 s, stretched after convergence). Shells own the recognition session; SyncCore only schedules.
- `SC_EVT_SYNC_ESTIMATE` is emitted at most 15 Hz (interpolated between fixes using the drift model) — sized for meter animation, not for control decisions.

---

## 2. UI State Management

### 2.1 Shared model (mirrored per platform, single source of truth per shell)

```
SessionPhase = idle | listening | matching | aiming | converging | locked
             | drifting | lost | needsSpotify | needsPremium | error(code)

TrackInfo   = { spotifyUri, isrc, title, artist, artworkUrl, durationMs }

SyncState   = {
  phase: SessionPhase
  track: TrackInfo?
  nudgeMs: Int                  // committed value (per-route persisted)
  outputRoute: { type, name }   // e.g. (bluetooth, "AirPods Pro")
  lastFixAgeSec: Double
  aecActive: Bool
}

MeterFrame  = { errorMs: Double, driftPpm: Double, confidence: Float, converged: Bool }
```

**Requirement — two streams, not one.** `SyncState` (low-frequency, drives layout/navigation) and `MeterFrame` (≤15 Hz, drives the sync meter + wheel readout) are separate observable streams. Meter updates MUST NOT trigger recomposition/re-render of the session screen — only of the meter canvas.

**Requirement — input level, a third signal that's never gated on a fix.** `SC_EVT_SYNC_ESTIMATE` doesn't fire until the first recognition fix lands, so the meter stream is dormant through all of `listening`/`matching` — exactly the phases where the user is waiting to learn whether the mic can hear anything (docs/ux-audit-2026-07.md #8; drives a mic-reactive treatment of the phase word).
- **`sc_status_t sc_get_input_level(sc_session_t*, float* out_level)` — a polled getter, not a new event.** The worker's event channel is estimate-driven; making it emit before a fix exists would special-case every consumer. A getter needs no such gating and reports silence for free when capture is stopped or the session is idle — nothing to suppress.
- **Units:** normalized `0..1` smoothed level, not dBFS — the shell drives a visual treatment directly off the value, so `0..1` needs no log/clamp step on the far side. Ballistics: attack ~10 ms / release ~300 ms exponential envelope so the UI doesn't jitter per-sample.
- **Computed in the worker**, alongside the existing post-AEC history append (`append_history`, called right after `aec.process_capture`, synccore.cpp:210/214) — same buffer, no new tap, no RT-thread involvement. Written into a `std::atomic<float>` (relaxed store); `sc_get_input_level` does a relaxed load — no lock, no allocation, safe to poll from any thread. Never touches the RT audio callback or ring buffer.
- **Independent of calibration and the estimator** — valid whenever capture is running: before the first fix, during calibration, before and after lock.
- **Kotlin seam:** `SyncEngine.inputLevel(): Flow<Float>`, polled at ≤15 Hz — explicitly part of the high-frequency stream family alongside `MeterFrame` (same §2.1 rule), never folded into `SyncState`, never observed by the session screen root.

### 2.2 iOS (SwiftUI)

- `SessionStore`: `@Observable` (Observation framework), `@MainActor`. Owns the `SessionPhase` state machine; sole writer of `SyncState`.
- SyncCore callback → `AsyncStream<SCEvent>` (continuation buffered `.bufferingNewest(8)`) → consumed by a store task on the main actor.
- `MeterFrame` bypasses the store: dedicated `AsyncStream` consumed inside a `SyncMeterView` drawing with `Canvas` + `TimelineView(.animation)`; store only reads `converged` for phase transitions.
- Nudge wheel: gesture-local `@State` for the live wheel angle (optimistic display); commit debounced 400 ms → `sc_set_user_nudge_ms` + persist to `UserDefaults` keyed by route ID. Haptics via `UIImpactFeedbackGenerator` per 5 ms detent.

### 2.3 Android (Compose)

- `SessionViewModel`: exposes `StateFlow<SyncState>`; SyncCore JNI callback → `callbackFlow` → `flowOn(Dispatchers.Default)` → state reduced on `viewModelScope`.
- Meter: separate `Flow<MeterFrame>` with `conflate()`; collected only inside the meter composable via `collectAsStateWithLifecycle`, drawn in `Canvas`; wheel readout uses the same flow.
- Nudge persistence: Proto DataStore, map `routeId → nudgeMs`. Debounce via `snapshotFlow` on wheel value → `debounce(400)`.
- Recomposition guard (acceptance criterion): scrolling/animating the meter at 15 Hz causes **zero** recompositions of the session screen root (verify with Layout Inspector recomposition counts).

### 2.4 State machine (authoritative transitions, both shells)

```
idle → listening            user taps Join / mic permission granted
listening → matching        first audio buffered to recognizer
matching → aiming           fix accepted + ISRC→URI resolved; play+seek issued
aiming → converging         first post-seek player state received
converging → locked         SC_EVT_SYNC_ESTIMATE.converged == true
locked → drifting           estimate leaves deadband (converged false)
drifting → locked           re-converged (auto micro-seek path, no UI action)
any → lost                  SC_EVT_TRACK_LOST → auto-restart listening (max 3, then error)
any → needsSpotify/needsPremium   detected at session start or App Remote connect failure
```

### 2.5 Session lifetime & foreground service (Android, INT-06)

- **`SessionGraph`** (`session/SessionGraph.kt`, process-scoped, lazily initialized, anchored in a new `JoinThePartyApplication`): owns SyncCore, `RecognitionProvider`, `HttpBackendClient`, `AudioTrackChirpPlayer`, `AppRemoteSpotifyController`, `NudgeStore`, `AudioRouteObserver`, and a `CoroutineScope(SupervisorJob() + Dispatchers.Default)` — the session's lifetime anchor, replacing `viewModelScope`. Built once per process. **Single-owner rule:** `SessionGraph` is the only caller of `engine.close()`, invoked only once the phase reaches a terminal state (`idle`/`error`, not transient `lost`) **and** `SessionForegroundService` has stopped.
- **`SessionViewModel`** is re-scoped, not rewritten: same state machine, same tests; takes its scope from `SessionGraph` instead of `androidx.lifecycle.ViewModel` (no more `onCleared` → `engine.close()`), and is itself held by `SessionGraph` so Activity recreation reattaches to the live instance. `MainActivity` stops using `by viewModels { Factory }`.
- **`SessionForegroundService`** (`service/SessionForegroundService.kt`, `foregroundServiceType="microphone"`): owns foreground lifetime + notification only, never builds or holds the graph.
  - Start: `startForegroundService`, triggered from `startListening` when phase leaves `idle` (always called while the app is foregrounded — mic-type FGS cannot start from background on API 34+).
  - Stop: `stopSelf` when phase returns to `idle` or a terminal `error`.
  - `android:stopWithTask="false"` — task swipe must not kill an active session; the notification's Stop action is the intended exit.
- **Notification** (one channel `"session"`, `IMPORTANCE_LOW`, silent), built with `NotificationCompat` (already available via `androidx.core.ktx` — no new dependency). Driven by collecting `SessionGraph`'s `SyncState` inside the service's own `CoroutineScope(SupervisorJob() + Dispatchers.Default)` (plain `Service`, not `LifecycleService` — a manual scope avoids pulling in `lifecycle-service` for one collector), throttled to phase changes only, never per-second position:

  ```
  idle/listening                    → "Listening for a track…"
  matching                          → "Matching…"
  aiming/converging                 → "Syncing — <title> · <artist>"
  locked                            → "Synced — <title> · <artist>"
  drifting                          → "Re-syncing — <title> · <artist>"
  lost                              → "Lost the track — retrying"
  needsSpotify/needsPremium/error   → "Action needed — open JoinTheParty"
  ```

  Stop action: notification `PendingIntent` → service `ACTION_STOP` intent → `SessionViewModel.reset()`.
- **Permission matrix:**

  | Permission | Requested by | API level | Denial behavior |
  |---|---|---|---|
  | `RECORD_AUDIO` | MainActivity (`registerForActivityResult`, unchanged) | all | session cannot start |
  | `POST_NOTIFICATIONS` | MainActivity, requested alongside `RECORD_AUDIO` | 33+ | non-fatal — FGS still runs, notification suppressed |
  | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` | manifest only (install-time) | 28+ / 34+ | n/a |

  No `WAKE_LOCK`: the Oboe native audio callback plus FGS priority are sufficient. The keep-screen-on workaround this replaces is removed.
- **`AppRemoteSpotifyController.activityContext`** moves from `onCreate`/`onDestroy` to `onStart`/`onStop` — set only while the Activity can actually render App Remote's consent UI. Accepted limitation: an App Remote reconnect that needs consent while backgrounded fails closed to `needsSpotify`; the notification's "Action needed" copy is the recovery path.
- **`AudioRouteObserver`** moves to `SessionGraph` ownership (`applicationContext`), started with the session rather than the Activity.
- Unaffected: the C ABI (§1) and state machine transitions (§2.4) — SyncCore never learns about Android lifecycles.

### 2.6 Per-device calibration profiles

**Scope.** Calibration establishes `output_chain_latency` (arch §6.1) for the *playback* path only. Recognition reads the mic, not the speaker, so an uncalibrated device never blocks or degrades song identification — only the initial seek aim and the referee's residual tracking.

**Route attribution — captured at measurement start, not completion (correctness).** A calibration result (chirp round-trip or tone-match dial-in) is attributed to the `routeId`/`routeName`/`routeClass` that was connected when the measurement *began* — `startCalibration()`/`startByEarCalibration()` — not whatever route happens to be connected when the result lands. The shell must snapshot the route at start and thread that snapshot through to completion (`onCalibrationResult()`/`commitByEar()`); it must never re-read the live route at completion time to decide what the result belongs to.

If the active route changes while a measurement is in flight (chirp armed-and-playing, or tone-match Running), the in-flight measurement is **invalidated**, not relabelled against the new route: the running calibration cancels automatically (the same effect as `cancelCalibration()`/`cancelByEarCalibration()`), the sheet returns to Idle scoped to the newly-connected route, and the user sees one line of copy naming what happened ("Device changed — calibration cancelled.") rather than a silent reset that reads as a stuck button. No `CalibrationProfile` is written, for either route, from an invalidated attempt.

This applies symmetrically to the first-contact gate (below): `acceptFirstContactGate()` must gain the same route-staleness guard `declineFirstContactGate()` already has. If the route that raised the gate is no longer the connected route by the time the user taps "Calibrate now," the gate is dismissed as stale rather than starting a measurement against the new, unrelated device.

**Method taxonomy (`CalibrationProfile.method`):**

| Method | Mechanism | When |
|---|---|---|
| `MEASURED` | Acoustic chirp round-trip (`sc_begin_calibration`, GCC-PHAT) | Speaker always; wired/Bluetooth attempted first |
| `BY_EAR` | Tone-match calibration, or a promoted wheel trim (below) | Wired/Bluetooth when the chirp goes undetected; always available as a fallback |
| `ESTIMATED` | Single generic default, no measurement | User declines the first-contact gate entirely |

- **No device-class permission or lookup.** Wired and Bluetooth routes are ambiguous (could be a speaker or headphones) but the guided flow doesn't try to classify them — it attempts `MEASURED` unconditionally. `ChirpDetector`'s existing 8 s arm timeout with no detection *is* the "this is headphones" signal, and auto-transitions the flow to `BY_EAR`. `SC_ROUTE_SPEAKER` always attempts `MEASURED` (mic guaranteed to hear the phone's own speaker).
- **`ESTIMATED` default: 150 ms**, applied to `output_latency_prior_ms` when the user cancels calibration. Centered on the common case (Bluetooth SBC/AAC and the deep-buffer speaker path), one value for every route class — v1 does not attempt per-codec detection (considered and dropped, see arch §10 / §13.7). Labelled "not yet calibrated" in the UI, not a measurement.

**Tone-match (`BY_EAR`) mechanism.** The app plays a short periodic tone through the active route (same fixed deep-buffer/stream/stereo/44.1 kHz transport as the chirp fix below — a wrong-path tone gives a wrong-path offset, same failure mode as the original chirp bug) while showing a synchronized visual beat. The user **adjusts an offset control until the heard tone coincides with the seen beat**; the dialled value becomes `latencyMs`.
- **Adjust-until-aligned, not tap-along, by design.** Tap-along measures the user's motor-response latency (~50–100 ms) stacked on the audio latency being measured — a second unknown needing its own calibration. Perceptual alignment has no motor-response term, so it's a materially cleaner estimate.
- **Accuracy bound: ±30 ms**, human audio/visual alignment tolerance (asymmetric — lag forgiven more readily than lead), stated as the method's expected accuracy, not hidden as false precision. One display-frame (~16 ms) is a known, accepted systematic term on top.
- Available on any route, not just headphones/wired/Bluetooth — an explicit alternative for a user who distrusts the `MEASURED` chirp result.

**Chirp path fix (correctness bug).** `AudioTrackChirpPlayer` currently plays `MODE_STATIC` mono 48 kHz — Android's fast-mixer path, a different route than Spotify's own playback (observed `FLAG_DEEP_BUFFER`, stereo, 44.1 kHz; field-test-7 measured 207 ms acoustic vs. 3 ms engine-reported). Fix: request `PERFORMANCE_MODE_POWER_SAVING`, `CONTENT_TYPE_MUSIC`, stereo, 44.1 kHz, `MODE_STREAM`, large buffer — the same deep-buffer path Spotify uses. Chirp waveform (f0/f1/duration/fades) is unchanged, so the correlator's reference still matches; only the transport changes. A calibration number from the wrong path is worse than no calibration — it is confidently wrong.

**Referee (verifier, not a servo).** Port `analyze_window`/`next_pow2` from `core/tools/lag_analyzer.cpp` into a new module under `core/src/correlate/`; factor the kissfft alloc/pad/forward/inverse pattern duplicated between `correlate.cpp` and the ported code into one shared internal FFT helper (no shared helper exists today).
- **Single-buffer autocorrelation, not a reference cross-correlation.** `analyze_window(const float* x, size_t n, int rate, double min_lag_ms, double max_lag_ms)` takes exactly one buffer — it is what `lag_analyzer` has used to grade every field test. The mic hears two copies of the same song during speaker/BT-speaker playback (ours and the room's); autocorrelating that single capture produces a peak at the lag between them, which *is* the acoustic sync error a listener perceives. No reference signal is needed or used. (An earlier draft of this spec proposed cross-correlating against `sc_push_reference` — wrong on two counts: nothing calls `pushReference` in production, per `docs/aec-implementation-review.md`'s open-follow-up list, and it would require a decoded copy of Spotify's audio, which is exactly what we don't have and the reason AEC is a passthrough stub.)
- New C ABI: `sc_status_t sc_sample_latency_residual(sc_session_t*)` — non-RT. Runs the ported `analyze_window` over `sc_copy_recent_capture`'s 12 s post-AEC buffer, `min_lag_ms=40, max_lag_ms=2500` (the field-proven bounds from `lag_analyzer`'s CLI defaults). **The 2500 ms ceiling must not be widened:** at 4000 ms the analyzer locks onto harmonics of the music's own periodicity, producing spurious multi-second readings (`docs/sync-test-results.md`). No new audio is captured or played. Emits `SC_EVT_LATENCY_RESIDUAL { int32_t residual_ms; float peak_ratio; bool valid; }`.
- **Gating (in SyncCore):** `valid=false` unless `SC_EVT_SYNC_ESTIMATE.converged` is currently true (LOCKED) and `peak_ratio > 4.0` (mirrors `lag_analyzer`'s own `found` rule). `sc_set_aec_mode(SC_AEC_OFF)` for the sampled window, restored after — AEC would otherwise cancel the very echo of our own output the residual measures (a documented no-op today against the AEC stub; correct once the real APM lands).
- **Attribution argument.** While locked, the seek target is known-accurate, so the position error is ~0; any acoustic gap the autocorrelation finds between our output and the room's is therefore attributable to `output_chain_latency`, not to estimator position error. Sampling while unconverged would conflate the two and corrupt the profile.
- **`peak_ratio` is a first-pass filter, NOT the safety mechanism.** Corrected during CAL-02/CAL-03 implementation, and the correction matters: `peak_ratio` is `max/mean` of the autocorrelation over the whole search range, which spans ~118k lags at 48 kHz. The maximum of noise over N samples grows like `sqrt(2 ln N)` ≈ 4.8σ while `mean|ac|` ≈ 0.8σ, so a **single source with no second copy present at all scores around 6** — comfortably past the 4.0 threshold. It is an extreme-value statistic over the window, not evidence that two copies exist. `core/tests/test_lag_window.cpp::test_single_source_lag_does_not_reproduce` pins this behaviour.
- **The ≥3-window agreement rule is what actually protects the referee.** A spurious peak lands at an *arbitrary* lag, different for every window, so requiring several windows to agree within a tolerance rejects it — whereas a genuine second copy reports the same lag every time. Any change that weakens the agreement rule reintroduces the false-positive path, regardless of what `peak_ratio` is doing.
- **Eligibility falls out of the signal itself.** Headphones put no copy of our audio into the mic, so no consistent second peak exists and the agreement rule discards the route's windows. `acousticallyReachable` (below) is a cached optimisation to skip sampling headphone routes early, not the safety mechanism.
- **A stopped or changed room source self-invalidates** for the same reason: with only one source there is no reproducible lag to agree on. Field testing is the origin of this concern — a low-lag reading with only ONE source playing is meaningless reverb, not sync (`docs/sync-test-results.md`'s ≈85 ms reverb floor with nothing playing) — and it is the agreement rule, not the lag value or the ratio, that keeps the referee from recording it.
- **Aggregation is shell-side** (SyncCore stays stateless about profiles): the shell calls `sc_sample_latency_residual` periodically while locked and requires agreement across **≥3 valid windows** before writing one sample into the profile's ring. The referee never adjusts the live `output_latency_prior_ms`; it only appends samples and, when a sample's residual exceeds ±50 ms of the profile's current `latencyMs`, sets `drifted=true` so the UI can prompt a redo.

**Profile record** replaces the flat `outlatency:<routeId>` Int key (left orphaned — precedent is no migration). New `stringPreferencesKey("calibration_profile:<routeId>")`, JSON via `gson` (already a dependency):

```
CalibrationProfile {
  schemaVersion: Int             // = 1
  routeId, routeClass, deviceName: String
  method: MEASURED | BY_EAR | ESTIMATED
  latencyMs: Int
  confidence: Float              // [0,1]
  sampleCount: Int
  acousticallyReachable: Boolean // true once a chirp has ever been detected on this routeId; lets the
                                  //   shell skip sampling headphone routes early — a cached optimisation,
                                  //   not the safety mechanism (that is the ≥3-window agreement rule)
  createdAtMs, updatedAtMs: Long
  refereeSamples: [{ residualMs: Int, atMs: Long }]  // bounded ring, cap 20
  drifted: Boolean
}
```
One JSON blob per route — a single atomic write. `NudgeStore`'s five independent Int keys can't be written atomically, and a partially-written profile would be indistinguishable from a valid one.

**Trim promotion.** ≥3 wheel-trim commits on the same routeId, all within ±25 ms of their median, `|median| > 30 ms` (above the 25 ms correction deadband, arch §6.1) → prompt "use this as your calibration for `<device>`?" — never adopt silently. Accept: fold the median into `latencyMs`, set `method = BY_EAR`, reset the wheel trim to 0 (keeps the wheel's centre meaningful). Decline: suppress the prompt for that routeId for a 7-day cooling-off period.

**Recalibration targeting.** "Calibrate again" (Device detail) targets the specific `routeId` whose profile is on screen — never "whatever route happens to be active." When the viewed device is **not** the connected route, the action is unavailable: the control renders disabled with a one-line reason ("Reconnect this device to recalibrate it") rather than either (a) silently no-opping — the shell already refuses to start a measurement against a mismatched routeId, but today gives the user no indication anything was refused — or (b) opening a guided-calibration flow titled with whatever *other* device is currently connected, which reads as recalibrating the device the user came here for. The device shelf's empty-state action ("Calibrate phone speaker") is held to the same honesty rule: it must either genuinely target the phone's built-in speaker route (switching to it first if something else is connected) before starting calibration, or be relabelled to describe what it actually does — it must never present a "phone speaker" label while starting a guided flow titled with the name of whatever Bluetooth or wired device is presently connected.

**First-contact gate.** Unknown routeId (no profile) at session start → guided calibration runs before playback starts (recognition proceeds unaffected). The flow attempts `MEASURED`; on wired/Bluetooth it auto-falls to `BY_EAR` on chirp-detection timeout. The user may cancel either flow → `ESTIMATED` (150 ms default) and the profile is written with `sampleCount=0` so the UI can re-offer calibration next session.

- **Gate copy must not pre-commit to a route class.** The prompt is shown before any measurement evidence exists, so its copy must stay route-neutral rather than branding the device as a speaker ("keeps everyone in sync on this speaker") or as headphones ("headphones can't be heard by the phone's mic") ahead of the chirp's own timeout-based classification, above — a Bluetooth speaker and a 3.5 mm cable into a PA are both ambiguous route classes the gate cannot resolve without running the chirp. Branch on the *measurement outcome* (chirp detected vs. timed out), never on `sc_route_t`. See ui-ux §6.5 for the corrected single-variant gate copy.
- **Staleness guard on accept, symmetric with decline.** See "Route attribution," above.

**Sheet lifetime & precedence.** The calibration/device-review sheet is state the session owns, not Compose state local to a screen that nothing outside it can close: a session-state change that invalidates the sheet's content must close it. Concretely, a phase transition out of the active-session group into "lost the room" or a concierge gate (Premium/Spotify-not-installed) must dismiss any open calibration/review sheet rather than leaving it open and interactive as an overlay on top of that state. The first-contact gate and the calibration/review sheet must never render simultaneously — they are mutually exclusive, with the gate taking precedence: if a gate becomes eligible while the sheet is open, the sheet closes; if the sheet is opened while a gate is pending, the gate must be resolved (accepted or declined) before the sheet is allowed to open.

**Entry points.** The calibration/device-review entry point (ui-ux §4's single quiet entry point) must be reachable outside an active session, not only from it — calibrating a device before joining a party, and reviewing known devices between sessions, are both legitimate uses this system is meant to support; an idle-screen-only Join/Connect-Spotify surface with no calibration access does not satisfy that.

**CaliperScale accessibility contract.** Because `BY_EAR` (tone-match) is the *only* calibration path ever offered on a route that can't be measured acoustically (Method taxonomy, above) — there is no alternative for a screen-reader user on such a route — the caliper scale's Input mode (the tone-match drag control) is a functional accessibility requirement, not a nicety: it must expose its current value as an accessibility value/state description in ms, and it must be operable to a committed value without performing a drag gesture (e.g. accessibility increment/decrement actions, one step each). Read-only (shelf/detail) instances of the caliper expose their value the same way, read-only.

**Connected-state encoding.** Which known device is the currently-connected one must not be conveyed by hairline colour alone (`brass` vs. `ink2`) on the shelf/detail caliper lines. See ui-ux §6.5 for the required accompanying text encoding — provenance already carries three redundant encodings (word, tick count, stroke style); connection state must carry more than one too.

**Shelf ordering.** The calibration profile store's "all known devices" read must return a deterministic order — most-recently-updated first — not raw, unordered map/preference iteration; this is the store's contract (it has no notion of "connected," so it cannot itself prioritize a route) and gives every caller the same stable base order for free. The shell layers connected-first on top, at the point where `connectedRouteId` is known (opening the device shelf): the connected device's profile, if present, is moved to the front of the store's already-deterministic list. Net effect for the UI: connected device first, then the rest by most-recently-updated, stable across openings.

**Unchanged:** the state machine (§2.4) and the C ABI's "SyncCore never reads clocks" rule — the referee is a measurement consumer of existing capture/reference data, not a new control path.

---

## 3. Authentication & Token Flows

### 3.1 Spotify — OAuth 2.0 Authorization Code + PKCE (no client secret in app)

1. Generate `code_verifier` (43–128 chars, CSPRNG) → `code_challenge = BASE64URL(SHA256(verifier))`.
2. Launch authorize URL in `ASWebAuthenticationSession` (iOS) / Custom Tabs (Android):
   `https://accounts.spotify.com/authorize?client_id=…&response_type=code&redirect_uri=jointheparty://callback&code_challenge_method=S256&code_challenge=…&scope=app-remote-control user-read-playback-state user-modify-playback-state`
3. Exchange code at `https://accounts.spotify.com/api/token` with `code_verifier` → `{access_token (1 h), refresh_token}`.
4. Store tokens: iOS Keychain (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`); Android Keystore-backed `EncryptedSharedPreferences`.
5. Refresh: proactive at < 5 min remaining; PKCE refresh returns a **rotated** refresh_token — always overwrite stored value.
6. **App Remote connect** (separate from Web API token): iOS `SPTAppRemote` with `authorizeAndPlayURI` to wake the Spotify app; Android `SpotifyAppRemote.connect` (`showAuthView=true` first run). Handle `CouldNotFindSpotifyApp` → `needsSpotify`; user without Premium → seek rejected → `needsPremium`.
7. Precondition checks at session start: Spotify installed (canOpenURL / package query `com.spotify.music` — requires `<queries>` entry in Android manifest, `LSApplicationQueriesSchemes` on iOS).

**ISRC → URI mapping is a backend concern:** the app calls `GET /v1/track-map?isrc=…`; the backend queries Spotify Web API `search?q=isrc:<code>&type=track` using its own **client-credentials** token (secret lives server-side only) and caches results (TTL 30 d). The app's user token is never used for search — keeps user-token scope minimal and mapping cacheable across users.

### 3.2 ShazamKit

- **iOS:** no token. Requires the ShazamKit app service enabled on the App ID + `NSMicrophoneUsageDescription`. `SHManagedSession` / `SHSession` with default catalog.
- **Android:** requires an Apple **Developer Token** (ES256 JWT signed with an Apple Developer private key — same mechanism as MusicKit tokens). The signing key MUST stay server-side:
  1. App calls `POST /v1/tokens/shazam` (authenticated by app attestation — Play Integrity / App Attest).
  2. Backend mints JWT: `alg=ES256`, `kid=<key id>`, `iss=<team id>`, TTL **24 h** (Apple allows up to 6 months; we vend short).
  3. App caches in memory + `EncryptedSharedPreferences`, refreshes on 401/`InvalidToken` or expiry−1 h.
- Rate/quota: ShazamKit Android has request quotas per developer account — recognizer must reuse one session per sync session and respect SyncCore's `SC_EVT_REQUEST_FIX` cadence (no free-running recognition loops). Confirm commercial terms (arch §13.3) before launch — **blocking ticket**.

### 3.3 Backend surface (thin, v1)

| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /v1/tokens/shazam` | Play Integrity / App Attest | Vend 24 h ShazamKit developer token (Android) |
| `GET /v1/track-map?isrc=` | none (rate-limited) | ISRC → Spotify URI via cached client-credentials search |

No user accounts in v1. No audio ever leaves the device except ShazamKit's own signature uploads.

---

## 4. Third-Party Dependencies

| Dependency | Version / pin strategy | License | Notes & risks |
|---|---|---|---|
| WebRTC Audio Processing (AEC3) | Vendored in `core/third_party`, pinned to a tagged release of the `webrtc-audio-processing` extraction (avoid depending on full libwebrtc checkout — multi-GB, churny) | BSD-3 | API is not stable across milestones; upgrades are deliberate, tested against fixture suite. Build with CMake for iOS/Android/desktop. |
| KissFFT | Vendored, pinned tag (131.x) | BSD-3 | Tiny, stable; used by GCC-PHAT + chirp correlator. |
| Spotify iOS SDK (App Remote) | SPM/binary framework, pin exact release; track GitHub releases | Apache-2.0 | Distributed as XCFramework; verify bitcode/arch coverage per Xcode version. Seek is Premium-only — no API change expected but ToS review each major. |
| Spotify Android App Remote + Auth libs | AAR, pin exact version in Gradle version catalog | Apache-2.0 | App Remote and Auth are separate artifacts; keep both pinned together. |
| ShazamKit (iOS) | OS framework — floor **iOS 17** (v1 target), no dependency to pin | Apple SDK | `SHManagedSession` requires iOS 17; feature-flag if floor drops to 16. |
| ShazamKit (Android) | Apple-distributed AAR, pinned in version catalog; **minSdk 24+** | Apple ToS | Not on Maven Central — vendor the AAR in-repo; verify redistribution terms (§3.2 blocking ticket). |
| Oboe | Gradle `com.google.oboe:oboe`, pin 1.9.x | Apache-2.0 | AAudio backend; exclusive/low-latency stream with fallback to shared. |
| Swift toolchain | Xcode 16.x, Swift 5.10+, Swift↔C++ interop enabled for Bridge target only | — | C interop is the ABI; C++ interop used only inside the bridge module. |
| Kotlin / Compose | Kotlin 2.x, Compose BOM pinned, AGP per version catalog | — | JNI bridge built via CMake + NDK **r27 pinned** (NDK drift breaks reproducible SyncCore builds). |
| CMake | ≥ 3.28, single `core/CMakeLists.txt` consumed by both mobile builds and desktop test build | — | One build definition; desktop test target runs fixture suite in CI. |

**Version policy:** every third-party is pinned exactly (version catalog / lockfiles / vendored tags). No floating ranges. SyncCore vendored deps upgrade only via PR that runs the desktop fixture-regression suite.

**INT-06 note:** no new dependency. `SessionForegroundService`'s notification uses `NotificationCompat` from `androidx.core.ktx` (already present); the service manages its own `CoroutineScope` rather than adding `androidx.lifecycle:lifecycle-service`.

**Calibration note (§2.6):** no new dependency and no new permission. Profile records serialize with `gson` (already present) into a `stringPreferencesKey`; the referee reuses the already-vendored KissFFT via the new shared FFT helper. v1 explicitly drops the A2DP-codec-based latency seed (would have required `BluetoothCodecStatus`, API 28+, and `BLUETOOTH_CONNECT`) in favor of a single generic `ESTIMATED` default plus user-driven `BY_EAR` tone-match — deferred, not cancelled (arch §13.7).

---

## 5. Ticket-Readiness Checklist (what the next phase decomposes)

1. SyncCore skeleton: session lifecycle, ring buffer, event pump, desktop test harness.
2. Estimator: Kalman filter + correction policy + settle-window suppression (pure C++, fixtures).
3. AEC integration: APM build, reference synthesis, self-hearing guard.
4. iOS shell: capture, ShazamKit provider, App Remote controller, bridge, SessionStore.
5. Android shell: Oboe capture, ShazamKit AAR provider, App Remote controller, JNI bridge, ViewModel.
6. UI: session screen, sync meter (two-stream rule), nudge wheel (detents, debounce, per-route persistence).
7. Auth: PKCE flow both platforms, token storage/refresh, backend token vendor + ISRC map service.
8. Calibration: chirp generator/correlator + per-route latency store.
9. **Blocking research ticket:** ShazamKit Android commercial terms + quota confirmation.
