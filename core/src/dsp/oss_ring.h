// oss_ring.h — DSP-01a: incremental onset-strength ring & tempogram
// (tech-req §2.10).
//
// New capability, not a modification of any existing module: computes a
// beat-period estimate from spectral-flux onset strength, entirely
// separate from lag_window.cpp's single-buffer copy-lag search. Reuses
// the house RealFft(1024) wrapper (dsp/fft.h) rather than adding a second
// FFT path.
//
// Worker-thread, non-RT home, mirroring CorrectionPolicy's own fixed-size
// rings (CTL-01a's referee ring, CTL-02a's error ring): push() is meant to
// run off the same post-AEC capture tap append_history() already sees
// (wired by the DSP-01b consumer ticket, out of this ticket's scope);
// estimate_beat_period() runs on demand, not per frame. All storage is
// sized once at construction; no heap allocation occurs afterward (see the
// allocation-guard test in test_oss_ring.cpp, mirroring test_synccore.cpp's
// operator-new hook).
#ifndef SYNCCORE_DSP_OSS_RING_H
#define SYNCCORE_DSP_OSS_RING_H

#include <cstddef>
#include <cstdint>
#include <vector>

#include "dsp/fft.h"

namespace synccore {

// DSP-01b (§2.10/§2.8 cross-check): k ranges over
// [1, kMaxBeatHarmonicMultiplier]. Per the PM directive,
// kMaxBeatHarmonicMultiplier = 4 — §2.10's spec text says only "a small
// integer k," so 4 is within that latitude; note this is wider than the
// ticket's own negative test, which only exercises k = 1..3 (see
// docs/dsp01b-review.md), so k = 4's corroborating behavior is pinned by a
// dedicated positive test rather than inherited from that negative case.
constexpr int kMaxBeatHarmonicMultiplier = 4;
// Agreement tolerance for the cross-check, ms — matches §2.8/§2.10's own
// "|second_lag_ms - k*beat_period_ms| < 30 ms" wording verbatim.
constexpr double kBeatCombAgreeMs = 30.0;

// tech-req §2.10's confidence contract, verbatim: `salience` is a
// diagnostics/CSV-only export — `s(l*) / mean(s)` over the search band,
// an extreme-value statistic computed over a single array, exactly the
// kind of number test_lag_window.cpp's sqrt(2 ln N) comment (and tech-req
// §2.6) warns cannot be trusted as a threshold. `stable` is gated ONLY by
// the three-estimate agreement-over-time rule below; no caller may add a
// salience floor to `stable` or to anything downstream of it.
//
// MHT hypothesis-bank seeding contract (DSP-01b, tech-req §2.10's
// "Consumers" list; research-closed-loop-control.md §5 item 3): the FUTURE
// hypothesis bank — not implemented by this ticket, its own separately
// specced work — seeds its per-fix hypothesis offsets at
// `fix_offset ± k*beat_period_ms`, k = 1..3, once `stable` is true,
// replacing a single-window guess at the comb spacing. No bank code exists
// anywhere in this tree yet. Restated per the standing hard limits (§2.10):
// neither the future bank nor the §2.8 cross-check below ever touches
// self-match handling — CTL-01 (§2.9) owns that problem exclusively — and
// `salience`/`peak_ratio` are never evidence for either.
struct BeatEstimate {
    double period_ms = 0;
    double salience = 0;
    bool stable = false;
};

// §2.10/§2.8 cross-check (DSP-01b): true when second_lag_ms sits within
// kBeatCombAgreeMs of a small integer multiple k in
// [1, kMaxBeatHarmonicMultiplier] of a STABLE beat period — the competitor
// autocorrelation peak analyze_window found is then corroborated as the
// music's own beat comb (the Billie Jean harmonic-churn class), not a
// genuine second copy. Read-only diagnostic; never gates a correction, and
// never touches self-match handling (CTL-01's exclusive territory) — this
// is a pure function of its two scalar/struct arguments, nothing else.
//
// False unless beat.stable, beat.period_ms > 0, and second_lag_ms > 0 (0
// is WindowLag::second_lag_ms's own "no competitor" sentinel — see
// lag_window.h).
bool beat_comb_corroborated(double second_lag_ms, const BeatEstimate& beat);

class OnsetStrengthRing {
public:
    explicit OnsetStrengthRing(int rate_hz = 48000);

