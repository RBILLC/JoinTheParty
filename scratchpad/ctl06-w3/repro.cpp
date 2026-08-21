// CTL-06/W3 offline reproduction harness.
//
// Drives the REAL synccore::SyncEstimator (core/src/estimator/estimator.cpp,
// unmodified, compiled straight from source) with the exact fix sequence
// from Field Test 11 Scenario 1, S1, 18:00:07.885 -> 18:03:09.368
// (scratchpad/ft11/jtp_ft11.log lines 33-290), to show whether `d_` (drift)
// reaches the +/-0.8 ms/s (=800ppm) clamp under sustained, chronically
// biased-but-ACCEPTED fixes when frequency_skew=0.0 on every single fix
// (confirmed field-logged, never nonzero this run).
//
// Every (offset, capAge, ps, psAge, skew) tuple below is copied verbatim
// from a quoted jtp_ft11.log line (cited in the comment above each entry).
// capture_mono_ns and player_mono_ns are RECONSTRUCTED from the log the same
// way docs/ctl05-investigation.md §2 did: capture_time = printed_time -
// capAge; player_state_time = printed_time - psAge. This is a derivation
// from logged numbers, not a fabrication of new ones.
//
// The one value NOT present anywhere in the log is provider_confidence
// (fixdbg never prints it — confirmed by reading the emitting code,
// android/.../SessionViewModel.kt:2248-2259). Per
// android/.../recognition/ACRCloudProvider.kt:19-24 and :158-160, ACRCloud
// confidence = score/100, documented floor 0.70, default-when-missing 0.80.
// We run the whole replay at BOTH ends of that documented range to show the
// clamp outcome does not hinge on the exact unlogged value.
//
// Build (see docs/agents/build-environment.md for the toolchain — this uses
// a from-scratch compile of just estimator.cpp + this file, NOT the full
// synccore CMake target, since SyncEstimator has zero other project deps):
//
//   "C:/Users/RBILLC/tools/llvm-mingw-20260616-ucrt-x86_64/bin/clang++.exe" \
//     -std=c++17 -O2 -Wall -Wextra \
//     -I "core/src" -I "core/include" \
//     core/src/estimator/estimator.cpp scratchpad/ctl06-w3/repro.cpp \
//     -o build/ctl06-w3/repro.exe
//
// Run (prepend the same bin/ dir to PATH per the DLL rule):
//   PATH="C:/Users/RBILLC/tools/llvm-mingw-20260616-ucrt-x86_64/bin:$PATH" \
//     build/ctl06-w3/repro.exe

#include <cmath>
#include <cstdint>
#include <cstdio>

#include "estimator/estimator.h"

