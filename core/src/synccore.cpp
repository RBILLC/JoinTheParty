// synccore.cpp — CORE-01 skeleton: session lifecycle, RT-safe capture path,
// control-plane command queue, worker thread + event dispatch.
//
// No DSP lives here yet. The worker drains the audio ring (so the capture
// path behaves exactly as it will in production) and answers control inputs
// with synthetic events where the contract requires a response, so the
// shell bridges (NAT-03/04) can be built and tested against real event
// traffic before CORE-02 lands the estimator.

#include "synccore/synccore.h"

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <mutex>
#include <thread>
#include <vector>

#include "spsc_ring.h"
#include "synccore_testing.h"

namespace {

constexpr int32_t kSupportedRateHz = 48000;
constexpr int32_t kSupportedChannels = 1;
constexpr int32_t kDefaultCommandLatencyMs = 250;
constexpr int32_t kNudgeClampMs = 750;
constexpr int32_t kMaxFramesPerPush = 1 << 16;
// ~12 s of 48 kHz mono float + headers, rounded up to 4 MiB by the ring.
constexpr size_t kRingBytes = static_cast<size_t>(kSupportedRateHz) * sizeof(float) * 12;

struct Command {
    enum class Kind {
        kRecognitionFix,
        kPlayerState,
        kSeekIssued,
        kLocalPlayback,
        kBeginCalibration,
        kCancelCalibration
    } kind;
    sc_recognition_fix_t fix{};
    sc_player_state_t player{};
    int64_t value_ms = 0;
    uint64_t mono_ns = 0;
};

}  // namespace

struct sc_session {
    explicit sc_session(const sc_config_t& c) : cfg(c), ring(kRingBytes) {}

    sc_config_t cfg;

    // --- capture path (producer: audio thread; consumer: worker) ---
    synccore::SpscRing ring;
    std::atomic<uint64_t> overrun_blocks{0};
    std::atomic<uint64_t> frames_consumed{0};

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
        bool has_player_state = false;
        sc_player_state_t last_player{};
        int64_t last_commanded_position_ms = -1;
        uint64_t settle_until_mono_ns = 0;
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

            // Drain captured audio. CORE-02 will feed this to the DSP chain;
            // for now consuming it keeps the producer-side contract honest.
            wk.scratch.clear();
            while (ring.try_read(&hdr, &wk.scratch)) {
                frames_consumed.fetch_add(hdr.frames, std::memory_order_relaxed);
                if (wk.scratch.size() > static_cast<size_t>(kSupportedRateHz))
                    wk.scratch.clear();  // bound scratch growth per iteration
            }

            for (const Command& cmd : pending) process(cmd);
        }
    }

    void process(const Command& cmd) {
        switch (cmd.kind) {
            case Command::Kind::kRecognitionFix: {
                if (cmd.fix.capture_mono_ns < wk.settle_until_mono_ns) {
                    sc_evt_fix_rejected_t rej{SC_REJECT_SETTLING};
                    dispatch(SC_EVT_FIX_REJECTED, &rej);
                    return;
                }
                // Synthetic estimate: echoes the fix so bridges see realistic
                // traffic. Replaced by the Kalman estimator in CORE-02.
                sc_evt_sync_estimate_t est{};
                est.error_ms = wk.has_player_state
                                   ? static_cast<double>(wk.last_player.position_ms -
                                                         cmd.fix.match_offset_ms)
                                   : 0.0;
                est.drift_ppm = 0.0;
                est.confidence = cmd.fix.confidence;
                est.converged = false;
                est.last_fix_mono_ns = cmd.fix.capture_mono_ns;
                dispatch(SC_EVT_SYNC_ESTIMATE, &est);
                break;
            }
            case Command::Kind::kPlayerState:
                wk.has_player_state = true;
                wk.last_player = cmd.player;
                break;
            case Command::Kind::kSeekIssued:
                // 3 s settle window (technical-requirements.md §1.2 note).
                wk.settle_until_mono_ns = cmd.mono_ns + 3'000'000'000ull;
                break;
            case Command::Kind::kLocalPlayback:
                wk.last_commanded_position_ms = cmd.value_ms;
                break;
            case Command::Kind::kBeginCalibration: {
                std::lock_guard<std::mutex> lock(mtx);
                calibrating = true;
                break;
            }
            case Command::Kind::kCancelCalibration: {
                std::lock_guard<std::mutex> lock(mtx);
                calibrating = false;
                break;
            }
        }
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
    std::lock_guard<std::mutex> lock(s->mtx);
    s->nudge_ms = std::clamp(nudge_ms, -kNudgeClampMs, kNudgeClampMs);
    return SC_OK;
}

sc_status_t sc_set_output_route(sc_session_t* s, sc_route_t route,
                                int32_t latency_prior_ms) {
    if (!s) return SC_ERR_INVALID_ARG;
    if (route < SC_ROUTE_SPEAKER || route > SC_ROUTE_BLUETOOTH)
        return SC_ERR_INVALID_ARG;
    std::lock_guard<std::mutex> lock(s->mtx);
    s->route = route;
    s->route_latency_prior_ms = latency_prior_ms;
    return SC_OK;
}

sc_status_t sc_set_aec_mode(sc_session_t* s, sc_aec_mode_t mode) {
    if (!s) return SC_ERR_INVALID_ARG;
    if (mode < SC_AEC_OFF || mode > SC_AEC_FULL) return SC_ERR_INVALID_ARG;
    std::lock_guard<std::mutex> lock(s->mtx);
    s->aec_mode = mode;
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
    // Consumed by the AEC3 wrapper in CORE-05/06. Accepted and discarded here
    // so shells can wire the call path now.
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
