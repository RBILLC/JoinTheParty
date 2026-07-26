// lag_analyzer — full-loop field-test ground truth (convergence audit §4.1).
//
// Input: a PCM16 WAV recorded by a PC microphone that hears TWO devices
// playing the same song (the "room" phone and the JoinTheParty phone). The
// recording therefore contains two time-shifted copies of one signal; the
// autocorrelation of each analysis window has a secondary peak at the lag
// between the copies — the TRUE audible sync error, independent of
// anything the app believes about itself.
//
// Output: CSV on stdout — window_start_s, lag_ms, peak_ratio — ready to
// overlay against the engine's `sync err` trace.
//
//   lag_analyzer recording.wav [--min-lag-ms 40] [--max-lag-ms 2500]
//   lag_analyzer --selftest
//
// Desktop-only tool; built beside the test suite.

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "kiss_fft.h"
#include "kiss_fftr.h"

namespace {

struct Wav {
    int sample_rate = 0;
    std::vector<float> mono;
};

bool read_wav_pcm16(const char* path, Wav* out) {
    FILE* f = std::fopen(path, "rb");
    if (!f) return false;
    auto rd32 = [&](uint32_t* v) { return std::fread(v, 4, 1, f) == 1; };
    auto rd16 = [&](uint16_t* v) { return std::fread(v, 2, 1, f) == 1; };

    char tag[5] = {0};
    uint32_t riff_size = 0;
    if (std::fread(tag, 1, 4, f) != 4 || std::strcmp(tag, "RIFF") != 0 ||
        !rd32(&riff_size) || std::fread(tag, 1, 4, f) != 4 ||
        std::strcmp(tag, "WAVE") != 0) {
        std::fclose(f);
        return false;
    }

    uint16_t channels = 0, bits = 0;
    uint32_t rate = 0;
    bool got_fmt = false;
    for (;;) {
        uint32_t chunk_size = 0;
        if (std::fread(tag, 1, 4, f) != 4 || !rd32(&chunk_size)) break;
        if (std::strcmp(tag, "fmt ") == 0) {
            uint16_t fmt_code = 0, block = 0;
            uint32_t byte_rate = 0;
            rd16(&fmt_code); rd16(&channels); rd32(&rate); rd32(&byte_rate);
            rd16(&block); rd16(&bits);
            if (chunk_size > 16) std::fseek(f, chunk_size - 16, SEEK_CUR);
            got_fmt = (fmt_code == 1 && bits == 16 && channels >= 1);
        } else if (std::strcmp(tag, "data") == 0 && got_fmt) {
            const size_t samples = chunk_size / 2;
            std::vector<int16_t> raw(samples);
            const size_t got = std::fread(raw.data(), 2, samples, f);
            out->sample_rate = static_cast<int>(rate);
            out->mono.resize(got / channels);
            for (size_t i = 0; i < out->mono.size(); ++i) {
                int32_t acc = 0;
                for (int c = 0; c < channels; ++c)
                    acc += raw[i * channels + static_cast<size_t>(c)];
                out->mono[i] =
                    static_cast<float>(acc) / (32768.0f * static_cast<float>(channels));
            }
            std::fclose(f);
            return !out->mono.empty();
        } else {
            std::fseek(f, static_cast<long>(chunk_size + (chunk_size & 1)), SEEK_CUR);
        }
    }
    std::fclose(f);
    return false;
}

size_t next_pow2(size_t n) {
    size_t p = 1;
    while (p < n) p <<= 1;
    return p;
}

struct WindowLag {
    double lag_ms = 0;
    double peak_ratio = 0;
    bool found = false;
};

// Autocorrelation via power spectrum; secondary-peak search in [min,max] lag.
WindowLag analyze_window(const float* x, size_t n, int rate, double min_lag_ms,
                         double max_lag_ms) {
    WindowLag result;
    const size_t nfft = next_pow2(n * 2);
    std::vector<float> padded(nfft, 0.0f);
    std::memcpy(padded.data(), x, n * sizeof(float));

    kiss_fftr_cfg fwd = kiss_fftr_alloc(static_cast<int>(nfft), 0, nullptr, nullptr);
    kiss_fftr_cfg inv = kiss_fftr_alloc(static_cast<int>(nfft), 1, nullptr, nullptr);
    if (!fwd || !inv) { free(fwd); free(inv); return result; }

    std::vector<kiss_fft_cpx> spec(nfft / 2 + 1);
    kiss_fftr(fwd, padded.data(), spec.data());
    for (auto& b : spec) {
        // Mild whitening (power^0.5 kept) sharpens the copy-lag peak while
        // tolerating the music's own periodicity.
        const float mag = std::sqrt(b.r * b.r + b.i * b.i) + 1e-9f;
        const float p = (b.r * b.r + b.i * b.i) / mag;
        b.r = p;
        b.i = 0.0f;
    }
    std::vector<float> ac(nfft);
    kiss_fftri(inv, spec.data(), ac.data());
    free(fwd);
    free(inv);

    const size_t min_lag = static_cast<size_t>(min_lag_ms * rate / 1000.0);
    const size_t max_lag = std::min(
        static_cast<size_t>(max_lag_ms * rate / 1000.0), nfft / 2 - 1);
    if (min_lag >= max_lag) return result;

    size_t best = min_lag;
    double best_v = -1e30, sum = 0;
    size_t count = 0;
    for (size_t k = min_lag; k <= max_lag; ++k) {
        const double v = ac[k];
        if (v > best_v) { best_v = v; best = k; }
        sum += std::abs(v);
        ++count;
    }
    const double mean = count ? sum / static_cast<double>(count) : 1.0;
    result.lag_ms = 1000.0 * static_cast<double>(best) / rate;
    result.peak_ratio = mean > 0 ? best_v / mean : 0;
    result.found = result.peak_ratio > 4.0;
    return result;
}

int run(const Wav& wav, double min_lag_ms, double max_lag_ms) {
    const int rate = wav.sample_rate;
    const size_t win = static_cast<size_t>(8.0 * rate);   // 8 s windows
    const size_t hop = static_cast<size_t>(2.0 * rate);   // 2 s hop
    std::printf("window_start_s,lag_ms,peak_ratio,confident\n");
    for (size_t start = 0; start + win <= wav.mono.size(); start += hop) {
        const auto r = analyze_window(wav.mono.data() + start, win, rate,
                                      min_lag_ms, max_lag_ms);
        std::printf("%.1f,%.1f,%.2f,%d\n",
                    static_cast<double>(start) / rate, r.lag_ms, r.peak_ratio,
                    r.found ? 1 : 0);
    }
    return 0;
}

int selftest() {
    // Pseudo-music: filtered noise, plus a copy delayed 800 ms at -6 dB.
    const int rate = 48000;
    const double lag_s = 0.8;
    const size_t n = static_cast<size_t>(20.0 * rate);
    std::vector<float> sig(n, 0.0f);
    uint32_t s = 12345;
    float lp = 0.0f;
    for (size_t i = 0; i < n; ++i) {
        s = s * 1664525u + 1013904223u;
        const float white = (static_cast<float>(s >> 8) / 8388608.0f) - 1.0f;
        lp += 0.08f * (white - lp);  // crude low-pass → music-ish spectrum
        sig[i] = lp;
    }
    Wav wav;
    wav.sample_rate = rate;
    wav.mono.resize(n);
    const size_t d = static_cast<size_t>(lag_s * rate);
    for (size_t i = 0; i < n; ++i) {
        wav.mono[i] = sig[i] + (i >= d ? 0.5f * sig[i - d] : 0.0f);
    }
    const auto r = analyze_window(wav.mono.data(), static_cast<size_t>(8.0 * rate),
                                  rate, 40, 2500);
    std::printf("selftest: lag=%.1fms (expect 800±5) ratio=%.2f found=%d\n",
                r.lag_ms, r.peak_ratio, r.found ? 1 : 0);
    const bool ok = r.found && std::abs(r.lag_ms - 800.0) <= 5.0;
    std::printf(ok ? "selftest PASS\n" : "selftest FAIL\n");
    return ok ? 0 : 1;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc >= 2 && std::string(argv[1]) == "--selftest") return selftest();
    if (argc < 2) {
        std::fprintf(stderr, "usage: lag_analyzer <recording.wav> | --selftest\n");
        return 2;
    }
    double min_lag = 40, max_lag = 2500;
    for (int i = 2; i + 1 < argc; i += 2) {
        if (std::string(argv[i]) == "--min-lag-ms") min_lag = std::atof(argv[i + 1]);
        if (std::string(argv[i]) == "--max-lag-ms") max_lag = std::atof(argv[i + 1]);
    }
    Wav wav;
    if (!read_wav_pcm16(argv[1], &wav)) {
        std::fprintf(stderr, "failed to read %s (PCM16 WAV required)\n", argv[1]);
        return 2;
    }
    std::fprintf(stderr, "loaded %.1fs @ %dHz\n",
                 static_cast<double>(wav.mono.size()) / wav.sample_rate,
                 wav.sample_rate);
    return run(wav, min_lag, max_lag);
}