namespace {

// ms since 18:00:00.000 on the FT11 capture day -- pure relative-time
// arithmetic, only deltas matter to the estimator.
constexpr double ms_at(int mm, int ss, int fff) {
    return static_cast<double>(mm) * 60000.0 + static_cast<double>(ss) * 1000.0 +
           static_cast<double>(fff);
}

uint64_t ns_from_ms(double ms) { return static_cast<uint64_t>(ms * 1e6); }

struct Fix {
    const char* log_line;   // jtp_ft11.log citation
    double print_ms;        // fixdbg line's own printed wall-clock time
    int64_t offset_ms;       // match_offset_ms, verbatim
    int zEnd;                 // logged zEnd, for cross-check only (not fed in)
    double capAge_ms;
    int64_t ps_pos_ms;
    double ps_age_ms;
    double skew;              // logged skew, verbatim (always 0.0 this run)
};

struct Correction {
    const char* log_line;
    double print_ms;
    int64_t target_ms;
};

// Verbatim from scratchpad/ft11/jtp_ft11.log, lines 33-290.
const Fix kFixes[] = {
    {"L33  18:00:07.885", ms_at(0, 7, 885), 36400, 355, 632, 33807, 3581, 0.0},
    {"L47  18:00:17.714", ms_at(0, 17, 714), 46400, 418, 462, 41673, 5607, 0.0},
    {"L60  18:00:27.750", ms_at(0, 27, 750), 56380, 438, 497, 41673, 15643, 0.0},
    {"L74  18:00:37.886", ms_at(0, 37, 886), 66280, 538, 633, 41673, 25779, 0.0},
    {"L86  18:00:47.732", ms_at(0, 47, 732), 76380, 438, 479, 41673, 35625, 0.0},
    {"L99  18:00:57.798", ms_at(0, 57, 798), 86420, 398, 546, 41673, 45691, 0.0},
    // --- CORRECTION #1 (L100-101 @ 18:00:57.799, seek->87339) happens here ---
    {"L111 18:01:01.853", ms_at(1, 1, 853), 90300, 396, 561, 87383, 3874, 0.0},
    {"L124 18:01:11.984", ms_at(1, 11, 984), 100300, 395, 692, 87383, 14005, 0.0},
    {"L136 18:01:22.022", ms_at(1, 22, 22), 110280, 416, 729, 87383, 24042, 0.0},
    // --- CORRECTION #2 (L137-138 @ 18:01:22.025, seek->111289) happens here ---
    {"L158 18:01:31.328", ms_at(1, 31, 328), 119260, 396, 803, 111388, 9072, 0.0},
    {"L170 18:01:41.171", ms_at(1, 41, 171), 129240, 416, 646, 111388, 18915, 0.0},
    {"L183 18:01:51.012", ms_at(1, 51, 12), 139260, 396, 488, 111388, 28757, 0.0},
    {"L195 18:02:01.138", ms_at(2, 1, 138), 149280, 376, 614, 111388, 38882, 0.0},
    // --- CORRECTION #3 (L197-198 @ 18:02:01.142, seek->150044) happens here ---
    {"L208 18:02:05.268", ms_at(2, 5, 268), 152980, 470, 624, 150100, 3975, 0.0},
    {"L220 18:02:15.313", ms_at(2, 15, 313), 162960, 492, 666, 150100, 14019, 0.0},
    {"L233 18:02:25.309", ms_at(2, 25, 309), 172980, 472, 662, 150100, 24015, 0.0},
    // --- CORRECTION #4 (L234-235 @ 18:02:25.313, seek->173806) happens here ---
    {"L245 18:02:29.421", ms_at(2, 29, 421), 177120, 50, 609, 173872, 3907, 0.0},
    {"L258 18:02:39.345", ms_at(2, 39, 345), 186760, 410, 533, 173872, 13831, 0.0},
    {"L290 18:03:09.368", ms_at(3, 9, 368), 216780, 386, 560, 173872, 43854, 0.0},
};

const Correction kCorrections[] = {
    {"L100 18:00:57.799", ms_at(0, 57, 799), 87339},
    {"L137 18:01:22.025", ms_at(1, 22, 25), 111289},
    {"L197 18:02:01.142", ms_at(2, 1, 142), 150044},
    {"L234 18:02:25.313", ms_at(2, 25, 313), 173806},
};

// Logged "sync err=...drift=...ppm" from the display tick immediately
// following each fix, for direct comparison against the harness's own
// estimate_at() output. -9999 = no comparable tick before the next fix.
const double kLoggedDriftPpmAfter[] = {
    0,     // after L33: L34 "18:00:07.888 drift=0ppm"
    102,   // after L47: L48 "18:00:18.128 drift=102ppm"
    226,   // after L60: L62 "18:00:28.373 drift=226ppm"
    555,   // after L74: L75 "18:00:38.642 drift=555ppm"
    451,   // after L86: L87 "18:00:48.139 drift=451ppm"
    206,   // after L99 (+correction#1): L105 "18:00:58.410 drift=206ppm"
    220,   // after L111: L112 "18:01:02.540 drift=220ppm"
    220,   // after L124: L125 "18:01:12.808 drift=220ppm"
    268,   // after L136 (+correction#2): L139 "18:01:22.091 drift=268ppm"
    282,   // after L158: L159 "18:01:32.363 drift=282ppm"
    300,   // after L170: L171 "18:01:41.584 drift=300ppm"
    268,   // after L183: L184 "18:01:51.909 drift=268ppm"
    183,   // after L195 (+correction#3): L196 "18:02:01.141 drift=183ppm"
    198,   // after L208: L209 "18:02:06.292 drift=198ppm"
    239,   // after L220: L221 "18:02:15.587 drift=239ppm"
    201,   // after L233 (+correction#4): L239 "18:02:25.864 drift=201ppm"
    195,   // after L245: L246 "18:02:30.039 drift=195ppm"
    755,   // after L258: L259 "18:02:40.308 drift=755ppm"
    800,   // after L290: L291 "18:03:10.115 drift=800ppm"  <-- THE CLAMP HIT
};

void run(float provider_confidence) {
    synccore::SyncEstimator est;  // default EstimatorConfig (unmodified)
    est.set_output_latency_ms(173.0);  // FT11 calibration profile (report §Rig)
    // nudge_ms left at 0 (TRIM confirmed "+0 ms" live, per FT11 report §Rig)

    std::printf("=== replay at provider_confidence=%.2f ===\n", provider_confidence);
    std::printf("%-20s %10s %8s %9s %9s | %8s %8s | %8s\n", "fix", "offset", "zEnd(log)",
                "d_ms/s", "drift_ppm", "e_ms", "logged_ppm", "match?");

    size_t corr_idx = 0;
    const size_t n_corr = sizeof(kCorrections) / sizeof(kCorrections[0]);

    for (size_t i = 0; i < sizeof(kFixes) / sizeof(kFixes[0]); ++i) {
        const Fix& f = kFixes[i];

        // Fire any corrections whose print time falls before this fix's
        // player-state timestamp (they always land strictly between two
        // consecutive fixes in the log).
        while (corr_idx < n_corr && kCorrections[corr_idx].print_ms < f.print_ms) {
            const Correction& c = kCorrections[corr_idx];
            const uint64_t issued_ns = ns_from_ms(c.print_ms);
            // command_latency_ms not logged anywhere (seekTo fires within
            // ~1ms of the CORRECTION line in every case observed) -> 0.0,
            // flagged as the one modeling approximation in findings.md.
            est.on_local_seek(c.target_ms, issued_ns, 0.0);
            std::printf("  [seek @ %s -> %lldms]\n", c.log_line,
                        static_cast<long long>(c.target_ms));
            ++corr_idx;
        }

        const double player_ms = f.print_ms - f.ps_age_ms;
        const double capture_ms = f.print_ms - f.capAge_ms;
        est.on_player_state(f.ps_pos_ms, false, ns_from_ms(player_ms));
        const bool accepted =
            est.on_fix(f.offset_ms, ns_from_ms(capture_ms), f.skew, provider_confidence);

        const auto e = est.estimate_at(ns_from_ms(capture_ms));
        const double logged = kLoggedDriftPpmAfter[i];
        const bool close = std::abs(e.drift_ppm - logged) <= 30.0;  // ~1 fix of tolerance
        std::printf("%-20s %10lld %8d %9.4f %9.1f | %8.1f %8.0f | %s%s\n", f.log_line,
                    static_cast<long long>(f.offset_ms), f.zEnd,
                    e.drift_ppm / 1000.0, e.drift_ppm, e.error_ms, logged,
                    close ? "match" : "DIFFERS", accepted ? "" : "  (fix NOT accepted!)");
    }

    // --- Part (b): starvation probe. --------------------------------------
    // No further on_fix/on_player_state calls at all from here on -- purely
    // repeated estimate_at() polls at increasing horizons, exactly what the
    // worker thread's ~1Hz display tick does. If d_ has no leak/decay term,
    // drift_ppm must sit EXACTLY at whatever it was after the last fix
    // (800.0 here) at every horizon, while confidence (a function of
    // *local* variables computed fresh inside estimate_at, per
    // estimator.cpp:172-175) decays with age via conf_age_tau_s=45s.
    const Fix& last = kFixes[sizeof(kFixes) / sizeof(kFixes[0]) - 1];
    const double last_capture_ms = last.print_ms - last.capAge_ms;
    std::printf("-- starvation probe from last fix (no further fixes at all) --\n");
    std::printf("%8s %10s %10s\n", "+age_s", "drift_ppm", "confidence");
    const double kProbeAges[] = {0.0, 10.0, 30.0, 60.0, 120.0, 210.0, 400.0};
    for (double age_s : kProbeAges) {
        const uint64_t probe_ns = ns_from_ms(last_capture_ms + age_s * 1000.0);
        const auto e = est.estimate_at(probe_ns);
        std::printf("%8.0f %10.1f %10.3f\n", age_s, e.drift_ppm, e.confidence);
    }
    std::printf("(compare: FT11 S4 post-pause tail, jtp_ft11.log:778->944, drift\n"
                "held at exactly -800ppm from 18:18:18.263 through 18:21:41.701+\n"
                "(3m23s+, zero intervening fixdbg lines) while conf fell 0.79->0.00\n"
                "over the same window -- same code path, opposite clamp sign.)\n\n");
}

}  // namespace

int main() {
    run(0.80f);  // ACRCloudProvider.kt:159 default-when-missing
    run(0.70f);  // ACRCloudProvider.kt:23-24 documented floor
    return 0;
}
