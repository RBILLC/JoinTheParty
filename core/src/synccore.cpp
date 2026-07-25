// synccore.cpp — session lifecycle, RT-safe capture path, control-plane
// command queue, worker thread + event dispatch.
//
// CORE-02/03: recognition fixes and player states feed the Kalman estimator
// (estimator/), whose filtered estimates drive the correction policy
// (policy/). Time inside the worker advances only from input timestamps —
// SyncCore never reads a clock — so the 15 Hz estimate cadence and the
// recognition-request scheduler are both driven by capture-buffer progress.

#include "synccore/synccore.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <mutex>
#include <thread>
#include <vector>

#include "aec/aec.h"
#include "correlate/correlate.h"
#include "estimator/estimator.h"
#include "policy/policy.h"
#include "spsc_ring.h"
#include "synccore_testing.h"

namespace {

constexpr int32_t kSupportedRateHz = 48000;
constexpr int32_t kSupportedChannels = 1;
constexpr int32_t kDefaultCommandLatencyMs = 250;
// Field Test 2: ±750 could not span the observed ~1 s residual lag on
// Bluetooth routes; widened until the lag's constant component is
// root-caused and absorbed by calibration.
constexpr int32_t kNudgeClampMs = 1500;
constexpr int32_t kMaxFramesPerPush = 1 << 16;
constexpr uint64_t kEstimateEmitPeriodNs = 66'666'667ull;  // ≤ 15 Hz
// CORE-06 (PM-confirmed 2026-07-22): a fix matching our own commanded
// playback position within this window, while in speaker mode, is
// self-hearing — the mic locked onto our own output.
constexpr int64_t kSelfHearingWindowMs = 30;
// ~12 s of 48 kHz mono float + headers, rounded up to 4 MiB by the ring.
constexpr size_t kRingBytes = static_cast<size_t>(kSupportedRateHz) * sizeof(float) * 12;
// NAT-06b: post-AEC capture history retained for recognition sampling.
constexpr size_t kHistoryFrames = static_cast<size_t>(kSupportedRateHz) * 12;

struct Command {
    enum class Kind {
        kRecognitionFix,
        kPlayerState,
        kSeekIssued,
        kLocalPlayback,
        kSetNudge,
        kSetOutputLatency,
        kSetAecMode,
        kPushReference,
        kBeginCalibration,
        kCancelCalibration
    } kind;
    sc_recognition_fix_t fix{};
    sc_player_state_t player{};
    int64_t value_ms = 0;
    uint64_t mono_ns = 0;
    std::vector<float> audio;  // kPushReference payload (control-plane copy)
};

}  // namespace

struct sc_session {
    explicit sc_session(const sc_config_t& c) : cfg(c), ring(kRingBytes) {}

    sc_config_t cfg;

    // --- capture path (producer: audio thread; consumer: worker) ---
    synccore::SpscRing ring;
    std::atomic<uint64_t> overrun_blocks{0};
    std::atomic<uint64_t> frames_consumed{0};

    // Worker-maintained mirror of the policy's (learned) command latency so
    // sc_get_command_latency_ms can read it from any thread.
    std::atomic<int32_t> command_latency_mirror_ms{kDefaultCommandLatencyMs};

    // NAT-06b capture-history tee: circular buffer of the last ~12 s of
    // post-AEC capture, written by the worker during drain, read by
    // sc_copy_recent_capture from any thread. Guarded by history_mtx (both
    // sides are non-RT).
    std::mutex history_mtx;
    std::vector<float> history = std::vector<float>(kHistoryFrames, 0.0f);
    size_t history_write = 0;
    bool history_wrapped = false;
    std::atomic<uint64_t> history_end_ns{0};

    // --- control plane (mutex-guarded) ---
    std::mutex mtx;
    std::condition_variable cv;
    std::deque<Command> commands;
    bool stopping = false;

    sc_event_cb callback = nullptr;
    void* callback_user = nullptr;

    int32_t nudge_ms = 0;
    sc_route_t route;
    int32_t route_latency_prior_ms;
    sc_aec_mode_t aec_mode = SC_AEC_PLATFORM_ONLY;
    bool calibrating = false;

    // Worker-thread-only session state (no lock needed).
    struct {
        synccore::SyncEstimator estimator;
        synccore::CorrectionPolicy policy;
        synccore::ChirpDetector detector{kSupportedRateHz};
        synccore::SyncCoreAec aec{kSupportedRateHz};
        uint64_t now_ns = 0;        // latest input timestamp seen
        uint64_t last_emit_ns = 0;  // last SC_EVT_SYNC_ESTIMATE emission
        int64_t last_commanded_position_ms = -1;  // self-hearing guard, CORE-06
        std::vector<float> scratch;
    } wk;