    // Incremental: accumulates `samples` (any n, including sub-frame and
    // multi-frame blocks) into the internal kFrameSize-sample overlap
    // buffer, running one frame of spectral-flux processing each time a
    // fresh kHopSize-sample hop becomes available. end_mono_ns is the
    // capture timestamp of the last sample in this call, retained for
    // bookkeeping; estimate_beat_period's own now_ns argument is what
    // actually timestamps a beat estimate for the stability history.
    void push(const float* samples, size_t n, uint64_t end_mono_ns);

    // On-demand unbiased normalized autocorrelation over the OSS ring's
    // current contents (tech-req §2.10). now_ns timestamps this call for
    // the stability history. Returns {0, 0, false} when there isn't yet
    // enough OSS history to fill the full lag range the harmonic sum
    // needs, or when r(0) <= 0 (silent/degenerate signal).
    BeatEstimate estimate_beat_period(uint64_t now_ns);

    // Epoch rule: clears the OSS ring, the frame-accumulation/flux-delay
    // state, and the stability history. A new capture stream must never
    // let a beat estimate survive into it — mirrors
    // CorrectionPolicy::reset() and §2.7's persistence-ring clearing.
    void reset();

    // The sample rate the caller constructed this ring with. Frame/hop
    // sizes and kOssRateHz are fixed per tech-req §2.10's 48 kHz-derived
    // constants regardless of this value; retained for callers/tests that
    // need to confirm what a session was configured with.
    int rate_hz() const { return rate_hz_; }

private:
    // --- STFT frame/hop constants (tech-req §2.10) ---
    static constexpr size_t kFrameSize = 1024;  // N: 21.33 ms @ 48 kHz
    static constexpr size_t kHopSize = 512;     // H: 10.67 ms @ 48 kHz
    static constexpr size_t kNumBins =
        kFrameSize / 2 + 1;  // RealFft(1024)'s real-spectrum size

    // Log-compression gain, Y(m,k) = ln(1 + gamma*|X(m,k)|). Cited from
    // model knowledge (Peeters 2007; Grosche & Mueller 2011), NOT yet
    // retrieved against the primary sources per docs/REFERENCES.md's
    // retrieval-honesty convention — tech-req §2.10 marks this
    // provisional and field-tunable. Do not silently treat it as final
    // the way confirm_floor_ms (policy.h) was after its RFC 5905
    // grounding resolved.
    static constexpr double kLogCompressionGamma = 100.0;

    // Local-mean removal delay line (tech-req §2.10): the causal form of
    // o(m) = max(0, Delta(m) - mean(Delta(m-W..m+W))), W ~= 47 frames
    // (~0.5 s), implemented as a 94-sample running-sum window. The
    // emitted o(.) corresponds to the flux value kFluxDelayHalfWindow
    // frames behind the newest sample currently in the window — the
    // resulting ~0.5 s output delay is irrelevant, per spec, since
    // estimate_beat_period reads 12 s of ring history, not the newest
    // sample.
    static constexpr size_t kFluxDelayHalfWindow = 47;
    static constexpr size_t kFluxWindowLen = 2 * kFluxDelayHalfWindow;  // 94

    // OSS ring: M=1125 samples at F_oss=93.75 Hz ~= 12 s, mirroring the
    // post-AEC PCM history ring's span. Per tech-req §2.10's "Correction
    // to the original brief": this is a new ring, not a reinterpretation
    // of the existing 12 s PCM history (which stores no spectral
    // structure).
    static constexpr size_t kOssRingCapacity = 1125;
    static constexpr double kOssRateHz = 93.75;  // 48000 / kHopSize

