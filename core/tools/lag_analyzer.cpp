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
//   lag_analyzer recording.wav [--min-lag-ms 40] [--max-lag-ms 2500] [--tempo]
//   lag_analyzer --stream [--rate 48000] [--channels 1] [--tempo]
//   lag_analyzer --selftest
//
// DSP-01b (tech-req §2.10/§2.8): --tempo runs an OnsetStrengthRing over the
// same audio stream and appends a beat_period_ms column LAST (after
// comb_ratio), the CTL-03a additive-column precedent — omitting --tempo
// leaves output byte-identical to pre-DSP-01b behavior.
//
// DSP-02a (tech-req §2.11): --beta <v> threads a parameterized whitening
// exponent through analyze_window (offline A/B tooling only -- see
// dsp/lag_window.h; the on-device default stays 0.5 and is untouched by
// this flag). The CSV gains a trailing `beta` column carrying the value,
// appended ONLY when --beta is passed, so runs that don't exercise the flag
// keep byte-identical output. When combined with --tempo, column order is
// ...,comb_ratio,beta,beat_period_ms -- beta slots in BEFORE beat_period_ms
// so §2.10's "beat_period_ms is appended last" stays literally true in
// every flag combination (docs/dsp02a-review.md has the rationale).
//
// Desktop-only tool; built beside the test suite.

#include <algorithm>
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
#include "dsp/oss_ring.h"

