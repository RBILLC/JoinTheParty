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

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#endif

#include "dsp/lag_window.h"

namespace {

using synccore::WindowLag;
using synccore::analyze_window;

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

// Live mode: raw s16le PCM on stdin (from e.g. ffmpeg -f s16le -), one lag
// line every `hop` seconds. Same analysis as the file path, but the room can
// be watched WHILE the app is running instead of after the fact — which is
// what makes it usable as a test instrument rather than a post-mortem.
int run_stream(int rate, int channels, double min_lag_ms, double max_lag_ms) {
    const size_t win = static_cast<size_t>(8.0 * rate);
    const size_t hop = static_cast<size_t>(2.0 * rate);
    std::vector<float> buf;
    buf.reserve(win + hop);
    std::vector<int16_t> chunk(static_cast<size_t>(4096 * channels));
    size_t since_last = 0;
    double t = 0.0;

    std::fprintf(stderr, "stream: %d Hz, %d ch, 8s window / 2s hop\n", rate,
                 channels);
    std::printf("t_s,lag_ms,peak_ratio,confident,rms_db\n");
    std::fflush(stdout);

    for (;;) {
        const size_t got =
            std::fread(chunk.data(), sizeof(int16_t), chunk.size(), stdin);
        if (got == 0) break;
        const size_t frames = got / static_cast<size_t>(channels);
        for (size_t i = 0; i < frames; ++i) {
            int32_t acc = 0;
            for (int c = 0; c < channels; ++c)
                acc += chunk[i * static_cast<size_t>(channels) +
                             static_cast<size_t>(c)];
            buf.push_back(static_cast<float>(acc) /
                          (32768.0f * static_cast<float>(channels)));
        }
        t += static_cast<double>(frames) / rate;
        since_last += frames;
        if (buf.size() > win) buf.erase(buf.begin(), buf.begin() + static_cast<long>(buf.size() - win));
        if (buf.size() < win || since_last < hop) continue;
        since_last = 0;

        double sum_sq = 0.0;
        for (size_t i = 0; i < win; ++i) sum_sq += buf[i] * buf[i];
        const double rms = std::sqrt(sum_sq / static_cast<double>(win));
        const double rms_db = 20.0 * std::log10(rms + 1e-9);

        const auto r =
            analyze_window(buf.data(), win, rate, min_lag_ms, max_lag_ms);
        std::printf("%.0f,%.0f,%.2f,%d,%.1f\n", t, r.lag_ms, r.peak_ratio,
                    r.found ? 1 : 0, rms_db);
        std::fflush(stdout);
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
        std::fprintf(stderr,
                     "usage: lag_analyzer <recording.wav> | --stream | "
                     "--selftest\n");
        return 2;
    }
    double min_lag = 40, max_lag = 2500;
    int rate = 44100, channels = 1;
    for (int i = 2; i + 1 < argc; i += 2) {
        if (std::string(argv[i]) == "--min-lag-ms") min_lag = std::atof(argv[i + 1]);
        if (std::string(argv[i]) == "--max-lag-ms") max_lag = std::atof(argv[i + 1]);
        if (std::string(argv[i]) == "--rate") rate = std::atoi(argv[i + 1]);
        if (std::string(argv[i]) == "--channels") channels = std::atoi(argv[i + 1]);
    }
    if (std::string(argv[1]) == "--stream") {
        if (rate <= 0 || channels <= 0) {
            std::fprintf(stderr, "bad --rate/--channels\n");
            return 2;
        }
#ifdef _WIN32
        // Without this the CRT mangles 0x0A/0x1A in the PCM stream.
        _setmode(_fileno(stdin), _O_BINARY);
#endif
        return run_stream(rate, channels, min_lag, max_lag);
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
