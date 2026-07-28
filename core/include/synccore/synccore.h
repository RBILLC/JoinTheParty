/* synccore.h — JoinTheParty SyncCore public C ABI.
 *
 * This header is the stable boundary between the C++ core and the native
 * shells (Swift C-interop on iOS, JNI on Android). It must remain pure C99:
 * no C++ types, no platform includes.
 *
 * Threading contract (technical-requirements.md §1.1):
 *   - sc_push_capture is real-time safe and may be called ONLY from the
 *     audio I/O thread: lock-free, no allocation, no logging.
 *   - Every other sc_* function is thread-safe and non-RT (internal mutex).
 *   - Events are delivered on SyncCore's single internal worker thread.
 *     Shells must marshal to their main thread. The callback must return
 *     quickly (< 1 ms) and must not call back into sc_* re-entrantly.
 *   - The caller must guarantee no sc_push_capture call is in flight or
 *     will be made after sc_destroy begins.
 *
 * Timebase: every input carries a monotonic timestamp in nanoseconds
 * (CLOCK_MONOTONIC / mach_absolute_time converted by the shell). SyncCore
 * never reads a clock itself.
 *
 * Ownership: buffers passed in are consumed/copied before the call returns.
 * Payloads passed to the event callback are valid only for the duration of
 * the callback invocation.
 */
#ifndef SYNCCORE_H
#define SYNCCORE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct sc_session sc_session_t; /* opaque */

typedef enum {
    SC_OK = 0,
    SC_ERR_INVALID_ARG,
    SC_ERR_BAD_STATE,
    SC_ERR_NO_MEMORY,
    SC_ERR_UNSUPPORTED_RATE
} sc_status_t;

typedef enum {
    SC_ROUTE_SPEAKER,
    SC_ROUTE_WIRED,
    SC_ROUTE_BLUETOOTH
} sc_route_t;

typedef enum {
    SC_AEC_OFF,
    SC_AEC_PLATFORM_ONLY,
    SC_AEC_FULL
} sc_aec_mode_t;

typedef struct {
    int32_t sample_rate_hz;            /* REQUIRED: 48000 (only supported rate, v1) */
    int32_t channels;                  /* REQUIRED: 1 */
    sc_route_t initial_route;
    int32_t output_latency_prior_ms;   /* per-route calibrated prior; -1 = default */
    int32_t command_latency_prior_ms;  /* Spotify seek settle prior; -1 = default 250 */
    /* Correction deadband. <=0 = default 25 ms. Field Test 2: recognition
     * noise (±100–150 ms) against the default made every fix an audible
     * micro-seek. MUST live here, not shell-side: a shell that silently
     * drops emitted corrections corrupts the policy's command-latency
     * learning (each unexecuted seek reads as landing bias). */
    int32_t deadband_ms;
} sc_config_t;

/* ---- Lifecycle ---- */

/* Validates cfg, allocates the session (audio ring buffer included — no
 * allocation happens after this call on the capture path) and starts the
 * worker thread. On failure *out is left untouched. */
sc_status_t sc_create(const sc_config_t* cfg, sc_session_t** out);

/* Stops and joins the worker thread, then frees the session. No events are
 * delivered after sc_destroy returns. NULL is a no-op. */
void sc_destroy(sc_session_t*);

/* ---- Real-time input (audio thread ONLY) ---- */

/* Lock-free, allocation-free, never blocks. If the ring buffer is full the
 * block is dropped and an internal overrun counter incremented. Invalid
 * arguments are silently ignored (no RT-safe way to report). */
void sc_push_capture(sc_session_t*, const float* mono, int32_t frames,
                     uint64_t capture_mono_ns);

/* ---- Control-plane inputs (any non-RT thread) ---- */

typedef enum { SC_FIX_SHAZAMKIT, SC_FIX_ACRCLOUD } sc_fix_source_t;

typedef struct {
    sc_fix_source_t source;
    int64_t  match_offset_ms;   /* position in catalog track at capture time */
    uint64_t capture_mono_ns;   /* when the matched audio was captured */
    double   frequency_skew;    /* 0.0 if unknown */
    float    confidence;        /* [0,1]; provider-normalized */
} sc_recognition_fix_t;

sc_status_t sc_submit_recognition_fix(sc_session_t*, const sc_recognition_fix_t*);

