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
- **Aggregation is shell-side** (SyncCore stays stateless about profiles): the shell calls `sc_sample_latency_residual` periodically while locked and requires agreement across **≥3 valid windows** before writing one sample into the profile's ring. The referee never adjusts the live `output_latency_prior_ms`; it only appends samples and maintains `drifted`. **Corrected (field test 8):** the residual is the *error*, not a re-measurement of the latency — while locked the position error is ~0, so the acoustic gap reads how much reality disagrees with the applied prior, and a healthy route reads near the room's reverb floor. An earlier draft compared the residual against `latencyMs`, which flagged a perfect 43 ms floor reading on a 153 ms profile as drift, live, while the listener confirmed sync. The rule is `drifted = |residual| > 50 ms`, evaluated per committed sample so it also CLEARS on recovery. Known limitation: the fixed threshold sits below the ~85 ms reverb floor of live-lier rooms and will need a floor-aware margin.

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

### 2.7 Correction control — dynamic deadband & persistence gating (CTL-02)

**Problem.** The Android shell runs `sc_config_t.deadband_ms = 350` (core default 25, `SessionGraph.kt`'s `ENGINE_DEADBAND_MS`), and field test 8 found the failure mode that width buys: stable, cross-instrument-agreeing residuals sit inside 350 ms forever while LOCKED. Billy Joel's Vienna locked at 33 s and held a constant ~300 ms echo for the rest of the cycle — engine 281 ms, mic 314 ms, ear ~250 ms, all three agreeing, zero corrections. Fleetwood Mac's Dreams locked at ~35 s and held a constant ~285 ms echo, also zero corrections. Both are the "deadband ceiling" named in the field-test-8 addendum. Uniformly lowering the deadband to 150 ms was tried live as the obvious fix and was worse: eight corrections in 77 s, drift estimate pegged, the loop chasing the song's own beat comb into an on-beat-but-3-beats-late hole (field-test-8-results.md's addendum; `SessionGraph.kt` lines 144–155 carries the same postmortem). The two failures bound the problem: a fixed threshold can be too wide (Vienna/Dreams) or too narrow (deadband-150) for the same knob, because "stable and corroborated" and "scattered and multimodal" are different residual shapes that one scalar can't tell apart.

**Design basis.** Donkers & Heemels's mixed event-trigger condition — a relative term plus a strictly-positive absolute floor ε (research-closed-loop-control.md §1a, Eq. 25) — proves that closed-loop stability (LMI feasibility, §V.C) depends only on the relative term; the floor affects only event count and ultimate-bound size. That licenses a second, corroboration-scaled gate layered *above* a fixed floor without reopening the stability question the deadband-150 experiment closed the hard way. NTP's step/slew discipline supplies the missing temporal half: from steady-state SYNC a single sample over STEPT does not step the clock, it moves to SPIK and waits — only an offset that *persists* past the WATCH stepout window earns the step (RFC 5905 Figure 28, research doc §1b). Liang et al.'s playout deadline is likewise not a fixed constant but derived from the order statistics of recently observed scatter (research doc §1f §IV.A, Eq. 5–8) — the threshold should widen or narrow with what the recent evidence actually looks like, not sit at one hand-tuned number for every condition. §5 items 1–2 of the research doc compose these three into the design below.

**Mechanism (all in `CorrectionPolicy`, core-only; no C ABI change, no shell change).** `CorrectionPolicy` gains a fixed-size ring of the last N (default 8) `est.error_ms` values, appended only from `on_estimate` calls where `est.valid && est.converged` and the policy is not settling. In `synccore.cpp` `on_estimate` is invoked once per accepted-fix decision, from the recognition-fix handling (`Command::Kind::kRecognitionFix`) after `estimator.on_fix` succeeds — so every ring entry is fresh fix evidence, never a coasted interpolation between fixes. The ring is cleared on `reset()`, on every emitted seek (a correction changes the operating point — post-seek residuals are a new cluster, not a continuation of the old one), and on any non-converged estimate arriving (loss of convergence invalidates the cluster's premise).

New `PolicyConfig` fields:

| Field | Default | Role |
|---|---|---|
| `confirm_min_fixes` | 3 | minimum ring occupancy before a cluster can be judged |
| `confirm_window_ns` | 20 s | minimum span the qualifying samples must cover |
| `confirm_agree_ms` | 60 | max deviation of any ring sample from the cluster mean |
| `confirm_floor_ms` | 125 | absolute floor ε; a cluster at or below it is healthy sync, never corrected |

**Persistence trigger.** While converged, when the ring holds ≥ `confirm_min_fixes` samples spanning ≥ `confirm_window_ns`, every one of those samples is within `confirm_agree_ms` of the cluster mean, the cluster's |mean| exceeds `confirm_floor_ms`, and `est.confidence ≥ min_confidence_to_correct` (the existing FT4 guard, unchanged) → emit exactly one correction, computed from the **cluster mean**, not the instantaneous `est.error_ms`, through the existing drift-centered seek-target formula in `on_estimate`. The ring is cleared immediately after. Every downstream mechanism — settle suppression, seek-ack, `awaiting_verify_` command-latency learning — is reused unmodified; the persistence trigger only changes what feeds the existing `action.seek_to_ms` computation, not how that computation or its aftermath works.

**Corroboration-hungry cadence.** While converged with a live ring cluster whose |mean| exceeds `confirm_floor_ms`, the fix-request cadence drops from `fix_interval_max_ns` (30 s) to `fix_interval_base_ns` (10 s), so `confirm_min_fixes` corroborating samples arrive in ~30 s instead of ~90 s. This is the same cadence value already used for the non-converged case — no new interval constant — and it is bounded: it only applies while an above-floor cluster is open, reverting to the normal converged cadence once the cluster clears (by firing, or by a seek/non-convergence reset).

**The instantaneous deadband is unchanged.** `deadband_ms` still gates every `on_estimate` call exactly as today — this mechanism adds a second, slower path alongside it, never replaces or lowers it. Per Heemels et al. §V.C, the shipped `deadband_ms` is the ε-only, σ=0 corner case of the general mixed condition (research doc §1a); this section adds the relative/corroboration term without ever letting the effective floor for a *single* fix drop below `deadband_ms`, and without ever letting the persistence path's own floor (`confirm_floor_ms`) reach zero.

**`confirm_floor_ms = 125` — a deliberate resolution, not a default.** RFC 5905 gives two different values for its own step threshold: Figure 27's parameter table lists 125 ms, Appendix A.5.5.6's reference pseudocode defines `STEPT .128` — an unresolved discrepancy inside the RFC itself (research doc §1b, and flagged as an open item in §4 item 6). This spec picks the table's 125 on purpose rather than leaving the ambiguity to whoever implements it first. Field evidence supports the choice with margin on both sides: field-test-8's healthy locks read engine −30 to −63 ms (Toto, Billy Joel — My Life), while the broken class reads 250–314 ms (Vienna, Dreams); 125 sits well clear of both clusters.

**`confirm_agree_ms = 60` — sized off the two failure shapes, not guessed.** The deadband-150 churn class chases the song's own beat comb, whose teeth sit roughly a beat period (~500 ms) apart; the Vienna/Dreams class is a single stable post-Kalman residual, constant to within tens of milliseconds across the whole cycle. 60 ms admits the latter and rejects the former by close to an order of magnitude — the same distinction Liang et al.'s scatter-adaptive threshold makes structurally (research doc §1f §IV.A), applied here as a fixed constant sized from measured scatter rather than a running order-statistic.

**Known limitation, stated rather than hidden.** No source retrieved in research-closed-loop-control.md answers whether a stable, cross-instrument-corroborated residual is a correctable sync error or an uncorrectable acoustic-path floor (research doc §4 item 4) — nothing in the six literatures models cross-instrument agreement as a signal, or an acoustic transmission path at all. This design does not resolve that question; it verifies its answer empirically. The existing post-settle verify fix (`awaiting_verify_`) reports whether the corrected residual actually shrank. If the residual is a real sync error, it lands near zero and the ring starts clean. If it is an acoustic-path floor, the correction doesn't remove it — the same offset re-accumulates in the ring and the persistence trigger re-fires. That repeated-fire pattern is itself diagnostic, but building a cap or monitor on top of it is CTL-03+ territory and out of scope here.

**Structurally inert at core defaults.** Core ships `deadband_ms = 25` against `confirm_floor_ms = 125` — the floor sits above the instantaneous deadband, so there is no residual class that is simultaneously outside the instantaneous deadband and below the confirm floor for the persistence trigger to act on. The mechanism only activates once a shell widens `deadband_ms` past the floor, as Android does at 350. This is intentional: the persistence path exists to recover accuracy a shell's own widened deadband gave up, not to change core's own default behavior.

**Scope note — a separate, later mechanism.** The FT8 song-2 overshoot class — a single conf-0.74 fix landing 1259 ms off and standing uncorrected because follow-up errors hid near the deadband — is a *large*-correction corroboration problem, not a stable-small-residual one, and is out of scope for this section. That failure belongs to the comb-ambiguity hypothesis-bank gating research doc §5 item 3 describes (mission item 2); this section's ring/persistence machinery is not a substitute for it and must not be stretched to cover it.

**Epoch rule.** All new state — the residual ring, the cluster bookkeeping — lives in `CorrectionPolicy` and is cleared by `reset()` alongside everything else the policy owns. It never carries across a track change or a session epoch, for the same reason the capture ring was fixed to reset per epoch (field-test-8-results.md): a fresh join must never judge its first residuals against a cluster the previous song or the previous session accumulated.

**Unchanged:** the state machine (§2.4), the C ABI, the estimator, and the Android shell's own logic are all untouched by this section — the mechanism is additive policy-internal state and a new emission rule inside `CorrectionPolicy::on_estimate`, nothing else. `SessionGraph.kt`'s `ENGINE_DEADBAND_MS` comment (lines 144–155) will be updated to point here once the mechanism lands, in place of its current forward reference to "CTL-02."

---

### 2.8 Correction control — comb-flatness score & large-correction corroboration (CTL-03)

**Problem.** Field test 8's song 2 took a single conf-0.74 fix 1259 ms off ("overcorrecting," the user's own word) and it stood uncorrected because the follow-up errors that would have exposed it hid near the deadband (field-test-8-results.md). §2.7's ring/persistence machinery is explicitly scoped away from this: it disambiguates *stable, small* residuals, not a *single large* one, and its scope note says so in terms. Billie Jean's harmonic churn — one fix reading 47.6 s off before an honest "Couldn't find the song" — is the companion evidence that a single autocorrelation peak can be one tooth of a flat comb, not a genuine second copy. This section closes both, at the two different layers where each actually lives.

**Part A — comb-flatness score in `analyze_window` (additive only).** `WindowLag` (`core/src/dsp/lag_window.h`) gains `double second_lag_ms = 0;` and `double comb_ratio = 0;`. `comb_ratio` is the best peak's autocorrelation value divided by the strongest peak found *outside* a ±20 ms exclusion neighborhood around the best lag — the exclusion keeps the best peak's own shoulder from scoring as its own competitor. By construction `comb_ratio ≥ 1` once a second candidate exists; ~1.0–1.5 reads as a flat comb of several near-equal teeth (the Billie Jean class), a high ratio reads as one unambiguous copy-lag. The scan is a second pass over the autocorrelation array `analyze_window` already computes — no new FFT, no new buffer.

The existing argmax, `peak_ratio` (max/mean — an extreme-value statistic over the whole search range, *not* evidence of a second copy present, per §2.6's `sqrt(2 ln N)` analysis and the standing field-corpus warning), and `found = peak_ratio > 4.0` are **byte-identical** in behavior: this is a wholly additive pass, satisfying `lag_window.h`'s header comment ("do not 'improve' the math here without re-running the field-test corpus") — nothing the corpus graded changes. `lag_analyzer`'s CLI (`core/tools/lag_analyzer.cpp`) gains a `comb_ratio` column alongside its existing `lag_ms,peak_ratio,confident` output so the field rig sees comb ambiguity live. No C ABI change: `SC_EVT_LATENCY_RESIDUAL`'s `{residual_ms, peak_ratio, valid}` shape is unchanged; wiring `comb_ratio` into referee validity is CTL-01's design space, noted here only as a forward pointer.

**Honesty note.** Recognition fixes (ACRCloud, via `sc_submit_recognition_fix`) do not flow through `analyze_window` at all — they arrive as `(match_offset_ms, frequency_skew, provider_confidence)`, with no access to a landmark scatterplot or a comb of candidate lags (research-offset-disambiguation.md §2a: "we are the *consumer* of a Wang-style engine... for the network fix path"). The comb score therefore has **no live correction-path consumer today**. It exists for the field rig's diagnostics now, and as the seeding/validity input the comb-ambiguity hypothesis bank (research-closed-loop-control.md §5 item 3) and CTL-01 will consume later. The runtime defense against the 1259 ms class is Part B, below.

**Part B — large-correction corroboration hold in `CorrectionPolicy`.** New `PolicyConfig` fields:

| Field | Default | Role |
|---|---|---|
| `large_correction_threshold_ms` | 1000.0 | at/above this magnitude (but below `lost_threshold_ms`), a proposed seek is never fired from one estimate alone |
| `large_corroborate_agree_ms` | 150.0 | max deviation between the pending error and the next fresh error to count as agreement |
| `large_pending_max_age_ns` | 30 s | pending record expires unfired if no corroborating fix arrives |

**Mechanism.** When the instantaneous path (existing `on_estimate` logic) would fire a seek with `|e| ≥ large_correction_threshold_ms`, the policy instead records a pending large correction `{error, timestamp}` and emits nothing; fix cadence tightens to `fix_interval_min_ns` (8 s, the existing erroring cadence — no new constant) so corroborating evidence arrives fast. Each `on_estimate` call is a fresh accepted fix (`synccore.cpp`'s `kRecognitionFix` handling calls `estimator.on_fix` before `on_estimate` ever runs), so the next call is genuinely new evidence, never a coasted interpolation. If that next error agrees with the pending one within `large_corroborate_agree_ms` **and** is still ≥ threshold, the seek fires — computed from the **fresh** error, not the stale pending one, through the existing `on_estimate` target formula. A disagreeing large error replaces the pending record (restart, not accumulate); an error that drops below threshold clears it outright. The record also clears on `reset()`, on any emitted seek (instantaneous or the hold's own), on track-lost, and on expiry past `large_pending_max_age_ns`.

`lost_threshold_ms` (2000) keeps absolute precedence, checked first exactly as today: track-lost still fires immediately off a single estimate. An error that large is a re-listen, not a seek to hold and verify — field-test-8's own `err=-5396ms conf=0.01`, printed the instant the end-of-track pause forced a fresh listen, is evidence for acting on it via the lost path rather than waiting on a second fix that may never come from the same phantom match.

**Rationale for 150 ms (not the ~50 ms first suggested).** `sc_config_t.deadband_ms`'s own comment (`synccore.h`) cites Field Test 2's measured single-fix recognition noise of ±100–150 ms. Two honest fixes of the same real jump will often differ by more than 50 ms on that noise floor alone, so a 50 ms gate would starve real large corrections indefinitely — a worse failure than the overshoot it exists to prevent. 150 ms admits honest corroborating pairs while still rejecting beat-comb teeth, which field-test-8 shows sitting roughly a beat period (~500 ms) apart. Flagged here as a deliberate deviation for field tuning, not a derived constant.

**Relationship to existing machinery.** The estimator's `outlier_gate_ms=1200`/`outlier_gate_max_p00=10000` gate (`estimator.h`) already corroborates large innovations — but only *when confident* (posterior std under 100 ms); at mid-uncertainty the gate is inactive by design ("post-reset first fixes always land") and a single large fix is accepted outright, which is structurally why the 1259 ms overshoot got through. This section's hold closes exactly that gap at the policy layer; the two are complementary, not redundant, and the estimator is untouched. NTP grounding: this is the SPIK-state discipline — "a single spike greater than the step threshold is always suppressed" (RFC 5905 Fig. 28, research-closed-loop-control.md §1b) — applied at the policy layer with a two-fix confirmation in place of NTP's wall-clock WATCH stepout, since we have no free-running clock to wait out. §2.7's persistence gate cannot interact with this hold: it only ever fires for sub-deadband residuals (at most the 350 ms shell deadband), far below the 1000 ms threshold here.

**Deliberate test change.** `core/tests/test_policy.cpp::test_track_lost_threshold`'s first `CHECK` — `make_est(1999.0)` at a bare policy expecting `kSeek` — pins exactly the pre-CTL-03 behavior this section changes: a single large estimate firing an immediate seek. That CHECK is updated, with a comment citing this section, to expect the corroboration hold instead (no action on the first 1999 ms estimate; a second, agreeing one fires it). The same class of pin exists one layer up: `test_synccore.cpp::test_correction_leads_by_recognition_age` asserted a single 1500 ms fix fires an immediate correction — its FT4 purpose (the recognition-age lead in the seek target) is preserved by corroborating with a second agreeing fix and asserting the lead math on the fix that fires. These two are the only existing-test modifications this spec authorizes.

**Epoch rule.** The pending-large-correction record lives in `CorrectionPolicy`, alongside the persistence ring, and is cleared by `reset()`.

**Unchanged:** the state machine (§2.4), the C ABI (`SC_EVT_LATENCY_RESIDUAL`'s shape included), the estimator (`outlier_gate_ms`/`outlier_gate_max_p00` untouched), `analyze_window`'s graded behavior (argmax, `peak_ratio`, `found` — all byte-identical), §2.7's persistence gate, and the Android shell are all untouched by this section.

---

### 2.9 Self-match defense — referee sentinel & active probe (CTL-01)

**Why the FT4 guard can't catch this.** The FT4 self-match guard (`synccore.cpp`'s `kRecognitionFix` handling) rejects fixes that break room continuity while landing on our own position — but field test 8 found the failure that guard structurally cannot see: after any room discontinuity (song change, stall, forced seek, an operator tap), our own audio *becomes* the continuous timeline. Continuity looks perfect, so the guard has nothing to reject; every telemetry layer self-confirms (engine "LOCKED" at −36 ms; the analyzer showing a fake floor once the true offset exceeds its 2.5 s window). Only two observables distinguish "synced" from "hearing ourselves": (1) the referee losing its second copy while the engine stays LOCKED, and (2) the response to a deliberate perturbation only our own output carries. FT8 measured both.

**Sentinel = agreement starvation, NOT `peak_ratio < 4`.** A critical correction to the naive design: `peak_ratio` is an extreme-value statistic (§2.6) — a single source with no second copy at all scores ≈6, comfortably past the 4.0 gate, so "peak_ratio dropped" cannot be the sentinel signal. The real signature is the *absence of reproducible agreement*: with the room stopped, per-window lags land at arbitrary values (reverb, ~85 ms floor class, §2.6); with the room on different material, windows go invalid. Both starve agreement rather than lowering `peak_ratio`. Mechanism, core-side, in `CorrectionPolicy`: a new `policy.on_referee_window(double lag_ms, bool valid, uint64_t now_ns)`, called from the `kSampleLatencyResidual` handler right where `sc_evt_latency_residual_t` is filled (`synccore.cpp` ~633–651), immediately after `dispatch(SC_EVT_LATENCY_RESIDUAL, ...)` so the feed is a pure additional consumer of the same result. The policy keeps a small ring (8) of recent windows and tracks `last_referee_agree_ns`, updated whenever any 3 of the ringed lags mutually agree within `referee_agree_ms` (50, matching §2.6's shell-side ±50 ms agreement convention). **Sentinel condition:** while converged continuously, ≥ `referee_starve_min_windows` (4) windows observed AND `now − last_referee_agree_ns ≥ referee_starve_ns` (45 s) → suspected self-match → probe requested. The shell's own §2.6 aggregation (the ≥3-valid-window commit into a `CalibrationProfile`) is untouched — this is a parallel, core-side consumer of the same per-window results, and the referee still never writes the live prior: §2.6's verifier-not-servo rule holds, because the sentinel's output is a *probe request*, not a prior adjustment.

**Second trigger — Wittenmark turn-off (research-closed-loop-control.md §5 item 4).** `on_estimate` only runs on accepted fixes, so a starving filter — one that keeps rejecting everything — never reaches the policy through it; the turn-off trigger must be time-driven instead: a new `policy.on_tick(const Estimate& est, uint64_t now_ns)` called from the worker's `tick()` (~synccore.cpp line 385), which already runs off capture-time progress independent of accepted fixes. Condition: `est.valid && est.confidence < min_confidence_to_correct` continuously for `probe_turnoff_dwell_ns` (20 s) with no accepted fix in that span → probe requested. This is the FT4-class episode (guard rejecting everything, confidence decayed to 0.19, policy coasting) generalized into a literature-grounded rule: fire the probe when passive learning has turned off, per Wittenmark's cautious/dual distinction (research doc §1d, §5 item 4).

**Probe request → `SC_EVT_ACTIVE_PROBE`.** Both triggers funnel through one rate limit: at most one probe per `probe_cooldown_ns` (120 s), never while a probe is outstanding, never while settling (`is_settling`), and never unless playback is live (`!is_paused` per the worker's last-known `sc_player_state_t` — the policy receives this as a bool alongside `on_tick`'s `Estimate`). A new event is appended at the **end** of `sc_event_type_t`, after `SC_EVT_LATENCY_RESIDUAL`: `SC_EVT_ACTIVE_PROBE`, payload `sc_evt_active_probe_t { int32_t pause_ms; }`, sourced from `PolicyConfig::probe_pause_ms` (default 200). The **shell** executes it: pause Spotify, wait `pause_ms`, resume, then echo a new ABI call `sc_status_t sc_notify_probe_executed(sc_session_t*)`, mirroring `sc_notify_seek_issued`'s echo pattern. The core stamps the probe epoch at the echo, not at emission — App Remote command latency is 100–500 ms and unknowable at emission time, the same reasoning `command_latency_ms` already encodes for seeks. If the shell can't execute (already paused, mid-calibration), it simply never echoes; the request expires after `probe_verdict_window_ns` and the cooldown still applies, so a silently-declined probe can't be retried in a tight loop.

**Verdict, in `CorrectionPolicy`, using the estimator's existing projections — the estimator itself is UNTOUCHED.** At echo: snapshot `probe_pre_error_ms` = the current filtered error. The pause freezes OUR content position for `pause_ms` while the room keeps advancing, so a genuinely room-tracking fix stream must read the error shifted by ≈ −`pause_ms` afterward; a self-match stream — which reports our own audible position back to us — reads an essentially unchanged error through the perturbation (FT8's own constant-error signature, now *forced* on demand instead of waited for). Rule: over the next `probe_verdict_window_ns` (20 s), take the mean shift of the first `probe_verdict_min_fixes` (2) post-echo estimates vs. the snapshot. Mean shift ≤ −pause_ms/2 → **genuine**: clear probe state; the probe deliberately introduced a known −pause_ms offset, and recovery flows entirely through the *existing* correction machinery — the instantaneous path if it exceeds the shell's deadband, otherwise §2.7's persistence gate (`probe_pause_ms`=200 exceeds the 125 ms `confirm_floor_ms`, so the gate clears it within ~3 corroborated fixes; this composition is deliberate, not incidental). Mean shift > −pause_ms/2 → **self-match confirmed**: the policy returns `kTrackLost` — reset, `SC_EVT_TRACK_LOST`, the shell's existing lost flow forces the pause → fresh re-listen that FT8 proved prints the truth (`sync err=-5396ms conf=0.01` the instant a forced re-listen ran). Fewer than `probe_verdict_min_fixes` fixes arriving in the window → inconclusive: clear and apply cooldown, no verdict claimed. **Seeks are suppressed while a probe is outstanding** (request → echo → verdict): a correction mid-verdict would contaminate the shift measurement; track-lost keeps its existing precedence throughout.

**Honest marginality note.** `probe_pause_ms=200` against Field Test 2's ±100–150 ms single-fix noise (§2.8's own citation) gives a decision boundary (100 ms) that sits inside the noise band for a single fix; the 2-fix mean tightens it to roughly ±106 ms — workable, but marginal. That is exactly why every probe constant below is a `PolicyConfig` field for field tuning: no literature formula exists for binary-cost probe magnitude (research-closed-loop-control.md §4 items 1–2 — Wittenmark's actual perturbation-signal formulas were paywalled, and probing cost here is a discrete near-fixed penalty, not the smoothly-scalable cost classical dual control assumes). 200 ms is the user's chosen starting point; raising toward 300–400 ms is the first knob if field trials show verdict flapping. Self-match streams help here: FT8 measured them eerily LOW-jitter (constant zEnd within ~24 ms across the discovery's four triggers), so the self-match side of the boundary is materially cleaner than the genuine side.

**New `PolicyConfig` fields:**

| Field | Default | Role |
|---|---|---|
| `probe_pause_ms` | 200 | duration of the deliberate playback pause the shell executes on `SC_EVT_ACTIVE_PROBE` |
| `probe_cooldown_ns` | 120 s | minimum spacing between probes, regardless of trigger or verdict |
| `probe_verdict_window_ns` | 20 s | span over which post-echo estimates are collected for the verdict |
| `probe_verdict_min_fixes` | 2 | minimum post-echo estimates required before a verdict (genuine/self-match) can be reached |
| `referee_agree_ms` | 50 | max deviation between ringed referee windows to count as mutual agreement |
| `referee_starve_min_windows` | 4 | minimum windows observed before starvation can be judged |
| `referee_starve_ns` | 45 s | time since `last_referee_agree_ns` that constitutes starvation |
| `probe_turnoff_dwell_ns` | 20 s | continuous sub-`min_confidence_to_correct` dwell that constitutes turn-off |

**ABI note.** `SC_EVT_ACTIVE_PROBE` is appended at the **end** of `sc_event_type_t` — existing values are unchanged, preserving binary compatibility for any shell code that switches on the enum by value. `sc_evt_active_probe_t` and `sc_notify_probe_executed` are additive; `synccore_abi_c_check` (`core/tests/`) gains coverage for both. Shell contract (Android): the JNI bridge maps the new event; `SessionViewModel` executes pause → `delay(pause_ms)` → resume via the Spotify controller and echoes `notifyProbeExecuted()`; it must NOT execute while already paused or mid-calibration — no echo means inconclusive by design, not a bug to work around. One probe is one audible ~200 ms gap — the binary-cost tradeoff research-closed-loop-control.md §3 discusses — and is rare by construction: the cooldown plus the two trigger gates (agreement starvation, confidence turn-off) both require sustained abnormal conditions before a probe fires at all.

**Epoch rule.** All sentinel/probe state — the referee ring, `last_referee_agree_ns`, the pending-probe record, the verdict snapshot, and the turn-off dwell timer — lives in `CorrectionPolicy` and is cleared by `reset()`, exactly like §2.7's persistence ring and §2.8's pending-large-correction record. A fresh join must never judge starvation or a turn-off dwell against a previous session's or previous song's accumulated state.

**Standing-warning compliance.** (1) Needs no reference PCM — the sentinel consumes the existing single-buffer-autocorrelation referee result, and the probe needs no reference signal at all, only a known perturbation to our own output. (2) Epoch-clean — all new state clears on `reset()`. (3) Does not use `peak_ratio > 4` as evidence — the sentinel fires on agreement starvation, never on the `peak_ratio` value. (4) No hypothesis bank touches this path — §2.8's comb-ambiguity direction and this section are independent; research-closed-loop-control.md §2(iii) is explicit that PDA-style hypothesis blending would make self-match *worse*, not better, since self-match "clutter" is self-correlated and anomalously clean, violating PDA's independence assumption. (5) The referee stays a verifier — the sentinel triggers a probe request, and never writes `output_latency_prior_ms` or any other live prior. (6) The eerily-constant-error heuristic FT8 observed passively is now an on-demand forced test (the probe) rather than something merely waited for.

**Unchanged:** the state machine (§2.4) — `TRACK_LOST` reuses the existing lost flow verbatim; the estimator; §2.6's shell-side referee aggregation into a `CalibrationProfile`; §2.7/§2.8's mechanisms (persistence gate, large-correction hold); and `analyze_window`'s graded behavior. This section adds a parallel core-side consumer of existing referee results, a time-driven tick hook, and one new outbound event — nothing about how any of those existing mechanisms compute or fire is touched.

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