    std::thread worker;

    void dispatch(sc_event_type_t type, const void* payload) {
        // Snapshot under the lock; invoke outside it so the callback can't
        // deadlock against control-plane calls from other threads. The
        // "cleared callback is never invoked again" guarantee holds because
        // both sc_set_event_callback and this snapshot serialize on mtx and
        // dispatch only ever runs on this worker thread.
        sc_event_cb cb;
        void* user;
        {
            std::lock_guard<std::mutex> lock(mtx);
            cb = callback;
            user = callback_user;
        }
        if (cb) cb(type, payload, user);
    }

    void worker_loop() {
        synccore::RecordHeader hdr;
        for (;;) {
            std::deque<Command> pending;
            {
                std::unique_lock<std::mutex> lock(mtx);
                cv.wait_for(lock, std::chrono::milliseconds(2), [this] {
                    return stopping || !commands.empty();
                });
                pending.swap(commands);
                if (stopping && pending.empty()) return;
            }

            // Drain captured audio; capture timestamps advance session time.
            for (;;) {
                wk.scratch.clear();  // keeps capacity — no realloc per block
                if (!ring.try_read(&hdr, &wk.scratch)) break;
                frames_consumed.fetch_add(hdr.frames, std::memory_order_relaxed);
                const uint64_t block_end =
                    hdr.capture_mono_ns +
                    static_cast<uint64_t>(hdr.frames) * 1'000'000'000ull /
                        kSupportedRateHz;
                wk.now_ns = std::max(wk.now_ns, block_end);
                // CORE-05: speaker-mode capture runs through AEC before any
                // downstream consumer (no-op unless SC_AEC_FULL).
                wk.aec.process_capture(&wk.scratch);
                if (wk.detector.armed())
                    wk.detector.push(wk.scratch.data(), wk.scratch.size(),
                                     hdr.capture_mono_ns);
                append_history(wk.scratch.data(), wk.scratch.size(), block_end);
            }

            for (const Command& cmd : pending) process(cmd);

            tick();
        }
    }

    void emit_estimate(const synccore::Estimate& e) {
        sc_evt_sync_estimate_t out{};
        out.error_ms = e.error_ms;
        out.drift_ppm = e.drift_ppm;
        out.confidence = e.confidence;
        out.converged = e.converged;
        out.last_fix_mono_ns = e.last_fix_mono_ns;
        dispatch(SC_EVT_SYNC_ESTIMATE, &out);
    }

    void apply(const synccore::Action& action) {
        switch (action.kind) {
            case synccore::ActionKind::kNone:
                break;
            case synccore::ActionKind::kSeek: {
                sc_evt_correction_t corr{action.seek_to_ms};
                dispatch(SC_EVT_CORRECTION, &corr);
                break;
            }
            case synccore::ActionKind::kTrackLost:
                wk.estimator.reset();
                wk.policy.reset();
                dispatch(SC_EVT_TRACK_LOST, nullptr);
                break;
        }
    }

    // Time-driven duties: interpolated estimate emissions (≤ 15 Hz) and the
    // recognition-request scheduler. Runs on capture-time progress.
    void tick() {
        if (wk.now_ns == 0) return;
        const synccore::Estimate est = wk.estimator.estimate_at(wk.now_ns);
        if (est.valid &&
            wk.now_ns - wk.last_emit_ns >= kEstimateEmitPeriodNs) {
            wk.last_emit_ns = wk.now_ns;
            emit_estimate(est);
        }
        if (wk.policy.fix_request_due(wk.now_ns))
            dispatch(SC_EVT_REQUEST_FIX, nullptr);
        if (wk.detector.armed()) {
            const auto det = wk.detector.poll(wk.now_ns);
            if (det.done) {
                {
                    std::lock_guard<std::mutex> lock(mtx);
                    calibrating = false;
                }
                sc_evt_calibration_result_t out{det.latency_ms, det.valid};
                dispatch(SC_EVT_CALIBRATION_RESULT, &out);
            }
        }
    }

