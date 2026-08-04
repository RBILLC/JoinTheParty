// synccore_testing.h — introspection hooks for core/tests only.
// Deliberately NOT installed with the public headers: shells must never
// depend on these symbols.
#ifndef SYNCCORE_TESTING_H
#define SYNCCORE_TESTING_H

#include <stdint.h>

#include "synccore/synccore.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Reads the capture-path counters: total frames drained by the worker and
 * blocks dropped because the ring was full. */
void sc_test_stats(sc_session_t*, uint64_t* frames_consumed,
                   uint64_t* overrun_blocks);

/* CAL-03: reads the AEC mode currently in effect on the worker. Used to
 * confirm sc_sample_latency_residual restores the prior mode after its
 * forced-OFF measurement window. */
void sc_test_get_aec_mode(sc_session_t*, sc_aec_mode_t* out_mode);

/* DSP-01b: reads the worker's most recent OSS tempogram state — the
 * beat_period_ms mirror (0.0 if no estimate has been computed yet, or
 * since the last kTrackLost epoch reset) and the §2.8 beat-comb
 * cross-check flag (0/1) from the most recent kSampleLatencyResidual
 * analysis moment, the only place estimate_beat_period is polled. */
void sc_test_get_beat_state(sc_session_t*, int32_t* out_beat_comb,
                            double* out_beat_period_ms);

/* DSP-03a: reads the worker's most recent duck-detector mirrors -- dip_db
 * (matched-filter dip depth D, in dB) and z (its MAD-normalized
 * significance) from the last completed deferred analysis. Both are 0.0 if
 * no analysis has ever run, or since the last kTrackLost epoch reset. */
void sc_test_get_duck_metrics(sc_session_t*, double* out_dip_db,
                              double* out_z);

#ifdef __cplusplus
}
#endif

#endif  // SYNCCORE_TESTING_H
