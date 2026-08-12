// test_fixture_suite.cpp — CORE-07: fixture regression suite.
//
// Data-driven closed-loop replay: every *.fixture file under
// tests/fixtures/ is discovered, parsed, and run through the real
// SyncEstimator + CorrectionPolicy exactly as the hand-written CORE-02/
// CORE-03 simulations do (see test_estimator.cpp's World and
// test_policy.cpp's closed-loop sims), except the world parameters, fix
// schedule, and pass/fail thresholds all come from the file instead of
// C++ source. Adding a new regression case — including one distilled from
// a future real field capture — never touches this file: drop a new
// *.fixture under tests/fixtures/ and ctest picks it up on the next run.
//
// Two fixture modes (see docs/core07-review.md for the full grammar):
//   mode: world  — synthetic closed loop. The file gives a true-error/
//                  drift model (with an optional one-time step, for
//                  "the room re-seeks" scenarios) and a fix cadence
//                  (policy-driven "auto", or explicit forced-time rows
//                  that can fall back to "auto" afterwards). The runner
//                  computes each fix's reported offset from the model, so
//                  behavior stays internally consistent even when the
//                  policy issues seeks that shift local_pos underneath it.
//   mode: trace  — literal replay. FIX rows carry an absolute offset_ms
//                  taken as-is (this is the shape a converter from real
//                  captured recognition fixes — timestamp/offset/
//                  confidence tuples — would produce); PLAYER rows (or an
//                  auto-synthesized 1:1 timeline, if none are given) supply
//                  player-state pushes. No world model, so "final error"
//                  is graded against an independently supplied
//                  expect.true_error_ms (e.g. an acoustic mic measurement)
//                  rather than a value the runner computed itself.
//
// House convention: print only failures, plus one final summary line.

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <sstream>
#include <unordered_map>
#include <string>
#include <vector>

// Directory listing without <filesystem>: the llvm-mingw toolchain this
// suite is built with locally ships a libc++ that has no <filesystem>
// umbrella header (and no libc++fs to link) — and, being a trimmed-down
// archive, no <windows.h> either — so a portable OS-level listing is used
// instead. <io.h>'s _findfirst/_findnext/_findclose family is the MSVC CRT
// API mingw also implements for compatibility, so it works unmodified on
// both the MSVC and llvm-mingw/MSVCRT-family Windows builds in CI.
#ifdef _WIN32
#include <io.h>
#else
#include <dirent.h>
#endif

#include "estimator/estimator.h"
#include "policy/policy.h"

using synccore::Action;
using synccore::ActionKind;
using synccore::CorrectionPolicy;
using synccore::Estimate;
using synccore::EstimatorConfig;
using synccore::PolicyConfig;
using synccore::SyncEstimator;