    void process(const Command& cmd) {
        switch (cmd.kind) {
            case Command::Kind::kRecognitionFix: {
                const uint64_t t = cmd.fix.capture_mono_ns;
                wk.now_ns = std::max(wk.now_ns, t);
                if (wk.policy.is_settling(t)) {
                    sc_evt_fix_rejected_t rej{SC_REJECT_SETTLING};
                    dispatch(SC_EVT_FIX_REJECTED, &rej);
                    return;
                }
                // CORE-06 self-hearing guard (architecture-spec §7.3,
                // ±30 ms PM-confirmed): in speaker mode a fix that matches
                // our own commanded playback position is the mic hearing
                // us, not the room — accepting it would report perfect
                // sync forever. Known v1 limitation: near true lock the
                // external source legitimately sits inside this window
                // too; the energy-dominance condition that disambiguates
                // arrives with the real APM (post-stub).
                if (wk.aec.mode() == SC_AEC_FULL &&
                    wk.last_commanded_position_ms >= 0 &&
                    std::abs(cmd.fix.match_offset_ms -
                             wk.last_commanded_position_ms) <=
                        kSelfHearingWindowMs) {
                    sc_evt_fix_rejected_t rej{SC_REJECT_SELF_HEARING};
                    dispatch(SC_EVT_FIX_REJECTED, &rej);
                    return;
                }
                if (!wk.estimator.on_fix(cmd.fix.match_offset_ms, t,
                                         cmd.fix.frequency_skew,
                                         cmd.fix.confidence)) {
                    sc_evt_fix_rejected_t rej{SC_REJECT_LOW_CONFIDENCE};
                    dispatch(SC_EVT_FIX_REJECTED, &rej);
                    return;
                }
                wk.policy.on_fix_accepted(t);
                const synccore::Estimate est = wk.estimator.estimate_at(t);
                wk.last_emit_ns = t;
                emit_estimate(est);
                apply(wk.policy.on_estimate(
                    est, wk.estimator.projected_local_ms(t), t));
                command_latency_mirror_ms.store(
                    static_cast<int32_t>(
                        std::lround(wk.policy.command_latency_ms())),
                    std::memory_order_relaxed);
                break;
            }
            case Command::Kind::kPlayerState:
                wk.now_ns = std::max(wk.now_ns, cmd.player.received_mono_ns);
                wk.estimator.on_player_state(cmd.player.position_ms,
                                             cmd.player.is_paused,
                                             cmd.player.received_mono_ns);
                break;
            case Command::Kind::kSeekIssued:
                wk.now_ns = std::max(wk.now_ns, cmd.mono_ns);
                wk.estimator.on_local_seek(cmd.value_ms, cmd.mono_ns,
                                           wk.policy.command_latency_ms());
                wk.policy.on_seek_issued(cmd.mono_ns);
                // A seek re-commands our own playback position — keep the
                // self-hearing guard's reference fresh.
                wk.last_commanded_position_ms = cmd.value_ms;
                break;
            case Command::Kind::kLocalPlayback:
                wk.last_commanded_position_ms = cmd.value_ms;
                break;
            case Command::Kind::kSetNudge:
                wk.estimator.set_nudge_ms(static_cast<double>(cmd.value_ms));
                break;
            case Command::Kind::kSetOutputLatency:
                wk.estimator.set_output_latency_ms(
                    static_cast<double>(cmd.value_ms));
                break;
            case Command::Kind::kSetAecMode:
                wk.aec.set_mode(static_cast<sc_aec_mode_t>(cmd.value_ms));
                break;
            case Command::Kind::kPushReference:
                wk.aec.push_reference(cmd.audio.data(), cmd.audio.size());
                break;
            case Command::Kind::kBeginCalibration: {
                // t0 = current capture time. Contract: the shell calls
                // sc_begin_calibration at the instant it commands chirp
                // playback, with capture already flowing.
                wk.detector.arm(wk.now_ns);
                std::lock_guard<std::mutex> lock(mtx);
                calibrating = true;
                break;
            }
            case Command::Kind::kCancelCalibration: {
                wk.detector.disarm();
                std::lock_guard<std::mutex> lock(mtx);
                calibrating = false;
                break;
            }
        }
    }

    void append_history(const float* data, size_t frames, uint64_t end_ns) {
        if (frames == 0) return;
        std::lock_guard<std::mutex> lock(history_mtx);
        for (size_t i = 0; i < frames; ++i) {
            history[history_write] = data[i];
            history_write = (history_write + 1) % kHistoryFrames;
            if (history_write == 0) history_wrapped = true;
        }
        history_end_ns.store(end_ns, std::memory_order_relaxed);
    }

    void enqueue(Command cmd) {
        {
            std::lock_guard<std::mutex> lock(mtx);
            commands.push_back(std::move(cmd));
        }
        cv.notify_one();
    }
};

/* ---------------- Public C ABI ---------------- */

