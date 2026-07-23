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

/* ---- Calibration ---- */

sc_status_t sc_begin_calibration(sc_session_t*);  /* emits SC_EVT_CALIBRATION_RESULT */
sc_status_t sc_cancel_calibration(sc_session_t*);

/* ---- Events out ---- */

typedef enum {
    SC_EVT_SYNC_ESTIMATE,      /* payload: sc_evt_sync_estimate_t */
    SC_EVT_CORRECTION,         /* payload: sc_evt_correction_t (shell must seek) */
    SC_EVT_REQUEST_FIX,        /* payload: NULL — run a recognition pass now */
    SC_EVT_FIX_REJECTED,       /* payload: sc_evt_fix_rejected_t */
    SC_EVT_TRACK_LOST,         /* payload: NULL */
    SC_EVT_CALIBRATION_RESULT  /* payload: sc_evt_calibration_result_t */
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

typedef void (*sc_event_cb)(sc_event_type_t, const void* payload, void* user_data);

/* Replaces any previous callback. Pass NULL to clear. Guaranteed: after this
 * call returns, a cleared/replaced callback will not be invoked again. */
sc_status_t sc_set_event_callback(sc_session_t*, sc_event_cb, void* user_data);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* SYNCCORE_H */