typedef struct {
    int64_t  position_ms;       /* Spotify-reported track position */
    bool     is_paused;
    uint64_t received_mono_ns;  /* when the shell received this player state */
} sc_player_state_t;

sc_status_t sc_submit_player_state(sc_session_t*, const sc_player_state_t*);

/* Values outside ±750 ms are clamped. */
sc_status_t sc_set_user_nudge_ms(sc_session_t*, int32_t nudge_ms);

sc_status_t sc_set_output_route(sc_session_t*, sc_route_t, int32_t latency_prior_ms);

sc_status_t sc_set_aec_mode(sc_session_t*, sc_aec_mode_t);

/* Shell MUST call this after executing an SC_EVT_CORRECTION seek so the
 * estimator suppresses measurements during the settle window. */
sc_status_t sc_notify_seek_issued(sc_session_t*, int64_t target_ms,
                                  uint64_t issued_mono_ns);

/* Arms the self-hearing guard (architecture-spec.md §7.3). */
sc_status_t sc_notify_local_playback(sc_session_t*, int64_t commanded_position_ms);

/* Copies up to max_frames of the MOST RECENT capture audio (post-AEC, mono
 * float, chronological order) into out. Returns the number of frames
 * copied (0 if none yet). If out_end_mono_ns is non-NULL it receives the
 * capture timestamp of the last copied frame — the pairing recognition
 * providers need (a match offset references the sample's end). Thread-safe,
 * non-RT (brief mutex against the worker's history writer; never touches
 * the RT ring). The engine retains ~12 s of history. */
/* Clears the retained capture history (and the smoothed input level). Call
 * when the shell (re)opens its capture stream: the ring otherwise survives a
 * capture stop/start, so the first recognition window of a NEW session can
 * contain the tail of the PREVIOUS session's audio — field test 8 watched a
 * fresh join match the prior session's song from exactly that stale tail and
 * confidently play the wrong track. Same disease as arming calibration on a
 * stale clock: consuming input recorded before the epoch it belongs to. */
sc_status_t sc_reset_capture_history(sc_session_t*);

int32_t sc_copy_recent_capture(sc_session_t*, float* out, int32_t max_frames,
                               uint64_t* out_end_mono_ns);

/* ---- AEC reference (synthesized; non-RT thread, chunked) ---- */

sc_status_t sc_push_reference(sc_session_t*, const float* mono, int32_t frames,
                              int64_t track_position_ms);

/* Reads the session's current Spotify command-latency estimate in ms —
 * the config prior refined by online learning from post-seek innovations.
 * Shells persist this per device/route and hand it back as
 * command_latency_prior_ms at the next sc_create, so learning survives
 * cold starts instead of resetting to the default. */
sc_status_t sc_get_command_latency_ms(sc_session_t*, int32_t* out_ms);

/* CAL-05 (technical-requirements.md §2.1): smoothed capture input level,
 * normalized 0..1 (not dBFS — the shell drives a visual treatment
 * directly off the value). A POLLED GETTER, not an event: SC_EVT_* is
 * estimate-driven and doesn't exist until the first recognition fix
 * lands, which would leave LISTENING/MATCHING with no signal at all;
 * a getter needs no such gating and reports silence for free whenever
 * capture is idle or was never started. Independent of calibration and
 * the estimator — valid before the first fix, during calibration, and
 * before/after lock. Ballistics: one-pole attack/release envelope,
 * ~10 ms attack / ~300 ms release, computed on the worker thread
 * alongside the existing post-AEC capture-history append (no new tap
 * into the capture path, never touches the RT audio callback or ring
 * buffer). Written to a relaxed atomic by the worker; this getter does a
 * relaxed load — no lock, no allocation, callable from any thread. */
sc_status_t sc_get_input_level(sc_session_t*, float* out_level);

/* ---- Calibration ---- */

sc_status_t sc_begin_calibration(sc_session_t*);  /* emits SC_EVT_CALIBRATION_RESULT */
sc_status_t sc_cancel_calibration(sc_session_t*);

/* ---- Acoustic referee (CAL-03, technical-requirements.md §2.6) ---- */