namespace {

int g_failures = 0;
int g_fixtures_run = 0;

void fail(const std::string& fixture, const std::string& msg) {
    std::printf("FAIL %s: %s\n", fixture.c_str(), msg.c_str());
    ++g_failures;
}

constexpr uint64_t kSec = 1'000'000'000ull;

std::string trim(const std::string& s) {
    size_t b = s.find_first_not_of(" \t\r\n");
    if (b == std::string::npos) return "";
    size_t e = s.find_last_not_of(" \t\r\n");
    return s.substr(b, e - b + 1);
}

// Basename minus extension, e.g. "a/b/foo.fixture" -> "foo".
std::string stem_of(const std::string& path) {
    const size_t slash = path.find_last_of("/\\");
    const std::string base = (slash == std::string::npos) ? path : path.substr(slash + 1);
    const size_t dot = base.find_last_of('.');
    return (dot == std::string::npos) ? base : base.substr(0, dot);
}

// Lists "<dir>/*.fixture" (non-recursive), sorted for deterministic order.
std::vector<std::string> list_fixture_files(const std::string& dir) {
    std::vector<std::string> out;
#ifdef _WIN32
    const std::string pattern = dir + "\\*.fixture";
    struct _finddata_t fd;
    const intptr_t h = _findfirst(pattern.c_str(), &fd);
    if (h != -1) {
        do {
            if (!(fd.attrib & _A_SUBDIR)) out.push_back(dir + "\\" + fd.name);
        } while (_findnext(h, &fd) == 0);
        _findclose(h);
    }
#else
    DIR* d = opendir(dir.c_str());
    if (d) {
        struct dirent* ent;
        const std::string suffix = ".fixture";
        while ((ent = readdir(d)) != nullptr) {
            const std::string name = ent->d_name;
            if (name.size() > suffix.size() &&
                name.compare(name.size() - suffix.size(), suffix.size(), suffix) == 0)
                out.push_back(dir + "/" + name);
        }
        closedir(d);
    }
#endif
    std::sort(out.begin(), out.end());
    return out;
}

// ---- Fixture file model ---------------------------------------------------

struct FixRow {
    double t_s = 0.0;
    // world/explicit: [confidence, noise_ms]
    // trace:          [offset_ms, confidence, skew]
    std::vector<double> cols;
};

struct PlayerRow {
    double t_s = 0.0;
    double position_ms = 0.0;
    bool paused = false;
};

struct Fixture {
    std::string path;
    std::string name;  // filename stem, used in FAIL output
    std::unordered_map<std::string, std::string> kv;
    std::vector<FixRow> fix_rows;
    std::vector<PlayerRow> player_rows;

    bool has(const std::string& key) const { return kv.count(key) != 0; }

    std::string gets(const std::string& key, const std::string& def = "") const {
        auto it = kv.find(key);
        return it == kv.end() ? def : it->second;
    }

    double getd(const std::string& key, double def = 0.0) const {
        auto it = kv.find(key);
        if (it == kv.end()) return def;
        try {
            return std::stod(it->second);
        } catch (...) {
            return def;
        }
    }

    int geti(const std::string& key, int def = 0) const {
        return static_cast<int>(getd(key, static_cast<double>(def)));
    }

    bool getb(const std::string& key, bool def) const {
        auto it = kv.find(key);
        if (it == kv.end()) return def;
        const std::string v = trim(it->second);
        return v == "true" || v == "1" || v == "yes";
    }
};

// Parses one *.fixture file. Returns false (with `error` set) on malformed
// input — a bad fixture file is itself a suite failure, not a silent skip,
// so CI catches typos in newly-added files.
bool parse_fixture(const std::string& path, Fixture& out, std::string& error) {
    // Plain C stdio rather than std::ifstream: the llvm-mingw toolchain this
    // suite is built with locally ships a libc++ with no <fstream> (a
    // reduced, no-file-streams build) — read the whole file with fopen/
    // fread instead and split lines from the in-memory buffer via
    // std::istringstream + getline, which works on any istream.
    std::FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) {
        error = "could not open file";
        return false;
    }
    std::string content;
    char buf[4096];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof(buf), f)) > 0) content.append(buf, n);
    std::fclose(f);

    out.path = path;
    out.name = stem_of(path);

    std::istringstream in(content);
    std::string line;
    int lineno = 0;
    while (std::getline(in, line)) {
        ++lineno;
        const std::string t = trim(line);
        if (t.empty() || t[0] == '#') continue;

        std::istringstream iss(t);
        std::string tag;
        iss >> tag;
        if (tag == "FIX") {
            FixRow row;
            if (!(iss >> row.t_s)) {
                error = "line " + std::to_string(lineno) + ": FIX missing t_s";
                return false;
            }
            double v;
            while (iss >> v) row.cols.push_back(v);
            out.fix_rows.push_back(row);
        } else if (tag == "PLAYER") {
            PlayerRow row;
            double paused = 0.0;
            if (!(iss >> row.t_s >> row.position_ms >> paused)) {
                error = "line " + std::to_string(lineno) +
                        ": PLAYER needs t_s position_ms paused";
                return false;
            }
            row.paused = paused != 0.0;
            out.player_rows.push_back(row);
        } else {
            // "key: value" directive.
            size_t colon = t.find(':');
            if (colon == std::string::npos) {
                error = "line " + std::to_string(lineno) + ": not a directive, FIX, "
                        "or PLAYER line: " + t;
                return false;
            }
            const std::string key = trim(t.substr(0, colon));
            const std::string val = trim(t.substr(colon + 1));
            out.kv[key] = val;
        }
    }

    std::sort(out.fix_rows.begin(), out.fix_rows.end(),
              [](const FixRow& a, const FixRow& b) { return a.t_s < b.t_s; });
    std::sort(out.player_rows.begin(), out.player_rows.end(),
              [](const PlayerRow& a, const PlayerRow& b) { return a.t_s < b.t_s; });

    if (out.gets("name").empty()) {
        error = "missing required 'name' directive";
        return false;
    }
    if (out.gets("desc").empty()) {
        error = "missing required 'desc' directive";
        return false;
    }
    if (out.gets("mode") != "world" && out.gets("mode") != "trace") {
        error = "'mode' must be 'world' or 'trace', got '" + out.gets("mode") + "'";
        return false;
    }
    if (!out.has("horizon_s")) {
        error = "missing required 'horizon_s' directive";
        return false;
    }
    return true;
}

