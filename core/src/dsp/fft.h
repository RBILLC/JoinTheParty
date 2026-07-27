// fft.h — shared kissfft plumbing (CAL-02).
//
// Both correlate.cpp's gcc_phat (cross-correlation against a known needle)
// and lag_window.cpp's analyze_window (autocorrelation of a mixed capture)
// need the identical alloc/zero-pad/forward/operate-on-spectrum/inverse
// sequence around kissfft's real-FFT API. This header is the one place that
// sequence — and the raw kissfft cfg alloc/free — lives.
#ifndef SYNCCORE_DSP_FFT_H
#define SYNCCORE_DSP_FFT_H

#include <cstddef>
#include <vector>

#include "kiss_fft.h"
#include "kiss_fftr.h"

namespace synccore {

// Smallest power of two >= n (1 if n == 0). kissfft's real transform wants
// an even size; callers pick n so next_pow2(n) satisfies that (it always
// does for n > 1).
size_t next_pow2(size_t n);

// RAII owner of a matched forward+inverse pair of kissfft real-FFT configs
// for one fixed transform size. Both call sites need forward once or twice
// then inverse once at the same nfft, so bundling the pair avoids repeating
// the alloc/null-check/free dance kissfft otherwise requires by hand.
class RealFft {
public:
    // nfft must be even (kissfft's real-FFT requirement); pass next_pow2(...).
    explicit RealFft(size_t nfft);
    ~RealFft();

    RealFft(const RealFft&) = delete;
    RealFft& operator=(const RealFft&) = delete;

    // False if either underlying kissfft config failed to allocate. Callers
    // must check this before calling forward()/inverse().
    bool valid() const { return fwd_ != nullptr && inv_ != nullptr; }

    size_t nfft() const { return nfft_; }
    size_t nbins() const { return nfft_ / 2 + 1; }  // real-FFT spectrum size

    // time.size() must equal nfft(). freq is resized to nbins().
    void forward(const std::vector<float>& time,
                 std::vector<kiss_fft_cpx>& freq) const;

    // freq.size() must equal nbins(). time is resized to nfft() (unscaled,
    // matching kissfft's kiss_fftri convention — same as the code this
    // replaces).
    void inverse(const std::vector<kiss_fft_cpx>& freq,
                 std::vector<float>& time) const;

private:
    size_t nfft_;
    kiss_fftr_cfg fwd_ = nullptr;
    kiss_fftr_cfg inv_ = nullptr;
};

}  // namespace synccore

#endif  // SYNCCORE_DSP_FFT_H