/* Non-RT: requests one referee measurement of the residual acoustic sync
 * error. The result arrives asynchronously as SC_EVT_LATENCY_RESIDUAL —
 * this call only enqueues the request; it never blocks and never returns
 * the measurement directly.
 *
 * Single-buffer autocorrelation, no reference signal: runs the ported
 * lag_analyzer algorithm (dsp/lag_window.h) over the post-AEC capture
 * history sc_copy_recent_capture already retains (~12 s). No new audio is
 * captured or played, and sc_push_reference is never consulted — nothing
 * calls it in production, and it would require a decoded copy of the
 * room's audio, which is exactly what this measurement doesn't need. The
 * mic hears two time-shifted copies of the same program material during
 * speaker/BT-speaker playback (ours and the room's); the lag between them
 * IS the acoustic sync error a listener perceives. Search range is fixed
 * at [40, 2500] ms — the 2500 ms ceiling is load-bearing (see the
 * SC_EVT_LATENCY_RESIDUAL payload doc below) and is not a parameter.
 *
 * Gated: SC_EVT_LATENCY_RESIDUAL.valid is false unless the estimator's
 * current position is converged (LOCKED, i.e. the same condition
 * SC_EVT_SYNC_ESTIMATE.converged reports) AND the measured peak_ratio
 * exceeds 4.0. While locked the position error is ~0, so any acoustic gap
 * this window finds is attributable to output-chain latency, not
 * estimator error; sampling while unconverged would conflate the two. A
 * route with no acoustic path back into the mic (e.g. headphones) or a
 * room that has gone silent or changed tracks naturally fails the
 * peak_ratio gate on its own — there is no separate route check.
 *
 * Forces AEC off for the duration of the sample, then restores whatever
 * mode was active immediately before — AEC would otherwise cancel the
 * very echo of our own output this measures. This is a verifier, not a
 * servo: it never writes output_latency_prior_ms or any other live
 * control state, only measures and emits. Aggregating repeated samples
 * into a calibration profile is shell-side (a later ticket). */
sc_status_t sc_sample_latency_residual(sc_session_t*);

/* ---- Events out ---- */

typedef enum {
    SC_EVT_SYNC_ESTIMATE,      /* payload: sc_evt_sync_estimate_t */
    SC_EVT_CORRECTION,         /* payload: sc_evt_correction_t (shell must seek) */
    SC_EVT_REQUEST_FIX,        /* payload: NULL — run a recognition pass now */
    SC_EVT_FIX_REJECTED,       /* payload: sc_evt_fix_rejected_t */
    SC_EVT_TRACK_LOST,         /* payload: NULL */
    SC_EVT_CALIBRATION_RESULT, /* payload: sc_evt_calibration_result_t */
    SC_EVT_LATENCY_RESIDUAL    /* payload: sc_evt_latency_residual_t (CAL-03) */
} sc_event_type_t;

typedef struct {
    double   error_ms;          /* + = local ahead of external */
    double   drift_ppm;
    float    confidence;        /* [0,1] */
    bool     converged;         /* 3 consecutive fixes inside deadband */
    uint64_t last_fix_mono_ns;
} sc_evt_sync_estimate_t;

typedef struct {
    int64_t seek_to_ms;
} sc_evt_correction_t;

typedef enum {
    SC_REJECT_SELF_HEARING,
    SC_REJECT_LOW_CONFIDENCE,
    SC_REJECT_SETTLING
} sc_reject_reason_t;

typedef struct {
    sc_reject_reason_t reason;
} sc_evt_fix_rejected_t;

typedef struct {
    int32_t measured_latency_ms;
    bool    valid;
} sc_evt_calibration_result_t;

/* CAL-03 acoustic referee result (technical-requirements.md §2.6). Emitted
 * only in response to sc_sample_latency_residual, never spontaneously. */
typedef struct {
    int32_t residual_ms;  /* lag between the two capture copies, ms */
    float   peak_ratio;   /* autocorrelation peak / mean(|autocorrelation|)
                            * over the search window; > 4.0 required for
                            * valid (mirrors lag_analyzer's own "found" rule) */
    bool    valid;         /* false unless converged (LOCKED) AND
                            * peak_ratio > 4.0; residual_ms/peak_ratio are
                            * still populated when false so callers can log
                            * the near-miss, but must not act on them */
} sc_evt_latency_residual_t;

typedef void (*sc_event_cb)(sc_event_type_t, const void* payload, void* user_data);

/* Replaces any previous callback. Pass NULL to clear. Guaranteed: after this
 * call returns, a cleared/replaced callback will not be invoked again. */
sc_status_t sc_set_event_callback(sc_session_t*, sc_event_cb, void* user_data);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* SYNCCORE_H */