// ---- Config overrides (allowlisted knobs; extend as new scenarios need
// more of PolicyConfig/EstimatorConfig exposed) --------------------------

EstimatorConfig build_ecfg(const Fixture& fx) {
    EstimatorConfig c;
    if (fx.has("estimator.deadband_ms")) c.deadband_ms = fx.getd("estimator.deadband_ms");
    if (fx.has("estimator.outlier_gate_ms")) c.outlier_gate_ms = fx.getd("estimator.outlier_gate_ms");
    if (fx.has("estimator.convergence_fixes")) c.convergence_fixes = fx.geti("estimator.convergence_fixes");
    if (fx.has("estimator.drift_clamp_ms_per_s")) c.drift_clamp_ms_per_s = fx.getd("estimator.drift_clamp_ms_per_s");
    return c;
}

PolicyConfig build_pcfg(const Fixture& fx) {
    PolicyConfig c;
    if (fx.has("policy.deadband_ms")) c.deadband_ms = fx.getd("policy.deadband_ms");
    if (fx.has("policy.confirm_floor_ms")) c.confirm_floor_ms = fx.getd("policy.confirm_floor_ms");
    if (fx.has("policy.confirm_min_fixes")) c.confirm_min_fixes = fx.geti("policy.confirm_min_fixes");
    if (fx.has("policy.confirm_window_s"))
        c.confirm_window_ns = static_cast<uint64_t>(fx.getd("policy.confirm_window_s") * 1e9);
    if (fx.has("policy.confirm_agree_ms")) c.confirm_agree_ms = fx.getd("policy.confirm_agree_ms");
    if (fx.has("policy.large_correction_threshold_ms"))
        c.large_correction_threshold_ms = fx.getd("policy.large_correction_threshold_ms");
    if (fx.has("policy.command_latency_ms")) c.command_latency_ms = fx.getd("policy.command_latency_ms");
    if (fx.has("policy.lost_threshold_ms")) c.lost_threshold_ms = fx.getd("policy.lost_threshold_ms");
    return c;
}

// ---- Replay outcome, checked against the fixture's expect.* directives ---

struct Outcome {
    bool valid = false;
    Estimate final_est;
    double final_true_error_ms = 0.0;  // world mode only; trace uses expect.true_error_ms
    int fixes = 0;
    int seeks_issued = 0;
    int large_seeks = 0;  // seeks issued while |filtered error| >= 1000 ms
    bool track_lost_seen = false;
};

