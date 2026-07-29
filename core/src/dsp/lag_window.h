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

    // CTL-03a: comb-flatness diagnostics. Wholly additive — computed by a
    // second pass over the same autocorrelation array after lag_ms/
    // peak_ratio/found are already final; that argmax/peak_ratio/found
    // computation is byte-identical to the pre-CTL-03a behavior (see the
    // "do not improve the math" warning above; the field-test corpus grades
    // exactly that computation and nothing here changes it).
    //
    // second_lag_ms: the lag, in ms, of the strongest autocorrelation value
    // found *outside* a +/-20 ms exclusion neighborhood around lag_ms. The
    // exclusion exists so the best peak's own shoulder (naturally elevated
    // for several ms/tens of ms around a true peak) can never be mistaken
    // for an independent competing lag. Sentinel: 0 means "no second
    // candidate exists outside the exclusion band" (e.g. the requested
    // [min_lag_ms, max_lag_ms] search range is itself narrower than the
    // exclusion band) — it is NOT a claim that a competing lag sits at 0 ms.
    //
    // comb_ratio: best peak's raw autocorrelation value divided by the raw
    // value at second_lag_ms (max of v, not |v| — mirrors the best-peak
    // scan exactly, since autocorrelation values can be negative). Nothing
    // here is clamped: comb_ratio can be enormous (one dominant, unambiguous
    // lag) or negative (the runner-up lag's raw value is itself negative).
    // Once a second candidate exists and both values are positive,
    // comb_ratio >= 1; ~1.0-1.5 reads as a flat comb of several near-equal
    // teeth (ambiguous — the Billie Jean harmonic-churn class), a high ratio
    // (consumers threshold at roughly 2.0) reads as one unambiguous copy-lag.
    // Sentinel: 0 both when no second candidate exists (mirrors
    // second_lag_ms's sentinel) and, as a division guard, when the
    // second-best raw value is exactly 0 — either way, 0/negative means "no
    // meaningful competitor," consistent with the ratio's own semantics.
    double second_lag_ms = 0;
    double comb_ratio = 0;
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
//
// CTL-03a: after the argmax/peak_ratio/found computation (unchanged), a
// second, additive pass over the same already-computed autocorrelation
// array fills WindowLag::second_lag_ms/comb_ratio — see WindowLag's doc
// comment for the exclusion rule and sentinel semantics.
WindowLag analyze_window(const float* x, size_t n, int rate,
                         double min_lag_ms, double max_lag_ms);

}  // namespace synccore

#endif  // SYNCCORE_DSP_LAG_WINDOW_H
