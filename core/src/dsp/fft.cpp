#include "dsp/fft.h"

#include <cstdlib>

namespace synccore {

size_t next_pow2(size_t n) {
    size_t p = 1;
    while (p < n) p <<= 1;
    return p;
}

RealFft::RealFft(size_t nfft) : nfft_(nfft) {
    fwd_ = kiss_fftr_alloc(static_cast<int>(nfft_), 0, nullptr, nullptr);
    inv_ = kiss_fftr_alloc(static_cast<int>(nfft_), 1, nullptr, nullptr);
    if (!fwd_ || !inv_) {
        // One of the two allocations may have succeeded; free it so a
        // failed construction never leaks either handle.
        std::free(fwd_);
        std::free(inv_);
        fwd_ = nullptr;
        inv_ = nullptr;
    }
}

RealFft::~RealFft() {
    std::free(fwd_);
    std::free(inv_);
}

void RealFft::forward(const std::vector<float>& time,
                      std::vector<kiss_fft_cpx>& freq) const {
    freq.resize(nbins());
    kiss_fftr(fwd_, time.data(), freq.data());
}

void RealFft::inverse(const std::vector<kiss_fft_cpx>& freq,
                      std::vector<float>& time) const {
    time.resize(nfft_);
    kiss_fftri(inv_, freq.data(), time.data());
}

}  // namespace synccore