void evaluate_expectations(const Fixture& fx, const Outcome& out) {
    if (!out.valid) {
        fail(fx.name, "replay produced no valid estimate");
        return;
    }
    if (fx.has("expect.track_lost")) {
        const bool want = fx.getb("expect.track_lost", false);
        if (out.track_lost_seen != want) {
            fail(fx.name, "track_lost=" + std::string(out.track_lost_seen ? "true" : "false") +
                              ", want " + (want ? "true" : "false"));
        }
    }
    if (fx.has("expect.converged")) {
        const bool want = fx.getb("expect.converged", true);
        if (out.final_est.converged != want) {
            fail(fx.name, "converged=" + std::string(out.final_est.converged ? "true" : "false") +
                              ", want " + (want ? "true" : "false"));
        }
    }
    if (fx.has("expect.final_abs_error_ms_max")) {
        const double truth = fx.has("expect.true_error_ms")
                                  ? fx.getd("expect.true_error_ms")
                                  : out.final_true_error_ms;
        const double err = std::abs(out.final_est.error_ms - truth);
        const double bound = fx.getd("expect.final_abs_error_ms_max");
        if (err > bound) {
            fail(fx.name, "final |estimate - truth| = " + std::to_string(err) +
                              " ms, want <= " + std::to_string(bound));
        }
    }
    if (fx.has("expect.max_seeks") && out.seeks_issued > fx.geti("expect.max_seeks")) {
        fail(fx.name, "seeks_issued=" + std::to_string(out.seeks_issued) +
                          ", want <= " + fx.gets("expect.max_seeks"));
    }
    if (fx.has("expect.min_seeks") && out.seeks_issued < fx.geti("expect.min_seeks")) {
        fail(fx.name, "seeks_issued=" + std::to_string(out.seeks_issued) +
                          ", want >= " + fx.gets("expect.min_seeks"));
    }
    if (fx.has("expect.max_large_seeks") && out.large_seeks > fx.geti("expect.max_large_seeks")) {
        fail(fx.name, "large_seeks=" + std::to_string(out.large_seeks) +
                          ", want <= " + fx.gets("expect.max_large_seeks"));
    }
    if (fx.has("expect.min_large_seeks") && out.large_seeks < fx.geti("expect.min_large_seeks")) {
        fail(fx.name, "large_seeks=" + std::to_string(out.large_seeks) +
                          ", want >= " + fx.gets("expect.min_large_seeks"));
    }
    if (fx.has("expect.min_fixes") && out.fixes < fx.geti("expect.min_fixes")) {
        fail(fx.name, "fixes=" + std::to_string(out.fixes) +
                          ", want >= " + fx.gets("expect.min_fixes"));
    }
    std::printf("  %s: %d fixes, %d seeks (%d large), final est=%.1f ms "
                "conv=%d lost=%d\n",
                fx.name.c_str(), out.fixes, out.seeks_issued, out.large_seeks,
                out.final_est.error_ms, out.final_est.converged, out.track_lost_seen);
}

// ---- mode: world -----------------------------------------------------------

