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
- Rate/quota: ShazamKit Android has request quotas per developer account — recognizer must reuse one session per sync session and respect SyncCore's `SC_EVT_REQUEST_FIX` cadence (no free-running recognition loops). Confirm commercial terms (spec §11.3) before launch — **blocking ticket**.

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
