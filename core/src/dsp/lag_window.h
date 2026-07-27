// lag_window.h — CAL-02: ported single-buffer autocorrelation lag finder.
//
// This is `lag_analyzer`'s `analyze_window`, moved into core so on-device
// code (CAL-03's acoustic referee) can call the exact same algorithm that
// has graded every field test. Ported verbatim — do not "improve" the
// math here without re-running the field-test corpus (docs/sync-test-results.md).
#ifndef SYNCCORE_DSP_LAG_WINDOW_H
#define SYNCCORE_DSP_LAG_WINDOW_H

#include <cstddef>

namespace synccore {

struct WindowLag {
    double lag_ms = 0;
    double peak_ratio = 0;
    bool found = false;
};

// Autocorrelation of one capture window via its power spectrum; searches for
// a secondary peak in [min_lag_ms, max_lag_ms] — the lag between two
// time-shifted copies of the same signal inside `x` (e.g. room speaker +
// device speaker both audible to one mic, or a device's own output leaking
// back into its own mic through room reverb).
//
// Whitening here is deliberately NOT the full PHAT that gcc_phat uses:
// gcc_phat whitens a *cross*-spectrum against a known reference (the chirp),
// where full-flat whitening is safe because the reference spectrum is known
// clean. This is an *autocorrelation* of a single mixed, unknown capture
// (music of unknown, non-flat spectrum) — full PHAT would also whiten away
// the music's own spectral shape and make the copy-lag peak unstable
// against ordinary program material. Mild whitening (retaining sqrt(power),
// i.e. p = (r²+i²)/mag with mag = sqrt(r²+i²)+1e-9) sharpens the peak while
// tolerating that structure. Do not unify the two.
//
// `n` should be the 8 s window length; `nfft` internally is
// next_pow2(n*2). `min_lag_ms`/`max_lag_ms` bound the search and are
// clamped to nfft/2 - 1; if min_lag >= max_lag after clamping, the window is
// unanalyzable and `found` stays false.
WindowLag analyze_window(const float* x, size_t n, int rate,
                         double min_lag_ms, double max_lag_ms);

}  // namespace synccore

#endif  // SYNCCORE_DSP_LAG_WINDOW_H