namespace {

using synccore::BeatEstimate;
using synccore::OnsetStrengthRing;
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

int run(const Wav& wav, double min_lag_ms, double max_lag_ms, bool tempo,
       bool beta_passed, double beta) {
    const int rate = wav.sample_rate;
    const size_t win = static_cast<size_t>(8.0 * rate);   // 8 s windows
    const size_t hop = static_cast<size_t>(2.0 * rate);   // 2 s hop
    // DSP-02a: column order when both flags are passed is
    // ...,comb_ratio,beta,beat_period_ms -- beta before beat_period_ms so
    // "beat_period_ms appended last" (§2.10) stays literally true.
    if (tempo && beta_passed)
        std::printf("window_start_s,lag_ms,peak_ratio,confident,comb_ratio,beta,beat_period_ms\n");
    else if (tempo)
        std::printf("window_start_s,lag_ms,peak_ratio,confident,comb_ratio,beat_period_ms\n");
    else if (beta_passed)
        std::printf("window_start_s,lag_ms,peak_ratio,confident,comb_ratio,beta\n");
    else
        std::printf("window_start_s,lag_ms,peak_ratio,confident,comb_ratio\n");

    // DSP-01b: constructed unconditionally (cheap, a few KB once) but fed/
    // polled only when --tempo is passed, so a plain run's OUTPUT is
    // byte-identical to pre-DSP-01b behavior.
    OnsetStrengthRing oss(rate);
    size_t oss_pushed = 0;
    const auto ns_at = [rate](size_t sample_pos) -> uint64_t {
        return static_cast<uint64_t>(static_cast<double>(sample_pos) /
                                     rate * 1e9);
    };

    for (size_t start = 0; start + win <= wav.mono.size(); start += hop) {
        const auto r = analyze_window(wav.mono.data() + start, win, rate,
                                      min_lag_ms, max_lag_ms, beta);
        double beat_period_ms = 0.0;
        if (tempo) {
            // Push every sample that feeds the windowing, each exactly
            // once, in stream order: advance the ring's cursor up through
            // the end of THIS window (win > hop, so consecutive windows
            // overlap; only the newly-covered tail is pushed each time).
            const size_t target = std::min(start + win, wav.mono.size());
            if (target > oss_pushed) {
                oss.push(wav.mono.data() + oss_pushed, target - oss_pushed,
                         ns_at(target));
                oss_pushed = target;
            }
            const BeatEstimate est = oss.estimate_beat_period(ns_at(target));
            beat_period_ms = est.period_ms;  // 0.0 when no estimate yet
        }
        if (tempo && beta_passed) {
            std::printf("%.1f,%.1f,%.2f,%d,%.2f,%.2f,%.1f\n",
                        static_cast<double>(start) / rate, r.lag_ms,
                        r.peak_ratio, r.found ? 1 : 0, r.comb_ratio, beta,
                        beat_period_ms);
        } else if (tempo) {
            std::printf("%.1f,%.1f,%.2f,%d,%.2f,%.1f\n",
                        static_cast<double>(start) / rate, r.lag_ms,
                        r.peak_ratio, r.found ? 1 : 0, r.comb_ratio,
                        beat_period_ms);
        } else if (beta_passed) {
            std::printf("%.1f,%.1f,%.2f,%d,%.2f,%.2f\n",
                        static_cast<double>(start) / rate, r.lag_ms,
                        r.peak_ratio, r.found ? 1 : 0, r.comb_ratio, beta);
        } else {
            std::printf("%.1f,%.1f,%.2f,%d,%.2f\n",
                        static_cast<double>(start) / rate, r.lag_ms,
                        r.peak_ratio, r.found ? 1 : 0, r.comb_ratio);
        }
    }
    return 0;
}

// Live mode: raw s16le PCM on stdin (from e.g. ffmpeg -f s16le -), one lag
// line every `hop` seconds. Same analysis as the file path, but the room can
// be watched WHILE the app is running instead of after the fact — which is
// what makes it usable as a test instrument rather than a post-mortem.
int run_stream(int rate, int channels, double min_lag_ms, double max_lag_ms,
              bool tempo, bool beta_passed, double beta) {
    const size_t win = static_cast<size_t>(8.0 * rate);
    const size_t hop = static_cast<size_t>(2.0 * rate);
    std::vector<float> buf;
    buf.reserve(win + hop);
    std::vector<int16_t> chunk(static_cast<size_t>(4096 * channels));
    size_t since_last = 0;
    double t = 0.0;

    // DSP-01b: pushed chunk-by-chunk, in real stream order, independent of
    // `buf`'s sliding window (which erases old samples). Constructed
    // unconditionally but fed/polled only when --tempo is passed — a plain
    // run's output stays byte-identical.
    OnsetStrengthRing oss(rate);
    std::vector<float> mono_chunk;
    uint64_t mono_ns = 0;

    std::fprintf(stderr, "stream: %d Hz, %d ch, 8s window / 2s hop\n", rate,
                 channels);
    // DSP-02a: same combined column order as file mode -- beta before
    // beat_period_ms (see run()'s header comment).
    if (tempo && beta_passed)
        std::printf("t_s,lag_ms,peak_ratio,confident,rms_db,comb_ratio,beta,beat_period_ms\n");
    else if (tempo)
        std::printf("t_s,lag_ms,peak_ratio,confident,rms_db,comb_ratio,beat_period_ms\n");
    else if (beta_passed)
        std::printf("t_s,lag_ms,peak_ratio,confident,rms_db,comb_ratio,beta\n");
    else
        std::printf("t_s,lag_ms,peak_ratio,confident,rms_db,comb_ratio\n");
    std::fflush(stdout);

    for (;;) {
        const size_t got =
            std::fread(chunk.data(), sizeof(int16_t), chunk.size(), stdin);
        if (got == 0) break;
        const size_t frames = got / static_cast<size_t>(channels);
        if (tempo) mono_chunk.clear();
        for (size_t i = 0; i < frames; ++i) {
            int32_t acc = 0;
            for (int c = 0; c < channels; ++c)
                acc += chunk[i * static_cast<size_t>(channels) +
                             static_cast<size_t>(c)];
            const float mono = static_cast<float>(acc) /
                               (32768.0f * static_cast<float>(channels));
            buf.push_back(mono);
            if (tempo) mono_chunk.push_back(mono);
        }
        t += static_cast<double>(frames) / rate;
        if (tempo) {
            mono_ns = static_cast<uint64_t>(t * 1e9);
            if (!mono_chunk.empty())
                oss.push(mono_chunk.data(), mono_chunk.size(), mono_ns);
        }
        since_last += frames;
        if (buf.size() > win) buf.erase(buf.begin(), buf.begin() + static_cast<long>(buf.size() - win));
        if (buf.size() < win || since_last < hop) continue;
        since_last = 0;

        double sum_sq = 0.0;
        for (size_t i = 0; i < win; ++i) sum_sq += buf[i] * buf[i];
        const double rms = std::sqrt(sum_sq / static_cast<double>(win));
        const double rms_db = 20.0 * std::log10(rms + 1e-9);

        const auto r = analyze_window(buf.data(), win, rate, min_lag_ms,
                                      max_lag_ms, beta);
        if (tempo && beta_passed) {
            const BeatEstimate est = oss.estimate_beat_period(mono_ns);
            std::printf("%.0f,%.0f,%.2f,%d,%.1f,%.2f,%.2f,%.1f\n", t,
                        r.lag_ms, r.peak_ratio, r.found ? 1 : 0, rms_db,
                        r.comb_ratio, beta, est.period_ms);
        } else if (tempo) {
            const BeatEstimate est = oss.estimate_beat_period(mono_ns);
            std::printf("%.0f,%.0f,%.2f,%d,%.1f,%.2f,%.1f\n", t, r.lag_ms,
                        r.peak_ratio, r.found ? 1 : 0, rms_db, r.comb_ratio,
                        est.period_ms);
        } else if (beta_passed) {
            std::printf("%.0f,%.0f,%.2f,%d,%.1f,%.2f,%.2f\n", t, r.lag_ms,
                        r.peak_ratio, r.found ? 1 : 0, rms_db, r.comb_ratio,
                        beta);
        } else {
            std::printf("%.0f,%.0f,%.2f,%d,%.1f,%.2f\n", t, r.lag_ms,
                        r.peak_ratio, r.found ? 1 : 0, rms_db, r.comb_ratio);
        }
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
    std::printf(
        "selftest: lag=%.1fms (expect 800±5) ratio=%.2f found=%d "
        "comb_ratio=%.2f\n",
        r.lag_ms, r.peak_ratio, r.found ? 1 : 0, r.comb_ratio);
    // Pass/fail is unchanged by CTL-03a: comb_ratio is printed for
    // visibility only and never enters the ok computation below.
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
                     "--selftest [--min-lag-ms N] [--max-lag-ms N] "
                     "[--rate N] [--channels N] [--tempo] [--beta V]\n");
        return 2;
    }
    double min_lag = 40, max_lag = 2500;
    int rate = 44100, channels = 1;
    // DSP-01b: --tempo is a standalone flag (no value), pulled out of the
    // argv tail before the existing flag/value pair scan below so that scan
    // is untouched — and therefore byte-identical when --tempo is absent.
    bool tempo = false;
    std::vector<std::string> rest;
    for (int i = 2; i < argc; ++i) {
        if (std::string(argv[i]) == "--tempo") {
            tempo = true;
            continue;
        }
        rest.push_back(argv[i]);
    }
    // DSP-02a: --beta <v> is a value-carrying flag, parsed in the existing
    // pair scan (the vector DSP-01b's --tempo extraction leaves behind) so
    // it coexists cleanly with --min-lag-ms/--max-lag-ms/--rate/--channels.
    // `beta_passed` gates CSV column emission on the flag being PASSED, not
    // on the value differing from 0.5 -- --beta 0.5 is a valid A/B sweep
    // point and must still show the column.
    double beta = 0.5;
    bool beta_passed = false;
    for (size_t i = 0; i + 1 < rest.size(); i += 2) {
        if (rest[i] == "--min-lag-ms") min_lag = std::atof(rest[i + 1].c_str());
        if (rest[i] == "--max-lag-ms") max_lag = std::atof(rest[i + 1].c_str());
        if (rest[i] == "--rate") rate = std::atoi(rest[i + 1].c_str());
        if (rest[i] == "--channels") channels = std::atoi(rest[i + 1].c_str());
        if (rest[i] == "--beta") {
            beta = std::atof(rest[i + 1].c_str());
            beta_passed = true;
        }
    }
    // DSP-02a (tech-req §2.11): the design space is (0, 1] -- beta = 0 is
    // plain autocorrelation (no whitening) and beta > 1 is more aggressive
    // than full PHAT; both are outside §2.11's scope and a typo'd sweep
    // value would silently produce garbage A/B data for DSP-02b, so this is
    // a hard usage error rather than a clamp.
    if (beta_passed && !(beta > 0.0 && beta <= 1.0)) {
        std::fprintf(stderr,
                     "bad --beta %.4g: must be in (0, 1] (§2.11 sweep range "
                     "is 0.5-0.8)\n",
                     beta);
        return 2;
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
        return run_stream(rate, channels, min_lag, max_lag, tempo,
                          beta_passed, beta);
    }
    Wav wav;
    if (!read_wav_pcm16(argv[1], &wav)) {
        std::fprintf(stderr, "failed to read %s (PCM16 WAV required)\n", argv[1]);
        return 2;
    }
    std::fprintf(stderr, "loaded %.1fs @ %dHz\n",
                 static_cast<double>(wav.mono.size()) / wav.sample_rate,
                 wav.sample_rate);
    return run(wav, min_lag, max_lag, tempo, beta_passed, beta);
}