Outcome run_world(const Fixture& fx) {
    Outcome out;
    EstimatorConfig ecfg = build_ecfg(fx);
    PolicyConfig pcfg = build_pcfg(fx);
    SyncEstimator est(ecfg);
    CorrectionPolicy pol(pcfg);

    const double drift = fx.getd("world.drift_ms_per_s", 0.0);
    double true_error = fx.getd("world.true_error0_ms", 0.0);
    const bool has_step = fx.has("world.step_at_s");
    const double step_at_s = fx.getd("world.step_at_s", -1.0);
    const double step_to_ms = fx.getd("world.step_to_ms", 0.0);
    bool step_applied = false;
    double local_pos = fx.getd("world.local_start_ms", 60000.0);

    const std::string schedule = fx.gets("fixes.schedule", "auto");
    const bool continue_auto = fx.getb("fixes.continue_auto", schedule == "auto");
    const bool apply_seeks = fx.getb("fixes.apply_seeks", true);
    const double fixed_noise = fx.getd("fixes.noise_ms", 0.0);
    const float fixed_conf = static_cast<float>(fx.getd("fixes.confidence", 0.9));
    const double fix_skew = fx.getd("fixes.skew", 0.0);

    bool seek_scheduled = false;
    double seek_target = 0.0;
    uint64_t seek_apply_at = 0;
    const uint64_t apply_delay =
        static_cast<uint64_t>(pol.command_latency_ms() / 1000.0 * kSec);

    const uint64_t step_ns = kSec / 10;
    const uint64_t horizon_ns = static_cast<uint64_t>(fx.getd("horizon_s") * 1e9);

    size_t explicit_idx = 0;

    for (uint64_t t = step_ns; t <= horizon_ns; t += step_ns) {
        const double dt_s = static_cast<double>(step_ns) / kSec;
        const double t_s = static_cast<double>(t) / kSec;
        true_error += drift * dt_s;
        local_pos += dt_s * 1000.0;
        if (has_step && !step_applied && t_s >= step_at_s) {
            true_error = step_to_ms;
            step_applied = true;
        }

        if (seek_scheduled && t >= seek_apply_at) {
            seek_scheduled = false;
            const double external = local_pos - true_error;
            local_pos = seek_target;
            true_error = local_pos - external;
            est.on_player_state(static_cast<int64_t>(local_pos), false, t);
        }
        if (t % kSec == 0) est.on_player_state(static_cast<int64_t>(local_pos), false, t);

        bool do_fix = false;
        float conf_this = fixed_conf;
        double noise_this = (out.fixes % 2 == 0) ? fixed_noise : -fixed_noise;

        if (schedule == "explicit" && explicit_idx < fx.fix_rows.size()) {
            const FixRow& row = fx.fix_rows[explicit_idx];
            if (t_s + 1e-6 >= row.t_s) {
                do_fix = true;
                if (row.cols.size() >= 1) conf_this = static_cast<float>(row.cols[0]);
                if (row.cols.size() >= 2) noise_this = row.cols[1];
                ++explicit_idx;
            }
        } else if (schedule == "auto" ||
                   (schedule == "explicit" && continue_auto && explicit_idx >= fx.fix_rows.size())) {
            const bool bootstrap = (out.fixes == 0 && t >= kSec);
            if (bootstrap || pol.fix_request_due(t)) do_fix = true;
        }

        if (do_fix) {
            const double external = local_pos - true_error + noise_this;
            if (est.on_fix(static_cast<int64_t>(std::llround(external)), t, fix_skew,
                           conf_this)) {
                ++out.fixes;
                pol.on_fix_accepted(t);
                const Estimate e = est.estimate_at(t);
                out.final_est = e;
                out.valid = true;
                const Action a = pol.on_estimate(e, est.projected_local_ms(t), t);
                if (a.kind == ActionKind::kSeek) {
                    ++out.seeks_issued;
                    if (std::llround(std::abs(e.error_ms)) >= 1000) ++out.large_seeks;
                    if (apply_seeks) {
                        est.on_local_seek(a.seek_to_ms, t, pol.command_latency_ms());
                        pol.on_seek_issued(t);
                        seek_scheduled = true;
                        seek_target = static_cast<double>(a.seek_to_ms);
                        seek_apply_at = t + apply_delay;
                    }
                } else if (a.kind == ActionKind::kTrackLost) {
                    out.track_lost_seen = true;
                }
            }
        }
    }

    out.final_true_error_ms = true_error;
    return out;
}

// ---- mode: trace ------------------------------------------------------------