extern "C" {

sc_status_t sc_create(const sc_config_t* cfg, sc_session_t** out) {
    if (!cfg || !out) return SC_ERR_INVALID_ARG;
    if (cfg->sample_rate_hz != kSupportedRateHz) return SC_ERR_UNSUPPORTED_RATE;
    if (cfg->channels != kSupportedChannels) return SC_ERR_INVALID_ARG;
    if (cfg->output_latency_prior_ms < -1 || cfg->command_latency_prior_ms < -1)
        return SC_ERR_INVALID_ARG;

    sc_session_t* s;
    try {
        s = new sc_session(*cfg);
    } catch (...) {
        return SC_ERR_NO_MEMORY;
    }
    if (s->cfg.command_latency_prior_ms < 0)
        s->cfg.command_latency_prior_ms = kDefaultCommandLatencyMs;
    s->route = cfg->initial_route;
    s->route_latency_prior_ms = cfg->output_latency_prior_ms;
    if (cfg->output_latency_prior_ms > 0)
        s->wk.estimator.set_output_latency_ms(
            static_cast<double>(cfg->output_latency_prior_ms));
    s->wk.policy.set_command_latency_ms(
        static_cast<double>(s->cfg.command_latency_prior_ms));
    s->command_latency_mirror_ms.store(s->cfg.command_latency_prior_ms,
                                       std::memory_order_relaxed);
    if (cfg->deadband_ms > 0) {
        s->wk.estimator.set_deadband_ms(static_cast<double>(cfg->deadband_ms));
        s->wk.policy.set_deadband_ms(static_cast<double>(cfg->deadband_ms));
    }
    s->worker = std::thread([s] { s->worker_loop(); });
    *out = s;
    return SC_OK;
}

void sc_destroy(sc_session_t* s) {
    if (!s) return;
    {
        std::lock_guard<std::mutex> lock(s->mtx);
        s->stopping = true;
    }
    s->cv.notify_one();
    s->worker.join();
    delete s;
}

void sc_push_capture(sc_session_t* s, const float* mono, int32_t frames,
                     uint64_t capture_mono_ns) {
    if (!s || !mono || frames <= 0 || frames > kMaxFramesPerPush) return;
    synccore::RecordHeader hdr;
    hdr.frames = static_cast<uint32_t>(frames);
    hdr.payload_bytes = hdr.frames * static_cast<uint32_t>(sizeof(float));
    hdr.capture_mono_ns = capture_mono_ns;
    if (!s->ring.try_write(hdr, mono))
        s->overrun_blocks.fetch_add(1, std::memory_order_relaxed);
}

sc_status_t sc_submit_recognition_fix(sc_session_t* s,
                                      const sc_recognition_fix_t* fix) {
    if (!s || !fix) return SC_ERR_INVALID_ARG;
    if (fix->confidence < 0.0f || fix->confidence > 1.0f) return SC_ERR_INVALID_ARG;
    Command cmd;
    cmd.kind = Command::Kind::kRecognitionFix;
    cmd.fix = *fix;
    s->enqueue(std::move(cmd));
    return SC_OK;
}

sc_status_t sc_submit_player_state(sc_session_t* s, const sc_player_state_t* ps) {
    if (!s || !ps) return SC_ERR_INVALID_ARG;
    Command cmd;
    cmd.kind = Command::Kind::kPlayerState;
    cmd.player = *ps;
    s->enqueue(std::move(cmd));
    return SC_OK;
}

sc_status_t sc_set_user_nudge_ms(sc_session_t* s, int32_t nudge_ms) {
    if (!s) return SC_ERR_INVALID_ARG;
    const int32_t clamped = std::clamp(nudge_ms, -kNudgeClampMs, kNudgeClampMs);
    {
        std::lock_guard<std::mutex> lock(s->mtx);
        s->nudge_ms = clamped;
    }
    Command cmd;
    cmd.kind = Command::Kind::kSetNudge;
    cmd.value_ms = clamped;
    s->enqueue(std::move(cmd));
    return SC_OK;
}

sc_status_t sc_set_output_route(sc_session_t* s, sc_route_t route,
                                int32_t latency_prior_ms) {
    if (!s) return SC_ERR_INVALID_ARG;
    if (route < SC_ROUTE_SPEAKER || route > SC_ROUTE_BLUETOOTH)
        return SC_ERR_INVALID_ARG;
    {
        std::lock_guard<std::mutex> lock(s->mtx);
        s->route = route;
        s->route_latency_prior_ms = latency_prior_ms;
    }
    Command cmd;
    cmd.kind = Command::Kind::kSetOutputLatency;
    cmd.value_ms = latency_prior_ms > 0 ? latency_prior_ms : 0;
    s->enqueue(std::move(cmd));
    return SC_OK;
}

sc_status_t sc_set_aec_mode(sc_session_t* s, sc_aec_mode_t mode) {
    if (!s) return SC_ERR_INVALID_ARG;
    if (mode < SC_AEC_OFF || mode > SC_AEC_FULL) return SC_ERR_INVALID_ARG;
    {
        std::lock_guard<std::mutex> lock(s->mtx);
        s->aec_mode = mode;
    }
    Command cmd;
    cmd.kind = Command::Kind::kSetAecMode;
    cmd.value_ms = static_cast<int64_t>(mode);
    s->enqueue(std::move(cmd));
    return SC_OK;
}

sc_status_t sc_notify_seek_issued(sc_session_t* s, int64_t target_ms,
                                  uint64_t issued_mono_ns) {
    if (!s || target_ms < 0) return SC_ERR_INVALID_ARG;
    Command cmd;
    cmd.kind = Command::Kind::kSeekIssued;
    cmd.value_ms = target_ms;
    cmd.mono_ns = issued_mono_ns;
    s->enqueue(std::move(cmd));
    return SC_OK;
}

sc_status_t sc_notify_local_playback(sc_session_t* s, int64_t commanded_position_ms) {
    if (!s || commanded_position_ms < 0) return SC_ERR_INVALID_ARG;
    Command cmd;
    cmd.kind = Command::Kind::kLocalPlayback;
    cmd.value_ms = commanded_position_ms;
    s->enqueue(std::move(cmd));
    return SC_OK;
}

sc_status_t sc_push_reference(sc_session_t* s, const float* mono, int32_t frames,
                              int64_t track_position_ms) {
    if (!s || !mono || frames <= 0 || track_position_ms < 0)
        return SC_ERR_INVALID_ARG;
    Command cmd;
    cmd.kind = Command::Kind::kPushReference;
    cmd.value_ms = track_position_ms;
    cmd.audio.assign(mono, mono + frames);  // control-plane copy (non-RT)
    s->enqueue(std::move(cmd));
    return SC_OK;
}

int32_t sc_copy_recent_capture(sc_session_t* s, float* out, int32_t max_frames,
                               uint64_t* out_end_mono_ns) {
    if (!s || !out || max_frames <= 0) return 0;
    std::lock_guard<std::mutex> lock(s->history_mtx);
    const size_t available =
        s->history_wrapped ? kHistoryFrames : s->history_write;
    const size_t n = std::min(static_cast<size_t>(max_frames), available);
    if (n == 0) return 0;
    // Chronological copy of the newest n frames ending at history_write.
    size_t start = (s->history_write + kHistoryFrames - n) % kHistoryFrames;
    for (size_t i = 0; i < n; ++i) {
        out[i] = s->history[start];
        start = (start + 1) % kHistoryFrames;
    }
    if (out_end_mono_ns)
        *out_end_mono_ns = s->history_end_ns.load(std::memory_order_relaxed);
    return static_cast<int32_t>(n);
}

sc_status_t sc_get_command_latency_ms(sc_session_t* s, int32_t* out_ms) {
    if (!s || !out_ms) return SC_ERR_INVALID_ARG;
    *out_ms = s->command_latency_mirror_ms.load(std::memory_order_relaxed);
    return SC_OK;
}

sc_status_t sc_begin_calibration(sc_session_t* s) {
    if (!s) return SC_ERR_INVALID_ARG;
    {
        std::lock_guard<std::mutex> lock(s->mtx);
        if (s->calibrating) return SC_ERR_BAD_STATE;
    }
    Command cmd;
    cmd.kind = Command::Kind::kBeginCalibration;
    s->enqueue(std::move(cmd));
    return SC_OK;
}

sc_status_t sc_cancel_calibration(sc_session_t* s) {
    if (!s) return SC_ERR_INVALID_ARG;
    Command cmd;
    cmd.kind = Command::Kind::kCancelCalibration;
    s->enqueue(std::move(cmd));
    return SC_OK;
}

sc_status_t sc_set_event_callback(sc_session_t* s, sc_event_cb cb, void* user_data) {
    if (!s) return SC_ERR_INVALID_ARG;
    std::lock_guard<std::mutex> lock(s->mtx);
    s->callback = cb;
    s->callback_user = user_data;
    return SC_OK;
}

/* ---- Test hooks (synccore_testing.h; not part of the public ABI) ---- */

void sc_test_stats(sc_session_t* s, uint64_t* frames_consumed,
                   uint64_t* overrun_blocks) {
    if (!s) return;
    if (frames_consumed)
        *frames_consumed = s->frames_consumed.load(std::memory_order_relaxed);
    if (overrun_blocks)
        *overrun_blocks = s->overrun_blocks.load(std::memory_order_relaxed);
}

}  // extern "C"
