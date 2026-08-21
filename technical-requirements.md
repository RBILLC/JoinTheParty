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

### 2.10 OSS beat-period tracker (autocorrelation tempogram)

**Status.** Design only — this section specs a new module (`core/src/dsp/oss_ring.h/.cpp`); nothing here is implemented. It is the mission-item-3 dependency the comb-ambiguity hypothesis bank (research-closed-loop-control.md §5 item 3, and §2.8's "no live correction-path consumer" note) has been waiting on: a principled beat-period estimate to seed the bank's hypothesis offsets, replacing "guess the comb spacing from one window."

**Correction to the original brief, load bearing.** There is no 12 s STFT ring buffer anywhere in the tree. What exists is the 12 s **post-AEC PCM** history ring (`kHistoryFrames = 48000 × 12` mono floats in `synccore.cpp`, written by `append_history()` during worker drain, read via `sc_copy_recent_capture`) — no spectral structure is stored. This feature adds a new **incremental onset-strength ring**, computed as capture drains, alongside `append_history`'s existing post-AEC tap; it does not read a buffer that doesn't exist (research-dsp-upgrades.md §0.1).

**Onset Strength Signal (spectral flux), computed incrementally.** Per-frame processing on the same post-AEC samples `append_history` already sees, no new capture tap:

- Frame `N = 1024` samples (21.33 ms at 48 kHz), hop `H = 512` (10.67 ms), Hann window. OSS rate `F_oss = 48000/512 = 93.75 Hz`.
- Real FFT per frame (the existing kissfft wrapper, `RealFft(1024)`): `X(m,k)`, `k = 0..512`.
- Log compression: `Y(m,k) = ln(1 + γ·|X(m,k)|)` — flattens dynamics so quiet passages still contribute onsets.
- Half-wave-rectified spectral flux: `Δ(m) = Σ_k max(0, Y(m,k) − Y(m−1,k))`.
- Local-mean removal (kills slow loudness ramps, keeps pulses): `o(m) = max(0, Δ(m) − mean(Δ(m−W..m+W)))`, `W ≈ 47` (≈ ±0.5 s), implemented causally via a 94-sample running-sum delay line — the ~0.5 s output delay is irrelevant, the consumer looks at 12 s of history, not the newest sample.
- Storage: fixed ring `M = 1125` OSS values ≈ 12 s, mirroring the PCM history's span. All buffers sized at init, zero allocation after init, worker-thread-only (same non-RT position as the referee).

Cost: ~94 FFTs of 1024/s plus O(N) bin math, well under 1 ms CPU per second of audio.

**Beat period via on-demand 1D autocorrelation of the OSS.** Not run every frame — proposed cadence is alongside each referee sample (the `kSampleLatencyResidual` rhythm), one shared "analysis moment" per window:

```
r(ℓ) = (1 / (M − ℓ)) · Σ_{m=0}^{M−1−ℓ} o(m)·o(m+ℓ)      (unbiased)
r̂(ℓ) = r(ℓ) / r(0)                                       (normalized)
```

Search `ℓ ∈ [24, 112]` bins ⇔ lag 250–1200 ms ⇔ 240–50 BPM. Tempo-octave disambiguation by harmonic reinforcement before the argmax:

```
s(ℓ) = r̂(ℓ) + 0.5·r̂(2ℓ)          (2ℓ ≤ 224 always exists in the full array)
```

Sub-bin refinement, parabolic interpolation around the winning `ℓ*`:

```
δ = 0.5·(r̂(ℓ*−1) − r̂(ℓ*+1)) / (r̂(ℓ*−1) − 2·r̂(ℓ*) + r̂(ℓ*+1))
beat_period_ms = 1000 · (ℓ* + δ) / 93.75
```

Bin quantization alone is 10.67 ms; interpolation brings the estimate to ~2–3 ms, comfortably inside the MHT gates' tolerances.

**Provisional constants — do not treat as final.** `γ = 100` (log-compression) and the `0.5` harmonic-sum weight are cited from model knowledge, not yet retrieved against the primary sources (Peeters 2007; Grosche & Müller 2011 — research-dsp-upgrades.md §5). Per `docs/REFERENCES.md`'s retrieval-honesty convention, both are **provisional pending primary-source retrieval, and field-tunable** — the implementing ticket must not silently freeze them as load-bearing constants the way `confirm_floor_ms` (§2.7) was after its RFC 5905 grounding was resolved. If the retrieved papers give different values, this section's numbers are wrong, not the papers.

**Confidence: reproducibility across windows, never a single-array threshold.** The standing warning from §2.6/§2.9 applies verbatim here: a peak-vs-mean ratio computed over one array is an extreme-value statistic (§2.6's `sqrt(2 ln N)` analysis), not evidence. The contract:

```cpp
struct BeatEstimate { double period_ms; double salience; bool stable; };
```

`stable` requires the **last 3 estimates to agree within ±10 ms while spanning ≥ 20 s** — reusing the §2.7 `confirm_window_ns` idiom (a handful of estimates seconds apart is not yet "persistent") rather than inventing a new agreement rule. `salience = s(ℓ*) / mean(s)` is exported for **diagnostics/CSV only** — it is never an admission gate on `stable` or on anything a consumer treats as evidence, for exactly the reason `peak_ratio` cannot gate the §2.6 referee.

**Placement.** New `core/src/dsp/oss_ring.h/.cpp`: `OnsetStrengthRing::push(samples, n)` called from the worker drain loop; `OnsetStrengthRing::estimate_beat_period()` called on demand. Owned by `sc_session::wk`, alongside `residual_scratch` — same non-RT worker-thread home as the referee's own scratch buffer.

**Consumers.**

- **MHT hypothesis-bank seeding (the point of this feature).** Hypothesis offsets for the bank = `fix_offset ± k·beat_period_ms`, `k = 1..3`, replacing a guess at the comb spacing from a single window. The χ²/existence machinery is per research-closed-loop-control.md §5 item 3 and is unchanged by this section.
- **§2.8 cross-check.** If `|WindowLag.second_lag_ms − k·beat_period_ms| < 30 ms` for a small integer `k`, the competitor peak `analyze_window` found is the music's own beat comb — corroborating a low `comb_ratio` reading as *ambiguity* (the Billie Jean class) rather than a genuine second copy. `second_lag_ms` remains the free, already-shipped cross-check; the tempogram is the principled estimator, not a replacement for it.
- Optional UI BPM readout, and a `beat_period_ms` column appended **last** to `lag_analyzer` CSVs behind a `--tempo` flag — the CTL-03a precedent (§2.8's `comb_ratio` column): additive columns only, so positional parsers on the field rig don't break.

**Hard limits, restated verbatim from the research doc (standing warnings 3–4).** The hypothesis bank **never touches self-match** — its clutter is self-correlated by construction; CTL-01 (§2.9) owns that problem exclusively, and this feature must not grow a second self-match defense. And **nothing consumes `peak_ratio` as evidence** anywhere in this feature — not as a gate on `stable`, not as an input to `salience`, not anywhere.

**Unchanged:** the C ABI, the state machine (§2.4), `analyze_window`'s graded behavior, `CorrectionPolicy`, and the estimator are all untouched — this section is a new, self-contained worker-side DSP module with a diagnostics CSV column and a future (not-yet-specified) MHT consumer. No existing test is authorized to change by this section.

---

### 2.11 Parameterized whitening exponent (β-PHAT)

**Status.** Design only. This section specs the zero-risk first step of research-dsp-upgrades.md §2 — parameterize and A/B-test offline. It does **not** change the on-device default; that is an explicit non-goal of this section's scope (below).

**Correction to the original brief, load bearing.** The shipped whitening in `lag_window.cpp` already *is* fractional GCC weighting, at β = 0.5. `lag_window.cpp` keeps `p = |X|²/|X| = |X|¹` (`const float mag = std::sqrt(power) + 1e-9f; const float p = power / mag;`), which in the Knapp–Carter weighted-spectrum framing (`Ψ(f) = G(f)/|G(f)|^β`, `G = |X|²`) is exactly `|X|^{2(1−β)}` at β = 0.5. "Move to β = 0.7" is therefore a move from 0.5 to 0.7 — *more* whitening, not the introduction of whitening — and `lag_window.h`'s own header carries a load-bearing warning that the field-test corpus grades the current math byte-for-byte, so any exponent change is corpus-gated (research-dsp-upgrades.md §0.2).

**Derivation.**

| β | retained spectrum | status |
|---|---|---|
| 0.0 | `\|X\|²` | plain autocorrelation — music's own comb dominates |
| 0.5 | `\|X\|¹` | **shipped** (`lag_window.cpp`'s "mild whitening," `p = power/(mag + 1e-9f)`) |
| 0.7 | `\|X\|^0.6` | **proposed** — unproven on our corpus (research-dsp-upgrades.md §2.2) |
| 1.0 | `1` (flat) | full PHAT — explicitly **rejected** in `lag_window.h`'s header comment for single-buffer program material ("full PHAT would also whiten away the music's own spectral shape and make the copy-lag peak unstable against ordinary program material") |

The reverberant-room hypothesis (Donohue et al. 2007, best β ≈ 0.6–0.8 — **not retrieved this session**, verify before treating 0.7 as anything but a starting point) is that late reverberation piles energy into the strong tonal bins, which β = 0.5 still weights ∝ `|X|`; lowering the retained exponent shrinks that dominance so the broadband phase-coherence evidence for the direct-path copy-lag sets the peak instead. The countervailing risk is exactly what the existing header documents: as β → 1 the noise floor is boosted toward equality and the peak destabilizes on ordinary non-flat music. This is a hypothesis to be corpus-tested, not a proof.

**Interface change — new trailing defaulted parameter.**

```cpp
WindowLag analyze_window(const float* x, size_t n, int rate,
                          double min_lag_ms, double max_lag_ms,
                          double whiten_beta = 0.5);
```

**Non-negotiable byte-identical rule.** When `whiten_beta == 0.5`, the existing code branch runs **verbatim** — `p = power / (mag + 1e-9f)` with `mag = std::sqrt(power)` — because `std::pow(power, 0.5)` is **not** bit-identical to that epsilon-guarded division path. The legacy branch is kept exactly as written, never replaced by a generalized `pow` call that happens to agree at β = 0.5 in exact arithmetic; floating-point rounding differs, and `lag_window.h`'s corpus-grading guarantee is about bytes, not about mathematical equivalence. Non-default betas take a separate path:

```cpp
const float power = b.r * b.r + b.i * b.i;
const float p = std::pow(power + 1e-18f, 1.0f - beta_f);  // |X|^{2(1-β)}
```

**Offline A/B tooling.** `lag_analyzer --beta <v>` threads the parameter through both file mode and `--stream` mode. The CSV gains a trailing `beta` column, following the CTL-03a precedent (§2.8) of additive-only columns — and, matching that precedent, the column appears **only when `--beta` is passed**, so runs that don't exercise this flag keep their existing column count and positional parsers on the field rig don't break.

**Corpus gate — non-negotiable, per `lag_window.h`'s own "do not improve the math" warning.** Sweep `β ∈ {0.5, 0.6, 0.7, 0.8}` over the full field corpus: `docs/sync-test-results.md`'s recordings plus the FT8 captures. Promotion criteria that must **all** be met before any future spec section flips the on-device default:

1. No lag flips and no `found` regressions on the corpus's healthy-lock windows.
2. Measurable gain on the reverberant/echoey windows — higher `peak_ratio` margin, lower window-to-window lag jitter, or `comb_ratio` (§2.8) separation improving on the churn class.

Only once both hold does a *future* spec section change the default — at which point the referee (`synccore.cpp`'s `kSampleLatencyResidual` handler) inherits the new default automatically through the parameter default, in a change the corpus will by then have re-graded.

**What must NOT change, in this section's scope.** The on-device default stays `whiten_beta = 0.5`, byte-identical to shipped behavior. This section authorizes only: the new parameter, the legacy-preserving branch, and the offline `lag_analyzer --beta` tooling — nothing that ships to a device changes. No existing test's expected output changes; `lag_window.h`'s corpus-grading guarantee is fully preserved by construction (default argument, verbatim legacy branch).

**Unchanged:** the C ABI, `WindowLag`'s shape (§2.8's `second_lag_ms`/`comb_ratio` additions included), the referee's gating (§2.6), and every existing `lag_window`/`lag_analyzer` test. This section is additive-parameter-only.

---

### 2.12 Volume-duck active probe & capture-energy verdict

**Status.** Design only. This section specs a gentler probe tier that composes with the shipped CTL-01 pause probe (§2.9) — research-dsp-upgrades.md §0.3's correction applies: this is **not** a new subsystem, it is a new actuation and a new verdict channel wired into the existing sentinel/probe machinery in `CorrectionPolicy`.

**Why the duck needs its own verdict channel.** The shipped §2.9 verdict reads the estimator's projected error shift after a pause, because a pause perturbs the *playback timeline*. A volume duck does not — it changes loudness, not position — so the estimate-shift verdict reads zero by construction against a duck and cannot be reused. The verdict source for a duck moves to **capture energy**: whether the mic's own energy dipped when we commanded our own output down.

**Actuation (Kotlin, `SessionViewModel`).** Nominal 150 ms duck, −6 dB, via `AudioManager`, same bounded-coroutine shape as `onActiveProbe` — no free-running loop, the `maybeSampleReferee` hang precedent stands:

```kotlin
val am = context.getSystemService(AudioManager::class.java)
val stream = AudioManager.STREAM_MUSIC
val original = am.getStreamVolume(stream)
val targetIdx = (original downTo 0).first { idx ->
    am.getStreamVolumeDb(stream, idx, deviceType) <=
        am.getStreamVolumeDb(stream, original, deviceType) - 6f
}
val achievedDb = am.getStreamVolumeDb(stream, original, deviceType) -
                 am.getStreamVolumeDb(stream, targetIdx, deviceType)
am.setStreamVolume(stream, targetIdx, 0)
delay(duckMs.toLong())                       // 150 ms nominal
am.setStreamVolume(stream, original, 0)
engine.notifyDuckExecuted((achievedDb * 10).roundToInt())
```

Same shell gates as the pause probe: no-op (no echo) when playback is already paused or calibration is Running/ByEarRunning. Two caveats the pause probe doesn't have: **volume-index quantization** means −6.0 dB exactly is rarely reachable, so the shell echoes `achievedDb` (as a deci-dB int over JNI) and the core judges the dip against the depth *actually commanded*, never the nominal 6; and **Bluetooth absolute volume** propagates the index change to an A2DP sink with sink-dependent latency (tens to a few hundred ms), so detection searches a window rather than assuming the dip lands at the echo instant (below), and `duck_ms` is field-tunable upward (150 → 400 ms) exactly like `probe_pause_ms`.

**ABI additions — append-only.** `SC_EVT_ACTIVE_DUCK` appended at the **end** of `sc_event_type_t`, after `SC_EVT_ACTIVE_PROBE`, payload `sc_evt_active_duck_t { int32_t duck_ms; }`; new `sc_status_t sc_notify_duck_executed(sc_session_t*, int32_t achieved_deci_db)`, mirroring `sc_notify_probe_executed`'s echo shape. `SC_EVT_ACTIVE_PROBE` and `sc_notify_probe_executed` are **untouched** — the shipped pause probe's ABI surface does not change. **Required deliverable of the implementing ticket:** `core/tests/abi_c_check.c`'s exhaustive `event_is_known` switch must gain a `case SC_EVT_ACTIVE_DUCK:` (the same `-Wswitch` exhaustiveness coverage §2.9 established for `SC_EVT_ACTIVE_PROBE`), plus basic compile/link/contract coverage for `sc_evt_active_duck_t` and `sc_notify_duck_executed`, matching the file's existing pattern. This spec does **not** make that edit itself — `SC_EVT_ACTIVE_DUCK` doesn't exist yet, so the edit wouldn't compile until the enum and struct land first.

**Worker-side detection: matched-filter dip in capture-ring log-energy.** Runs over the existing 12 s post-AEC history via `sc_copy_recent_capture` — no new capture tap, same pattern as `kSampleLatencyResidual`:

1. **Envelope:** 20 ms non-overlapping RMS hops → `e(j) = 10·log10(mean(x²) + ε)` at 50 Hz.
2. **Search window:** capture-time `[echo_ns − 250 ms, echo_ns + duck_ms + 750 ms]` — wide enough to absorb App Remote and BT-absolute-volume actuation latency. Epoch rule holds: every sample consumed postdates the current epoch.
3. **Matched filter:** slide a rectangular dip template of width `duck_ms / 20 ms` hops across the window; at each position, dip depth `D = median(flanking baseline hops) − mean(template hops)`, take the max-D position. Robustness: normalize by the baseline's MAD over the preceding 3 s of envelope → `z = D / (1.4826 · MAD)`, so a loud, choppy mix (which has deep natural 150 ms valleys) demands a deeper dip than a smooth ballad before it counts as evidence.

**Verdict rationale — the mixture model.** Mic power is a mixture `P_mic = P_room + P_self`; ducking scales only `P_self` by `10^(−D_cmd/10)` (≈ 0.251 at 6 dB). The dip depth is therefore an estimator of *our fraction of captured energy*:

| self fraction of mic energy | expected dip (6 dB duck) |
|---|---|
| 100 % (pure self-match) | 6.0 dB |
| 80 % | 4.6 dB |
| 50 % (true lock, both audible) | 2.9 dB |
| 20 % | 1.0 dB |
| 0 % (room only / BT headphones) | 0 dB |

**Verdict bands, scaled to `achieved_db` when it differs from the nominal 6:**

- `D_obs ≥ 4 dB` **and** `z ≥ 3` → self-dominant → the shipped `kTrackLost` path (re-listen; recovery via the §2.7 persistence gate, unchanged).
- `D_obs ≤ 1.5 dB` → room-dominant → cleared, sentinel state resets.
- Otherwise (including `z < 3`) → **inconclusive** → escalate **once** to the shipped §2.9 pause probe rather than re-ducking in a loop.

**Division of labor — DSP in the worker, decision in `CorrectionPolicy`.** §3.3's matched-filter/z-score computation runs worker-side, exactly like `kSampleLatencyResidual`'s pattern. The worker hands the *result* — not raw samples — to the policy via a new entry point:

```cpp
void on_duck_result(double dip_db, double z, int32_t achieved_deci_db, uint64_t now_ns);
```

`policy.cpp`'s existing charter — "pure decision logic, no clocks, no DSP" — is preserved; `on_duck_result` decides the verdict band and issues `kTrackLost` or clears suspicion exactly as `on_probe_executed` does today for the pause probe, without itself touching capture data.

**Trigger composition.** Both existing §2.9 triggers (`on_referee_window` agreement starvation, `on_tick` Wittenmark turn-off) arm a **duck request first**; the pause request becomes the escalation tier reached only through the inconclusive-verdict path above, not a second independent trigger path. Proposed cooldowns: **duck cooldown 60 s** (it's near-inaudible, so a shorter cooldown than the pause probe's is deliberate — flagged here as a proposed value, not a derived one, matching how `probe_pause_ms` itself was field-tuned rather than derived), pause keeps its shipped `probe_cooldown_ns` = 120 s unchanged. Seek suppression while a probe/duck is outstanding is unchanged from §2.9's rule. All new state (pending-duck record, verdict accumulation) clears in `reset()` — the same epoch rule §2.7/§2.8/§2.9 already apply to their own state; a fresh join must never judge a duck verdict against a previous session's or previous song's accumulated capture. The referee/probe subsystem remains a **verifier**: `on_duck_result`, like `on_probe_executed`, never writes the estimator or `output_latency_prior_ms` or any other latency prior — only `kTrackLost` or a suspicion clear.

**Sequencing note (research-dsp-upgrades.md §3.5).** The CTL-01 device pass runs first with the pause probe as shipped — it validates the triggers and the verdict plumbing with the unambiguous actuator. The duck swaps in as the **default** tier only after the triggers are field-proven on-device; this section specs the mechanism, it does not resequence CTL-01's own rollout.

**Unchanged:** the state machine (§2.4); `SC_EVT_ACTIVE_PROBE`, `sc_evt_active_probe_t`, and `sc_notify_probe_executed` (§2.9's ABI surface, byte-for-byte); the estimator; §2.6/§2.7/§2.8's mechanisms; and the pause probe's own verdict logic (§2.9), which this section composes with as an escalation tier rather than modifying.

---

### 2.13 Self-Initiated Playback & Auto-Advance Guardian Suppression

**Problem.** Field test 9's Billie Jean churn (Test 2) counted **12 real `play(uri)` restarts vs. 6 correctly-guarded `already loaded; resume+aim (no restart)` resumes** in the test window (field-test-9-results.md, "Audible restart on re-acquire"). Some restarts are legitimate — the FT9 doc is explicit that "each restart legitimately fired because `trackUri` really did differ from what was loaded (ACR kept re-resolving to different editions)" — evidenced by a genuine three-restart oscillation:

```
11:55:30.463  Spotify connected → play spotify:track:0fHbLv7QZDpD2tHqzxOg1e
11:55:31.898  Spotify connected → play spotify:track:6vR5u5b8JeRESx5nZaIWx6
11:55:33.302  Spotify connected → play spotify:track:0fHbLv7QZDpD2tHqzxOg1e
```

But a second, compounding mechanism — "not hypothesized in the brief," per the FT9 doc — makes every one of these restarts audibly worse: the "Spotify auto-advanced" guardian, built for genuine end-of-track auto-advance, cannot tell "Spotify moved on its own" from "we just told it to play something else," so it fires on our own `play(uri)` call and stacks an extra pause+re-listen cycle on top of the re-acquisition already in flight:

```
11:55:31.673  Spotify auto-advanced to spotify:track:0fHbLv7QZDpD2tHqzxOg1e — pausing to hear the room
11:55:31.673  pause()
11:55:31.673  phase: CONVERGING → LOST
11:55:31.673  phase: LOST → LISTENING
11:55:31.673  phase: LISTENING → MATCHING
```

Note the timing: this fires at `11:55:31.673`, between our own `play(...0fHbLv7...)` call at `11:55:30.463` and the *next* `play(...6vR5u5b8...)` at `11:55:31.898` — the guardian is reacting to the player-state confirmation of a URI **we ourselves just commanded** four lines above it. FT9's recommendation, verbatim: "the auto-advance detector needs to suppress itself around a URI change we just initiated, not just get the play-vs-resume branch right."

**Root cause (code seam).** `handlePlayerState` (`SessionViewModel.kt` lines 687–702) fires the guardian whenever `state.trackUri != commanded`, where `commanded = _syncState.value.track?.spotifyUri` — the single most-recently-*resolved* track, not the URI that was actually in flight when *this* player-state event's underlying `play()` call was issued. `startPlayback` (lines 544–608) issues `controller.play(uri)` (line 589) only after `_syncState` has already been updated to that same `uri` by the caller (`resolvedWithAim`/`onTrackResolved`, lines 531–536 and 744–756) — so a single play/state pair is self-consistent in isolation. The failure is a **race across churn**: while a `play(uri=A)` call's confirming player-state event is still in flight, a *newer* ACR re-resolution can update `_syncState.value.track` to `uri=C` before that confirmation for `A` lands. `handlePlayerState` then compares the late `A` state against the now-current `C`, sees a mismatch, and calls `onSpotifyAutoAdvanced(controller, A)` (line 698; handler at lines 2037–2043) — even though `A` is a URI *we* issued, not one Spotify chose. `_syncState.track` — the only comparison target today — is a single, most-recent value; it structurally cannot represent "several of our own `play()` calls are still in flight."

**Design — an expected-URI latch, not a timer.** Every `controller.play(uri)` call (the `play(uri)` branch of `startPlayback`, line 589 — not the `resume()` branch, which never changes `trackUri` and so was never ambiguous) latches that `uri` into a small, bounded set of "self-issued, not yet confirmed" URIs, each entry stamped with the wall time it was latched. `handlePlayerState` is extended: before the existing `state.trackUri != commanded` test, it first checks whether `state.trackUri` is present in the (unexpired) latch set. If so, the state is treated as self-initiated regardless of what `_syncState.value.track` currently holds, the matching latch entry is consumed, and the guardian does not fire. **This is deliberately a set-membership test, not a blanket "ignore auto-advance for N ms after any play()" timer** — a timer-only suppression would also mask a genuine Spotify- or user-initiated auto-advance to some other URI that happened to land inside the same window. Because the latch only ever contains URIs *we* issued, a real auto-advance to any URI we did not just command is never in the set and still trips the guardian exactly as today — the change can only suppress additional false positives, never newly suppress a true one. The latch's expiry window exists purely to bound memory (a `play()` call that never gets confirmed — connect failure, killed session — must not leak an entry forever) and to span "the track-load transition window": Spotify's own metadata often arrives before playback is actually buffered and audible (the FIELD TEST 2 round-2 note at `aimUntilLanded`'s header already documents a ~10 ms metadata-before-buffered gap for the aim path), so the latch must outlive that transition, not just the instant of the call.

**New config (Kotlin `SessionViewModel` companion; naming mirrors `policy.h`'s convention — `ENGINE_DEADBAND_MS` is existing precedent for a core-style name materialized as a Kotlin `SCREAMING_SNAKE_CASE` constant):**

| Field | Default | Rationale |
|---|---|---|
| `self_play_latch_window_ms` | 5000 | FT9's own churn measured three self-issued restarts inside 2.8 s (`11:55:30.463`→`11:55:33.302`); 5 s covers that with margin without leaving a stale entry live indefinitely. |
| `self_play_latch_max_entries` | 4 | Bounded ring, sized off FT9's observed churn rate (12 restarts across Test 2's ~5.5 min window, with bursts of 3 in under 3 s) — generous enough for a burst, still finite. |

**Unchanged.** The state machine (§2.4); `onSpotifyAutoAdvanced`'s existing `autoAdvanceHandled` one-response-per-track dedup (line 2038), which continues to run downstream, unaffected, only ever seeing a call that survives the new latch check; `scheduleEndOfTrackPause`'s genuine end-of-track backstop; the C ABI (this section is Kotlin-shell-only — SyncCore has no notion of URIs at all, so nothing here touches `synccore.h`).

**Test obligations.** (1) A `play(uri=A)` followed by a late player-state event reporting `A` while `_syncState.track` has already moved to `uri=C` must NOT call `onSpotifyAutoAdvanced`. (2) A player-state event reporting a URI that was never latched (a genuine external auto-advance) must still call `onSpotifyAutoAdvanced`, unchanged from today. (3) A latch entry older than `self_play_latch_window_ms` must be treated as expired — a late-enough confirmation of an old self-issued `play()` must fall through to the ordinary (pre-existing) guardian check. (4) The latch must not grow unbounded across sustained churn (`self_play_latch_max_entries` enforced, oldest evicted first). (5) A reproduction of FT9's exact three-restart sequence (`0fHbLv7...` → `6vR5u5b8...` → `0fHbLv7...` inside 2.8 s) must produce zero guardian firings, matching the "6 correctly-guarded" ideal rather than the observed extra pause/re-listen cycles.

---

### 2.14 Post-Lost / Aim-Failure Match Corroboration

**Problem.** FT9's Test 3 (Dreams [Extended]) surfaced a defect distinct from simple misrecognition. After an aim gave up and the estimator reported a garbage reading, the recognizer matched the same wrong song seven times in a row; the self-match guard (§7.3's CORE-06) correctly rejected the first three, then stopped rejecting once the fix looked locally self-consistent rather than independently corroborated:

```
12:15:02.787  aim gave up after 4 attempts — estimator will report the error
12:15:03.232  sync err=763715ms drift=353ppm conf=0.12        ← garbage/stale value, not a real reading
12:15:06.829  MATCH ✓ 'Everywhere (2002 Remaster)' offset=127020ms   [fixdbg zEnd=233]
12:15:06.834  fix rejected: SELF_HEARING
12:15:11.158  MATCH ✓ 'Everywhere (2002 Remaster)' offset=132040ms   [fixdbg zEnd=213]
12:15:11.160  fix rejected: SELF_HEARING
12:15:16.394  MATCH ✓ 'Everywhere (2002 Remaster)' offset=137060ms   [fixdbg zEnd=203]
12:15:16.396  fix rejected: SELF_HEARING
12:15:21.269  MATCH ✓ 'Everywhere (2002 Remaster)' offset=142080ms   [fixdbg zEnd=54]  ← accepted
12:15:21.272  phase: CONVERGING → LOST → LISTENING → MATCHING
12:15:23.112  MATCH ✓ 'Everywhere (2002 Remaster)' offset=143800ms   [fixdbg zEnd=44]
12:15:23.115  fix rejected: LOW_CONFIDENCE
12:15:23.411  Spotify connected → play spotify:track:0CQ2EPgBXhJEnTaxbb4rWt   ← restart to the wrong song
```

`zEnd` (the recognizer-vs-local-timeline discrepancy, computed shell-side — `SessionViewModel.kt` line 1842, `val zEnd = shellProj - fix.matchOffsetMs`) fell from 203–233 ms on the three rejected attempts to 44–54 ms on the two that got through. Per the FT9 doc's own reclassification: "the defect is not 'picked the wrong song,' it's that a sustained run of low-margin matches, immediately following a track-lost/aim-failure, was eventually accepted once it became self-consistent rather than independently corroborated against the room... nothing in this path re-checks agreement against room continuity the way CTL-02's persistence gate does for corrections — it only checks disagreement against our own prior estimate." All seven matches to "Everywhere" across the episode (`12:15:06`–`12:15:37`) "never disagreed with each other" — identity was never actually in doubt across the run; what was missing was corroboration independent of JTP's own already-unreliable position estimate (the `aim gave up` / 763715 ms garbage reading two seconds earlier is the same episode's evidence that JTP's own timeline was itself unreliable at that exact moment). FT9's own recommendation: "after a track-lost/aim-failure, widen the corroboration requirement for the next several fixes (a CTL-02-style N-of-M agreement) before acting on ANY match — including a fresh `play(uri)` — rather than accepting on the first fix that merely stops looking self-inconsistent."

**Design — identity corroboration, parallel to but distinct from §2.7's offset corroboration.** §2.7's persistence gate corroborates *offsets* for an *already-established* track identity (a cluster of fixes agreeing on how far off a known song is). This section corroborates *identity itself* — which song, i.e. which URI — before the shell takes any action on it. Because track identity is a shell-only concept (`sc_recognition_fix_t` carries no URI; SyncCore never resolves a track), this mechanism lives entirely in `SessionViewModel`, at the one place identity resolution and actuation already happen together: `resolveTrack` (lines 709–742), called only while `_syncState.value.phase == SessionPhase.MATCHING` (line 1865), immediately upstream of `resolvedWithAim` → `startPlayback` → `play(uri)`/`seekTo`.

A corroboration episode **arms** when `MATCHING` is (re-)entered via either of the two failure paths this section targets: (a) `onTrackLost()`'s any→LOST→LISTENING→MATCHING path (§2.4), or (b) `aimUntilLanded` exhausting `MAX_AIM_ATTEMPTS` without landing (today, line 662's "aim gave up" log is passive — this section requires it to also force the same LOST→LISTENING→MATCHING re-bootstrap `onTrackLost()` already performs, rather than silently letting `playerStateWatcher` proceed to `CONVERGING` against an unresolved aim, which is exactly the sequence FT9 measured: an unresolved aim fed a stale/garbage estimate that only tripped the coarser 2000 ms `lost_threshold_ms` well after the fact). While armed, every recognizer result is appended to a bounded ring, regardless of what §7.3's guard independently decided for it (a rejected `SELF_HEARING`/`LOW_CONFIDENCE` fix is still recorded here) — because the exact failure this section closes is one where §7.3's own reference (our own position estimate) was itself unreliable, so a fix's cross-fix agreement with *other recent fixes*, checked against **elapsed wall-clock time** (a reference independent of JTP's own possibly-corrupted local estimate), is additional, independent evidence, not a re-check of the same thing.

**Mechanism.** Each ring entry is `(uri, match_offset_ms, capture_mono_ns)`. A new entry either extends the current streak — same `uri` as the streak's most recent entry, and its offset delta agrees with the elapsed wall-clock delta within `ident_confirm_offset_agree_ms` (i.e. `|Δoffset − Δcapture_time| ≤` tolerance) — or it disagrees (different `uri`, or an offset delta that doesn't track wall-clock), in which case the streak restarts at just this entry (the same "restart, not accumulate" rule §2.8's pending-large-correction record already established). Once the streak reaches `ident_confirm_min_fixes` agreeing entries, identity is corroborated: the shell proceeds to `resolveTrack`'s existing resolution path (`resolvedWithAim` → `startPlayback`) using the **newest** entry's data — not the streak's first — mirroring §2.7's "cluster mean, not the instantaneous value" and §2.8's "fresh error, not the stale pending one" principle of always actuating on the most current evidence. If the streak never reaches `ident_confirm_min_fixes` within `ident_corrob_max_age_ms` of arming, the ring clears and the session simply keeps sampling in `MATCHING` (no escalation to `error`) — this expiry behavior, and whether a UI affordance should surface "still trying to identify the room" during it, is flagged below as an open question for the PM.

**Interaction with §7.3 (the CORE-06 self-match guard).** Unchanged and unweakened: every fix still passes through CORE-06's per-fix continuity/self-position check exactly as today, and a `SELF_HEARING` rejection still means the estimator never sees that fix (`estimator.on_fix` is not called). This section adds a **second, independent, shell-side gate** downstream of that — it does not loosen or bypass CORE-06's verdict, and CORE-06 does not know this gate exists. The two are complementary for the same reason §2.9's probe and §2.6's referee are complementary (two independent consumers of related evidence): CORE-06 asks "does this fix look like it could be us, right now, given our own position estimate," while this section asks "do several fixes, considered only against each other and the wall clock, agree on a stable identity" — a test that does not depend on JTP's own position estimate being trustworthy, which is precisely the axis that failed in the FT9 episode above.

**Known limitation, stated rather than hidden.** N-of-M offset-progression agreement defends well against an isolated or unstable phantom match (a wrong match whose reported offset would jump around incoherently across attempts). It does **not**, by itself, disprove a match that is *itself* internally self-consistent across repeated attempts — which the FT9 episode's own numbers arguably show: all seven "Everywhere" matches agreed with each other and with elapsed wall-clock time throughout, per the doc's own read ("never disagreed with each other"). This section raises the cost of exactly the failure FT9 measured (three-to-five samples of exposure instead of one or two) and closes the specific gap FT9's recommendation named (corroboration independent of our own uncertain position estimate) — it is not a claim that a sufficiently well-behaved wrong match cannot still pass `ident_confirm_min_fixes` agreeing samples. No source in `research-offset-disambiguation.md`/`research-closed-loop-control.md` offers a stronger mechanism for this specific case either (§3 of the former: "the algorithm is... very sensitive to which particular version of a track has been sampled" — Wang03 discriminates near-duplicates, it does not arbitrate which of two genuinely-matching candidates is contextually correct).

**New config (Kotlin `SessionViewModel` companion, `policy.h`-convention naming):**

| Field | Default | Rationale |
|---|---|---|
| `ident_confirm_min_fixes` | 3 | Mirrors §2.7's `confirm_min_fixes` (3) exactly — "philosophically parallel," per this section's own framing, to the persistence gate's minimum cluster occupancy. |
| `ident_confirm_offset_agree_ms` | 500 | Reuses `kRoomContinuityGateMs` (`synccore.cpp` line 71, the CORE-06 room-continuity tolerance) rather than inventing a new figure for "advancing ~wall-clock." |
| `ident_corrob_max_age_ms` | 30000 | Matches §2.8's `large_pending_max_age_ns` (30 s) precedent for "how long unconfirmed evidence may sit before it expires." |

**Epoch rule.** The corroboration ring/streak lives alongside the shell's other post-loss bootstrap state (`firstEstimateSeen`, `samplingAttempts`) and is cleared whenever it is re-armed (a fresh `onTrackLost()`/aim-failure) or satisfied (identity corroborated) — it never carries across sessions or across a prior, already-resolved track.

**Unchanged:** the state machine (§2.4), the C ABI, §7.3's guard verdicts (unweakened, unbypassed), and every fix still reaching `sc_submit_recognition_fix` exactly as today — this section only changes when the *shell* acts on a resolved identity, never what SyncCore itself accepts or rejects.

**Open questions for the PM.** (1) Should `ident_corrob_max_age_ms` expiry surface distinct UI copy ("still trying to identify the room") rather than silently continuing to look like ordinary `MATCHING`? (2) Should the two arming paths (track-lost vs. aim-failure) share one set of thresholds, or does an aim-failure — which FT9 shows can coincide with a genuinely unreliable local estimate — warrant a stricter `ident_confirm_min_fixes` than a plain track-lost re-listen? (3) The "Known limitation" above means this section reduces but does not eliminate FT9's exact failure class — is that an acceptable interim bar for the field, pending a stronger mechanism?

**Test obligations.** (1) A track-lost re-listen followed by 3 consecutive same-URI fixes with wall-clock-consistent offsets must resolve/aim on the 3rd, not the 1st. (2) A disagreeing fix (different URI, or an offset break) mid-streak must restart the streak at that fix, not accumulate toward the old one. (3) An aim-failure (`MAX_AIM_ATTEMPTS` exhausted) must now force the LOST→LISTENING→MATCHING re-bootstrap and arm this section's gate, where today it silently continues to `CONVERGING`. (4) A fix rejected by §7.3 as `SELF_HEARING` must still be recorded in this section's ring (not discarded), and must still count toward — or break — the streak like any other fix. (5) A streak that never reaches `ident_confirm_min_fixes` within `ident_corrob_max_age_ms` must clear without transitioning to `error`.

---

### 2.15 Convergence Settling Hysteresis

**Problem.** FT9's Test 2 (Billie Jean) recorded the full correction sequence during the stabilization window:

| Time | seek | jump | e | conf | Below 350 ms deadband? |
|---|---|---|---|---|---|
| 11:57:26.885 | 230921ms | −269ms | 633 | 0.80 | No — instantaneous |
| 11:58:14.260 | 278269ms | +79ms | 150 | 0.83 | **Yes — CTL-02 persistence gate** |
| 11:58:18.343 | 282000ms | −287ms | 542 | 0.57 | No — instantaneous |
| 11:58:22.419 | 285589ms | −291ms | 547 | 0.74 | No — instantaneous |
| 11:59:19.523 | 342670ms | +64ms | 87 | 0.82 | **Yes — CTL-02 persistence gate** |

The human reported "3–4 corrections then an interfering 5th"; FT9's own read is that "the human's perceived 'interfering 5th correction' most likely corresponds to one of the three instantaneous ones (633/542/547 ms) landing while the audible impression was already close." FT9's recommendation, verbatim: "a convergence deadband/settling hysteresis — once |error| is floor-class, require corroboration or a larger threshold before firing, mirroring CTL-03's own logic but at a lower threshold." Test 1 (Vienna) supplies the companion evidence for what a genuinely stable, at-floor lock looks like versus one that never gets there: the `trim=+585 ms` segment held "flat at 43 ms — at floor," while the `trim=0 ms` segment "never stabilized before the room went quiet," alternating between a 43–52 ms low mode and "large harmonic-multiple values (1257, 1639, 2361 ms — close to 2×/3×/4× the beat_period_ms of ~500–560 ms)" — the doc states plainly, "I cannot report a clean trim=0 steady-state number for Test 1." That contrast is exactly the target/non-target split this section's dwell requirement is built to preserve: a mechanism that engages on the clean plateau and never spuriously engages on the unstable one.

**Design.** A **settled** state, entered once the filtered, converged `est.error_ms` has sat at or below `settle_enter_threshold_ms` continuously through the existing post-seek `settle_ns`/`post_settle_verify_ns` window (i.e. a correction — instantaneous or persistence-gate — actually lands at floor and the standard post-settle verify fix confirms it, rather than requiring a new, separate long dwell timer). Once settled, this section **raises the bar for every further correction proposal** — not only ones already routed through §2.7's persistence gate, but the *instantaneous* deadband-crossing path too: while settled, a proposed correction must additionally satisfy a **stronger** persistence-gate-style test — `settled_confirm_min_fixes` agreeing samples within `settled_confirm_agree_ms` of their mean, both stricter than §2.7's own `confirm_min_fixes`/`confirm_agree_ms` defaults — before it is allowed to actuate. Applied to Test 2's timeline: the `150 ms` correction at `11:58:14.260` is exactly the kind of event that would enter settled; the `542 ms` proposal four seconds later at `11:58:18.343` would, under this section, be held pending a second agreeing fix rather than firing immediately off one estimate — the same shape as §2.8's large-correction hold, at a far smaller magnitude. This reading is this section's own design analysis of the FT9 timeline, not a claim the FT9 doc itself states — the doc's table does not attribute a specific "already settled" moment to the sequence, only the human's qualitative complaint and its own plausible-cause read.

**Explicit large-error bypass.** A fresh estimate with `|error| ≥ large_correction_threshold_ms` (§2.8's existing 1000 ms CTL-03 threshold) or `|error| ≥ lost_threshold_ms` (2000 ms, track-lost) exits settled immediately and unconditionally — these route to §2.8's hold and the track-lost path respectively, exactly as today, so that a genuine perturbation (a real discontinuity, not a small residual wobble) is never slowed down by the stronger settled-state bar. No new knob is needed for this: it reuses `large_correction_threshold_ms` and `lost_threshold_ms` verbatim.

**Why `settle_enter_threshold_ms` is not simply `confirm_floor_ms`.** §2.7's `confirm_floor_ms` (125 ms) defines "healthy sync, never corrected" for the persistence gate's own cluster judgment. This section's entry threshold is set slightly wider, at 150 ms, specifically so that a correction which *itself* closes error to ~150 ms (as FT9's own `11:58:14.260` gate-firing did) is recognized as "reaching floor" without demanding the stricter 125 ms bar be cleared *again* immediately after a correction whose entire purpose was to reach floor.

**New `PolicyConfig` fields:**

| Field | Default | Role |
|---|---|---|
| `settle_enter_threshold_ms` | 150.0 | Filtered `\|error_ms\|` at or below which, sustained through the existing settle/verify window, the policy is considered "at floor" and enters settled. Sized to admit FT9's own 150 ms gate-firing (`11:58:14.260`) as reaching floor, not just `confirm_floor_ms`'s stricter 125 ms. |
| `settled_confirm_min_fixes` | 5 | Stronger than §2.7's `confirm_min_fixes` (3) — the whole point of this section is to raise the bar once settled. |
| `settled_confirm_agree_ms` | 40.0 | Stronger (tighter) than §2.7's `confirm_agree_ms` (60 ms), for the same reason. |

`large_correction_threshold_ms` (1000.0) and `lost_threshold_ms` (2000.0) are reused unchanged as the bypass triggers — no new fields.

**Mechanism placement.** Lives in `CorrectionPolicy`, alongside §2.7's ring — this section's "stronger corroboration" is a second, higher-bar variant of the exact same ring/cluster machinery (`ring_append`/`ring_all_agree`/`ring_mean`), gated on a `settled_` boolean rather than a new data structure. Entering settled does not clear the existing ring; it changes which thresholds `on_estimate` checks it against.

**Known limitation.** Because entry requires the *existing* settle/verify window to confirm floor first, this mechanism cannot engage mid-churn, before any correction has actually landed at floor — it does not, by construction, retroactively protect the `633/542/547 ms` instantaneous corrections that occurred while Billie Jean's beat-comb ambiguity (§2.16) was still being fought outright; it protects the *stable, already-converged* case Test 1's Vienna `trim=+585` plateau demonstrates, and the brief interval after Test 2's own gate-firings, not the general mid-recovery churn itself (which stays §2.16/comb-ambiguity territory).

**Epoch rule.** The settled flag and its own dwell bookkeeping clear in `reset()`, alongside §2.7's ring, §2.8's pending record, and §2.9's sentinel state — a fresh join or track-lost re-listen must never be born "settled."

**Unchanged:** the state machine (§2.4), the C ABI, the estimator, §2.7/§2.8/§2.9's own mechanisms (this section changes only which threshold `on_estimate` checks a proposal against while settled — it does not change how a correction, once authorized, is computed or executed).

**Test obligations.** (1) A correction that lands at ≤150 ms and clears the standard settle/verify window enters settled. (2) While settled, a fresh instantaneous-path proposal below `large_correction_threshold_ms` is held, not fired, until `settled_confirm_min_fixes` agree within `settled_confirm_agree_ms`. (3) A fresh estimate ≥ `large_correction_threshold_ms` or ≥ `lost_threshold_ms` exits settled immediately regardless of any pending hold. (4) Settled clears on any emitted seek (instantaneous, persistence-gate, or settled-gate) and on `reset()`. (5) A reconstruction of Test 1's `trim=0` alternating segment (43–52 ms interleaved with 1257–2361 ms harmonic-multiple readings) must never spuriously enter settled — the wild readings must clear it (or prevent entry) via the large-error bypass, consistent with FT9's own observation that the segment "never stabilized."

---

### 2.16 Multi-Hypothesis Tracking (MHT) Bank — Epic 10, NOT scheduled

**Status.** Design only, and explicitly **not scheduled** — this is Epic 10 territory per `research-closed-loop-control.md` §5 item 3's own framing ("a real but bounded follow-on") and `backlog-tickets.md`'s DSP-01b ticket, which documents the MHT seeding contract in code comments but explicitly states "the bank itself is explicitly OUT of this ticket's scope, pending its own future spec." This section is that future spec, sketched for planning purposes; no implementation, no ABI change, and no new test is authorized by this section alone.

**FT9 evidence.** Test 2 (Billie Jean) reproduced FT8's harmonic-churn finding "at much higher resolution": ACRCloud matched Billie Jean to **at least 6 distinct Spotify catalog editions** (`5dMuRtYktKL5Bkv5qph75v`, `1euuAfFtkRzJy489azxfLC` "Long Version", `5ChkMS8OtdzJeqyybCc9R5`, `0fHbLv7QZDpD2tHqzxOg1e`, `5W23Jb8IrP0CnLs5o9dlFY`, `6vR5u5b8JeRESx5nZaIWx6`) across **~134 phase transitions** (deduplicated; 148 raw log lines). The mic's own ground truth was independently compromised the same way: "`lag_ms` alternates between a low mode (40–64 ms) and values at almost exact integer multiples of `beat_period_ms` (~512–516 ms): 1025/1026 (≈2×), 1637–1661 (≈3.2×), 2046–2133 (≈4×), with `comb_ratio` mostly 1.0–1.7 (near-flat/ambiguous by the metric CTL-03a added specifically to catch this)." FT9's own framing: "Billie Jean's repetitive bassline defeats both ACR's fingerprint offset recognition *and* the mic's autocorrelation lag estimate — the same root cause driving two independent symptoms." This is precisely the class §2.16 targets: repetitive material producing multiple structurally-plausible offset candidates spaced at beat-period multiples, for both the recognizer and the acoustic referee.

**Correction to the task brief, load-bearing.** The originating brief for this section cited "comb_ratio 4.3 during the pre-seek plateau" as comb-aliasing evidence; per the FT9 doc itself this is a *misattribution* — comb_ratio 4.3 belongs to Test 3's Dreams pre-seek plateau, which the doc explicitly reports as "high confidence, **not** the ambiguous ~1.0 seen with Billie Jean." A comb_ratio of 3–4.3 is the *unambiguous*, single-real-copy reading (§2.8's own `comb_ratio ≥ 1` construction: "a high ratio reads as one unambiguous copy-lag"); the genuinely ambiguous evidence for this section is Billie Jean's **1.0–1.7** reading, cited above. This section uses the correct number.

**Design.** A small bank of parallel offset hypotheses, seeded at `fix_offset ± k·beat_period_ms` for `k ∈ {1, 2, 3}`, using §2.10's `BeatEstimate` (`period_ms`, `stable`) and §2.8's `WindowLag.comb_ratio` to decide when the bank is *warranted* at all — only repetitive material with a trustworthy beat estimate and an ambiguous comb reading justifies the extra state; a sharp, unambiguous single-copy lag (Dreams' 4.3, corrected above) should never spawn a bank. Each hypothesis is a parallel `SyncEstimator` instance — reusing `estimator.h` verbatim, per `research-offset-disambiguation.md` §2b/§4 item 3's own cost note ("reuses `estimator.h` verbatim — no DTW, no NMF"), never a new estimator implementation. Admission of a fix into a given hypothesis is gated by that hypothesis's **own posterior covariance** via a χ² Mahalanobis test (Grinberg §3.2, Eq. 3.2 — `research-closed-loop-control.md` §1c/§5 item 3), generalizing the existing fixed `outlier_gate_ms`/`outlier_gate_max_p00` pair from a single implicit two-hypothesis gate into a principled, per-hypothesis, N-hypothesis one. Each hypothesis carries an **IPDA-style existence probability** (Mušicki & Evans, via Grinberg §4) — a scalar updated by subsequent fixes and referee windows, decaying on non-corroboration the same way `conf_age_tau_s` already decays confidence, but tracked per-hypothesis rather than for one shared estimator. Hypotheses below `mht_existence_prune_floor` are dropped; the policy actuates — issues an actual seek — only off the single dominant hypothesis once its existence probability clears `mht_existence_actuate_threshold`, never off a soft blend of several (PDA's own soft-blend update, Eq. 3.6, is explicitly the wrong shape here per the same research doc's §2(iii): blending would let an anomalously clean but wrong candidate dominate).

**Hard limit, restated verbatim (do not relax).** The bank **never touches self-match.** `research-closed-loop-control.md` §2(iii)/§4 item 3 is explicit that PDA/IPDA's clutter model assumes independent, identically-distributed clutter — self-match "clutter" is neither: it is self-correlated, caused by our own last seek, and anomalously *cleaner* than genuine fixes (FT8/FT9's own eerily-low-jitter self-match signature). Routing self-match through this bank "would not merely be unhelpful, it would be actively wrong" (same source) — a self-match candidate would win the existence-probability competition precisely because it looks best. §7.3/§2.9's guard and probe own self-match exclusively; this section must never grow a second self-match path, exactly as §2.10 already states for the tempogram feeding it.

**Bounded memory and CPU (provisional, not field-validated — Epic 10).** Proposed cap `mht_max_hypotheses = 4` (bounded bank size, evicting the lowest-existence-probability hypothesis first if seeding would exceed it); each hypothesis is one `estimator.h` instance (small, fixed-size state, no heap growth per the codebase's existing zero-allocation-after-init convention) plus one existence-probability scalar. No new FFT or capture tap — hypothesis seeding consumes §2.10's `BeatEstimate` and §2.8's `comb_ratio`, both already computed for other consumers. CPU cost is `O(mht_max_hypotheses)` Kalman updates per accepted fix, negligible next to the existing single-estimator cost.

**Additive C ABI only, if/when scheduled.** Any future implementing ticket must append new events/functions at the end of `sc_event_type_t`/the header — never reorder — following exactly the precedent §2.9's `SC_EVT_ACTIVE_PROBE` and §2.12's `SC_EVT_ACTIVE_DUCK` already set; existing tests stay byte-unmodified. This section makes no ABI change itself — there is nothing to append yet.

**New config (provisional defaults, Epic 10 — do not treat as final; no field corpus has validated any of these, unlike §2.7's RFC/FT8-grounded constants):**

| Field | Default (provisional) | Rationale |
|---|---|---|
| `mht_max_hypotheses` | 4 | Bounded bank size; a "small bank" per the design brief, no field data yet to size it more precisely. |
| `mht_seed_k_max` | 3 | Matches the seeding contract already documented in `backlog-tickets.md`'s DSP-01b ticket (`k = 1..3`). |
| `mht_warrant_comb_ratio_max` | 1.7 | Reuses Billie Jean's own measured ambiguous band (comb_ratio "mostly 1.0–1.7," FT9) as the sizing basis for "warranted." |
| `mht_warrant_requires_stable_beat` | true | Requires §2.10's `BeatEstimate.stable` before seeding — an unstable/unreliable `beat_period_ms` must not seed hypotheses at all. |
| `mht_existence_prune_floor` | 0.05 | Provisional; below this a hypothesis is dropped. Not corpus-validated. |
| `mht_existence_actuate_threshold` | 0.75 | Provisional; a hypothesis must clear this before its correction may actuate. Not corpus-validated. |

**Corpus gate, non-negotiable (per §2.11's own precedent for any change touching graded DSP/estimator behavior).** Before any future spec schedules this for implementation, it must be swept against the full field corpus (`docs/sync-test-results.md` plus the FT8/FT9 captures) with the same promotion discipline §2.11 already requires for β-PHAT: no regressions on the corpus's healthy-lock windows, and a measurable win specifically on the comb-ambiguous windows (Billie Jean-class) this bank targets.

**Unchanged:** the state machine (§2.4), the C ABI, `analyze_window`'s graded behavior, the estimator, `CorrectionPolicy`'s existing mechanisms (§2.7/§2.8/§2.9/§2.15), and §7.3/§2.9's self-match ownership — this section adds nothing runnable, only a design for a future, separately-scheduled Epic 10 ticket.

**Test obligations.** None authorized by this section — it is design-only and not scheduled. The eventual implementing ticket must, at minimum: (1) prove zero interaction with the self-match guard/probe test suite (§7.3/§2.9's existing tests byte-unmodified); (2) validate hypothesis admission/pruning/existence-decay against synthetic fixtures before any corpus run; (3) run and pass the corpus gate above before any on-device default changes; (4) add ABI coverage (`abi_c_check.c`) for any new appended event, mirroring §2.9/§2.12's own required-deliverable pattern, only once such an event is actually proposed.

---

### 2.17 CTL-06 Diagnostic Instrumentation — policy-state event & fix-arbitration diagnostics

**Status.** Implementation-authorized (wayfinder map #41, ticket #42 / CTL-06/W1; charter decisions recorded on the map 2026-08-20). **Additive instrumentation only — zero behavior change anywhere.** The estimator, `CorrectionPolicy` decisions, and the self-match guard's arbitration remain byte-identical in outcome; this section only makes what they already do observable. Two consumers: the §6.4 control run (CTL-06/W2, #43) that discriminates the chronic-zEnd-bias root cause, and CTL-04 field observability — `settled_` has now been unobservable through two field tests (FT10, FT11: `grep -i settl` → zero hits both runs).

**Evidence base.** `docs/ctl05-investigation.md` §7 (every number in that investigation's §2 walkthrough had to be hand-reconstructed from timestamps because the guard's inputs aren't logged); FT11's verification addendum (`docs/field-test-11-results.md`); the CTL-06/W3 clamp reproduction (`research/ctl06-clamp-repro` branch, `7a46c0a`) — which additionally established that the offline harness can replay field logs through the real estimator, making per-fix diagnostics directly valuable to future offline reproductions.

**Design — two new events, appended to the end of `sc_event_type_t` per the §2.9/§2.12 ABI precedent (never reorder):**

1. **`SC_EVT_POLICY_STATE`** (payload `sc_evt_policy_state_t`): the dedicated per-tick policy-state event — the charter explicitly chose this over piggybacking a bool on `sc_evt_sync_estimate_t`, to give policy state a proper owned surface with room for future fields. Initial payload: `settled` (the §2.15 hysteresis state) and `in_deadband_streak`-derived convergence context the policy already tracks; future policy fields append to the struct end. Emitted on the same worker cadence as `SC_EVT_SYNC_ESTIMATE` (no new timer, no new config). Fixed-size payload, zero-allocation-after-init convention holds.
2. **`SC_EVT_FIX_DIAG`** (payload `sc_evt_fix_diag_t`): emitted **once per submitted fix, accepted or rejected**, immediately after guard arbitration, carrying the arbitration's own inputs and outputs: the fix offset, the verdict (accepted / rejection reason), `tracks_room`, `tracks_cand`, the live `room_anchor_offset_ms` and its age, and — for self-hearing arbitration specifically — the comparison values the guard computed (`off`, `predicted_room`, `local_audible_ms`). This is §7's "log the guard's inputs, not just the verdict," realized as one event instead of retrofitting existing payload structs (existing `sc_evt_fix_rejected_t` stays byte-identical).

**Android shell.** JNI forwards both events. Logging is **new-lines-only — no existing log-line format changes** (FT analysis tooling greps the current formats):

- `fixdiag: off=<ms> verdict=<ACCEPTED|SELF_HEARING|...> trackR=<0|1> trackC=<0|1> anchor=<ms>@<age_ms> pred=<ms> localAud=<ms>` — one line per fix, pairing with its `fixdbg:` line via the shared offset value.
- `policy: settled → true|false` — logged on **transition only** (not per tick; the 1 Hz stream stays clean). Satisfies the two field tests' failed `grep -i settl` probe.

**Unchanged:** the state machine (§2.4), every existing event payload struct, all existing log-line formats, the estimator, `CorrectionPolicy` behavior (§2.7/§2.8/§2.15), the self-match guard and CTL-05 post-seek machinery (§7.3), and all existing tests byte-unmodified (house rule).

**New config:** none. Cadence reuses the estimate emit period; everything else is unconditional diagnostic emission.

**Test obligations (seams confirmed with PM 2026-08-21 — existing seams only, no new hooks):** (1) core: new ctest coverage driving the public C API + event pump exactly as the CTL-05 tests do — assert `SC_EVT_POLICY_STATE` emission cadence and `settled` transitions, and assert `SC_EVT_FIX_DIAG` values across an accepted fix, a SELF_HEARING rejection (values must match the §2 hand-reconstruction method's arithmetic), and a post-seek corroboration sequence; (2) ABI: `abi_c_check.c` coverage for both new events/structs, mirroring §2.9/§2.12; (3) Android: SessionViewModel JVM-suite assertions that the two new log lines are emitted with the event's values; no test reaches into policy internals — the event surface is the test surface.

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