Outcome run_trace(const Fixture& fx) {
    Outcome out;
    EstimatorConfig ecfg = build_ecfg(fx);
    PolicyConfig pcfg = build_pcfg(fx);
    SyncEstimator est(ecfg);
    CorrectionPolicy pol(pcfg);

    const double local_start = fx.getd("world.local_start_ms", 60000.0);
    // Unlike world mode, trace-mode seeks (when enabled) land immediately —
    // there is no local_pos model downstream whose continuity a delayed
    // landing needs to preserve, since every FIX row's offset is already a
    // literal, independent value rather than derived from local_pos.
    const bool apply_seeks = fx.getb("fixes.apply_seeks", false);

    // t=0 baseline so the very first FIX row always has player state to
    // read (SyncEstimator::on_fix requires has_player_).
    est.on_player_state(static_cast<int64_t>(local_start), false, 0);

    // Merge FIX and (optional) PLAYER rows into one chronological event
    // stream. If no PLAYER rows are given, synthesize one per whole second
    // up to each FIX row's time, 1:1 off local_start — the same assumption
    // test_estimator.cpp's World makes for its player timeline.
    struct Ev {
        double t_s;
        bool is_fix;
        const FixRow* fix = nullptr;
        const PlayerRow* player = nullptr;
    };
    std::vector<Ev> events;
    for (const auto& r : fx.fix_rows) events.push_back({r.t_s, true, &r, nullptr});
    for (const auto& r : fx.player_rows) events.push_back({r.t_s, false, nullptr, &r});
    std::stable_sort(events.begin(), events.end(),
                      [](const Ev& a, const Ev& b) { return a.t_s < b.t_s; });

    const bool has_explicit_player = !fx.player_rows.empty();
    double next_auto_player_s = 1.0;

    for (const Ev& ev : events) {
        if (!has_explicit_player) {
            while (next_auto_player_s <= ev.t_s) {
                const uint64_t pt = static_cast<uint64_t>(next_auto_player_s * 1e9);
                const double pos = local_start + next_auto_player_s * 1000.0;
                est.on_player_state(static_cast<int64_t>(pos), false, pt);
                next_auto_player_s += 1.0;
            }
        }
        const uint64_t t = static_cast<uint64_t>(ev.t_s * 1e9);
        if (!ev.is_fix) {
            est.on_player_state(static_cast<int64_t>(ev.player->position_ms),
                                ev.player->paused, t);
            continue;
        }
        const FixRow& row = *ev.fix;
        if (row.cols.size() < 2) continue;  // malformed row already caught by parse
        const double offset_ms = row.cols[0];
        const float confidence = static_cast<float>(row.cols[1]);
        const double skew = row.cols.size() >= 3 ? row.cols[2] : 0.0;
        if (est.on_fix(static_cast<int64_t>(std::llround(offset_ms)), t, skew, confidence)) {
            ++out.fixes;
            pol.on_fix_accepted(t);
            const Estimate e = est.estimate_at(t);
            out.final_est = e;
            out.valid = true;
            const Action a = pol.on_estimate(e, est.projected_local_ms(t), t);
            if (a.kind == ActionKind::kSeek) {
                ++out.seeks_issued;
                if (std::llround(std::abs(e.error_ms)) >= 1000) ++out.large_seeks;
                if (apply_seeks) {
                    est.on_local_seek(a.seek_to_ms, t, pol.command_latency_ms());
                    pol.on_seek_issued(t);
                }
            } else if (a.kind == ActionKind::kTrackLost) {
                out.track_lost_seen = true;
            }
        }
    }

    return out;
}

}  // namespace

int main() {
    const char* dir = SYNCCORE_FIXTURES_DIR;
    const std::vector<std::string> paths = list_fixture_files(dir);

    if (paths.empty()) {
        std::printf("FAIL fixture_suite: no *.fixture files found in %s "
                     "(directory missing, unreadable, or empty)\n", dir);
        ++g_failures;
    }

    for (const auto& path : paths) {
        Fixture fx;
        std::string error;
        if (!parse_fixture(path, fx, error)) {
            fail(stem_of(path), "parse error: " + error);
            continue;
        }
        ++g_fixtures_run;
        const std::string mode = fx.gets("mode");
        const Outcome out = (mode == "trace") ? run_trace(fx) : run_world(fx);
        evaluate_expectations(fx, out);
    }

    std::printf("fixture_tests: ran %d fixture(s) from %s\n", g_fixtures_run, dir);
    if (g_failures == 0) {
        std::printf("fixture_tests: all tests passed\n");
        return 0;
    }
    std::printf("fixture_tests: %d check(s) FAILED\n", g_failures);
    return 1;
}