    // Autocorrelation search (tech-req §2.10): lag bins 24..112 <=> ~250..
    // 1200 ms <=> 240..50 BPM. The harmonic sum needs r_hat out to
    // 2*kSearchLagMax = 224, computed over the FULL lag range (not just
    // the search band) — see estimate_beat_period.
    static constexpr size_t kSearchLagMin = 24;
    static constexpr size_t kSearchLagMax = 112;
    static constexpr size_t kMaxHarmonicLag = 2 * kSearchLagMax;  // 224
    // Harmonic-sum octave-disambiguation weight,
    // s(l) = r_hat(l) + kHarmonicSumWeight * r_hat(2l). Same
    // provisional/field-tunable status as kLogCompressionGamma above
    // (tech-req §2.10) — not yet checked against Peeters 2007 / Grosche &
    // Mueller 2011.
    static constexpr double kHarmonicSumWeight = 0.5;
    // Below this many valid OSS samples, r(kMaxHarmonicLag) would have no
    // terms to average (count - kMaxHarmonicLag must be > 0) — treated as
    // "insufficient data," same bucket as r(0) <= 0 and an empty ring.
    static constexpr size_t kMinValidOssSamples = kMaxHarmonicLag + 1;

    // Stability contract (tech-req §2.10), reusing the §2.7
    // confirm_window_ns idiom rather than inventing a new agreement rule:
    // the last 3 estimates must agree within kStableAgreeMs and span at
    // least kStableWindowNs.
    static constexpr double kStableAgreeMs = 10.0;
    static constexpr uint64_t kStableWindowNs = 20'000'000'000ull;
    static constexpr size_t kHistoryCapacity = 8;

    void process_frame();
    void push_flux(double delta);
    void oss_ring_push(double o);
    double oss_at(size_t m) const;

    struct HistorySample {
        double period_ms = 0.0;
        uint64_t mono_ns = 0;
    };
    void history_append(double period_ms, uint64_t mono_ns);
    const HistorySample& history_at(size_t m) const;

    // FFT + windowing scratch. Sized once at construction and reused every
    // frame — RealFft::forward's internal freq.resize(nbins()) call
    // (fft.h/.cpp) is a no-op once the target vector already has that
    // size, so no allocation happens on the steady-state per-frame path.
    RealFft fft_;
    std::vector<float> hann_;          // kFrameSize, precomputed in ctor
    std::vector<float> stage_;         // kFrameSize overlap/accumulation buffer
    std::vector<float> windowed_;      // kFrameSize, windowed frame scratch
    std::vector<kiss_fft_cpx> spec_;   // kNumBins
    size_t stage_fill_ = 0;

    double prev_y_[kNumBins] = {};
    bool have_prev_y_ = false;

    double flux_ring_[kFluxWindowLen] = {};
    size_t flux_write_ = 0;
    size_t flux_count_ = 0;
    double flux_running_sum_ = 0.0;

    double oss_ring_[kOssRingCapacity] = {};
    size_t oss_write_ = 0;
    size_t oss_count_ = 0;

    HistorySample history_[kHistoryCapacity];
    size_t history_write_ = 0;
    size_t history_count_ = 0;
    // frames_emitted_ as of the most recent history append. Frozen-ring
    // guard (see estimate_beat_period): a poll that arrives with no new
    // OSS frame since the last append must not stack a duplicate entry
    // into the stability history — session time can advance while capture
    // is stalled (player-state timestamps advance wk.now_ns with no audio
    // flowing), and three such polls spanning 20 s would otherwise latch
    // `stable` off a single frozen window, defeating §2.10's
    // reproducibility-across-independent-windows intent.
    uint64_t frames_at_last_history_ = 0;

    uint64_t frames_emitted_ = 0;
    uint64_t last_push_mono_ns_ = 0;
    int rate_hz_;
};

}  // namespace synccore

#endif  // SYNCCORE_DSP_OSS_RING_H
