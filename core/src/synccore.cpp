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
#include "dsp/lag_window.h"
#include "estimator/estimator.h"
#include "policy/policy.h"
#include "spsc_ring.h"
#include "synccore_testing.h"

namespace {

constexpr int32_t kSupportedRateHz = 48000;
constexpr int32_t kSupportedChannels = 1;
constexpr int32_t kDefaultCommandLatencyMs = 250;
// Field Test 2: ±750 could not span the observed ~1 s residual lag on
// Bluetooth routes. Widened again for the ear-rebase semantics (the shell's
// engine setpoint = wheel trim + absorbed measurement bias, so it can
// legitimately exceed the wheel's own ±1500 UI range).
constexpr int32_t kNudgeClampMs = 4000;
constexpr int32_t kMaxFramesPerPush = 1 << 16;
constexpr uint64_t kEstimateEmitPeriodNs = 66'666'667ull;  // ≤ 15 Hz
// CAL-05 input-level ballistics (technical-requirements.md §2.1): one-pole
// attack/release envelope over the post-AEC capture, ~10 ms attack / ~300 ms
// release. Kept as time constants (not a fixed per-call coefficient) because
// the per-block coefficient depends on how much audio-time each drained
// block actually represents — see step_input_level's derivation below.
constexpr double kInputLevelAttackSec = 0.010;
constexpr double kInputLevelReleaseSec = 0.300;
// The worker's own idle-poll cadence (see cv.wait_for below).
constexpr auto kWorkerPollInterval = std::chrono::milliseconds(2);
// Upper bound on a single idle release step, so a stalled worker (debugger,
// starvation) can't collapse the level meter in one jump.
constexpr double kMaxIdleDecayStepSec = 0.100;
// CORE-06 self-match guard, rebuilt after Field Test 3 (2026-07-26).
//
// The room plays continuously, so its offset MUST advance 1:1 with the
// wall clock. That gives a prediction, and a fix that breaks it while
// simultaneously landing on our OWN audible position is the mic hearing
// us rather than the room. Both conditions are required: at true lock the
// room and our own position coincide, and that fix still tracks the room
// prediction, so it is accepted normally. A room perturbation (someone
// skips the source) breaks the prediction but does NOT match our position,
// so it is accepted too — which is what makes this safe to run ungated.
//
// Field Test 3 measured the two populations directly: self-matches landed
// within 200 ms of our own position while running 1.2–1.8 s off the room
// prediction, and genuine room fixes tracked the prediction within 250 ms.
constexpr double kRoomContinuityGateMs = 500.0;
constexpr double kSelfMatchWindowMs = 400.0;
// Beyond this the room prediction has coasted too long to arbitrate. MUST
// stay well clear of PolicyConfig::fix_interval_max_ns (30 s when converged)
// plus a recognition round trip — at 30 s the guard switched itself off at
// exactly the moment the session converged, which is when it matters most.
constexpr uint64_t kRoomPredictionMaxAgeNs = 90'000'000'000ull;
// A reference that keeps rejecting is more likely wrong than the room is:
// the anchor can only have been seeded by a fix that was itself accepted
// without arbitration. Drop it after this many and let the room re-seed —
// Field Test 4 hit exactly this lockout, rejecting every fix for a minute
// while the mic confirmed the session was actually in sync.
constexpr int kMaxConsecutiveSelfRejects = 3;
// ~12 s of 48 kHz mono float + headers, rounded up to 4 MiB by the ring.
constexpr size_t kRingBytes = static_cast<size_t>(kSupportedRateHz) * sizeof(float) * 12;
// NAT-06b: post-AEC capture history retained for recognition sampling.
constexpr size_t kHistoryFrames = static_cast<size_t>(kSupportedRateHz) * 12;
// CAL-03 acoustic referee (technical-requirements.md §2.6): search-window
// bounds handed to the ported dsp/lag_window.h analyze_window, identical to
// lag_analyzer's own field-proven CLI defaults.
constexpr double kResidualMinLagMs = 40.0;
// LOAD-BEARING — do not widen. At 4000 ms the analyzer locks onto harmonics
// of the music's own periodicity and reports spurious multi-second lags
// (docs/sync-test-results.md). 2500 ms is the field-validated ceiling.
constexpr double kResidualMaxLagMs = 2500.0;

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
        kCancelCalibration,
        kSampleLatencyResidual
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

    // CAL-05: worker-maintained smoothed input level (0..1), relaxed store
    // by the worker / relaxed load by sc_get_input_level. Zero-initialized
    // so a session that has never received capture reports silence, not
    // garbage, with no special-casing in the getter.
    std::atomic<float> input_level{0.0f};

    // CAL-03: worker-maintained mirror of the AEC mode currently in effect
    // (sc_aec_mode_t stored as int32_t), relaxed store by the worker /
    // relaxed load by the test hook sc_test_get_aec_mode. Exists so a test
    // can observe from another thread that the referee's forced-OFF window
    // was correctly restored, without racing the worker's own wk.aec (which
    // is worker-thread-only, unsynchronized state).
    std::atomic<int32_t> aec_mode_mirror{static_cast<int32_t>(SC_AEC_PLATFORM_ONLY)};

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
        // Retained for diagnostics only — the self-match guard no longer
        // reads it (it was a frozen seek target that never advanced with the
        // wall clock, which is why the old guard never fired).
        int64_t last_commanded_position_ms = -1;
        // Room-timeline reference for the self-match guard: offset + capture
        // time of the last accepted fix. It only earns the right to REJECT
        // anything once a second accepted fix has corroborated it — the very
        // first fix of a session is accepted without arbitration, so a lone
        // seed may itself be a self-match and must not be trusted to judge.
        int64_t room_anchor_offset_ms = -1;
        uint64_t room_anchor_ns = 0;
        bool room_anchor_confirmed = false;
        int consecutive_self_rejects = 0;
        // A fix that broke the room timeline without matching our own
        // position. It is held aside rather than acted on: if the NEXT fix
        // continues ITS timeline the room genuinely moved and we adopt it,
        // otherwise it was a one-off recognizer error and the established
        // room timeline survives. Without this, a single bad offset wiped
        // the confirmed anchor and disarmed the self-match guard.
        int64_t cand_offset_ms = -1;
        uint64_t cand_ns = 0;
        std::vector<float> scratch;
        // CAL-05: one-pole envelope follower state (full double precision;
        // only the published atomic is truncated to float).
        double input_level_state = 0.0;
        // Deferred calibration arm — set by kBeginCalibration, consumed by
        // the next drained capture block (see the FIELD FIX comment there).
        bool pending_calibration_arm = false;
        // Wall-clock stamp of the last envelope step, so the idle release
        // decays by measured elapsed time rather than an assumed poll
        // period — see decay_input_level_idle().
        std::chrono::steady_clock::time_point last_level_update =
            std::chrono::steady_clock::now();
        // CAL-03: reused across calls to avoid a fresh 12 s allocation per
        // referee sample (sized lazily to kHistoryFrames on first use).
        std::vector<float> residual_scratch;
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
                cv.wait_for(lock, kWorkerPollInterval, [this] {
                    return stopping || !commands.empty();
                });
                pending.swap(commands);
                if (stopping && pending.empty()) return;
            }

            // Drain captured audio; capture timestamps advance session time.
            bool drained_any = false;
            for (;;) {
                wk.scratch.clear();  // keeps capacity — no realloc per block
                if (!ring.try_read(&hdr, &wk.scratch)) break;
                drained_any = true;
                frames_consumed.fetch_add(hdr.frames, std::memory_order_relaxed);
                const uint64_t block_end =
                    hdr.capture_mono_ns +
                    static_cast<uint64_t>(hdr.frames) * 1'000'000'000ull /
                        kSupportedRateHz;
                wk.now_ns = std::max(wk.now_ns, block_end);
                // CORE-05: speaker-mode capture runs through AEC before any
                // downstream consumer (no-op unless SC_AEC_FULL).
                wk.aec.process_capture(&wk.scratch);
                if (wk.pending_calibration_arm) {
                    // Deferred arm (see kBeginCalibration): this block's
                    // capture timestamp IS the present.
                    wk.detector.arm(hdr.capture_mono_ns);
                    wk.pending_calibration_arm = false;
                }
                if (wk.detector.armed())
                    wk.detector.push(wk.scratch.data(), wk.scratch.size(),
                                     hdr.capture_mono_ns);
                // CAL-05: same post-AEC buffer append_history taps below —
                // no new tap into the capture path.
                update_input_level(wk.scratch);
                append_history(wk.scratch.data(), wk.scratch.size(), block_end);
            }
            if (!drained_any) decay_input_level_idle();

            for (const Command& cmd : pending) process(cmd);

            tick();
        }
    }

    // CAL-05: one-pole attack/release envelope follower (spec §2.1).
    //
    // Coefficient derivation: for y += alpha * (x - y), the step response
    // after elapsed time T is 1 - e^(-T/tau) toward x. So the coefficient
    // for a given block is alpha = 1 - e^(-T/tau), where T is that block's
    // OWN duration and tau is the attack or release time constant — this is
    // derived per call from the actual block length rather than a hardcoded
    // per-call factor, so the ballistics stay correct however the shell
    // chunks its capture pushes.
    void step_input_level(double magnitude, double block_sec) {
        const double tau = magnitude > wk.input_level_state
                                ? kInputLevelAttackSec
                                : kInputLevelReleaseSec;
        const double alpha = 1.0 - std::exp(-block_sec / tau);
        wk.input_level_state += alpha * (magnitude - wk.input_level_state);
        wk.input_level_state = std::clamp(wk.input_level_state, 0.0, 1.0);
        input_level.store(static_cast<float>(wk.input_level_state),
                          std::memory_order_relaxed);
        // Every step restamps, so the idle path's measured dt covers only
        // the gap since the last update — whichever path produced it.
        wk.last_level_update = std::chrono::steady_clock::now();
    }

    // Instantaneous level for a drained block: peak absolute sample value
    // (capture is already normalized float, so no separate dBFS/clamp step
    // is needed on the far side — spec §2.1). block_sec comes from the
    // block's own frame count, not wall-clock timing of the push.
    void update_input_level(const std::vector<float>& block) {
        if (block.empty()) return;
        float peak = 0.0f;
        for (float v : block) peak = std::max(peak, std::fabs(v));
        peak = std::min(peak, 1.0f);
        const double block_sec = static_cast<double>(block.size()) /
                                 static_cast<double>(kSupportedRateHz);
        step_input_level(static_cast<double>(peak), block_sec);
    }

    // No capture drained this worker iteration — nothing to compute a
    // magnitude from. Rather than leaving the last loud value stale forever
    // once capture actually stops (Oboe's stream close sends no sc_*
    // notification; the worker's only signal is "no more blocks arrive"),
    // release the envelope toward silence.
    //
    // The elapsed duration here MUST be measured, not assumed. An earlier
    // version passed kWorkerPollInterval (2 ms) as the nominal iteration
    // time, reasoning that a compile-time constant avoids a clock read. It
    // decayed ~7x too slowly on Windows, where the default timer resolution
    // is ~15.6 ms, so cv.wait_for(2ms) actually sleeps far longer than 2 ms
    // and the envelope advanced 2 ms of "time" per ~15 ms of reality
    // (test_decays_after_capture_stops_entirely caught it).
    //
    // Reading steady_clock here does NOT breach the "SyncCore never reads a
    // clock" invariant. That rule exists so *session timing* — offsets,
    // drift, correction targets — derives solely from capture timestamps and
    // is therefore immune to clock skew between devices. This is a
    // display-only level meter whose decay rate affects nothing downstream;
    // no estimate, event, or correction reads it.
    void decay_input_level_idle() {
        const double elapsed_sec =
            std::chrono::duration<double>(std::chrono::steady_clock::now() -
                                          wk.last_level_update)
                .count();
        // Guard against a long stall (debugger, thread starvation) dumping a
        // huge dt into the envelope: clamp to a sane upper bound.
        step_input_level(0.0, std::min(elapsed_sec, kMaxIdleDecayStepSec));
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
                // The room timeline we were predicting is gone; a stale
                // reference would arbitrate the re-bootstrap fixes.
                wk.room_anchor_offset_ms = -1;
                wk.room_anchor_confirmed = false;
                wk.consecutive_self_rejects = 0;
                wk.cand_offset_ms = -1;
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
                // CORE-06 self-match guard (architecture-spec §7.3).
                //
                // The previous form compared the fix against
                // last_commanded_position_ms — a FROZEN seek target that
                // never advanced with the wall clock, so it went stale
                // within a second and never fired. Field Test 3 caught the
                // consequence: with the phone's own output audible to its
                // own mic, ACR locked onto us on ~40% of fixes, each one
                // reporting near-zero error and convincing the filter it
                // was synced while the room ran 1.2 s ahead. The session
                // oscillated for 6 minutes and never converged.
                const double off = static_cast<double>(cmd.fix.match_offset_ms);
                const bool anchor_usable =
                    wk.room_anchor_offset_ms >= 0 && t > wk.room_anchor_ns &&
                    t - wk.room_anchor_ns < kRoomPredictionMaxAgeNs;
                const double predicted_room =
                    anchor_usable
                        ? static_cast<double>(wk.room_anchor_offset_ms) +
                              static_cast<double>(t - wk.room_anchor_ns) / 1e6
                        : 0.0;
                const bool tracks_room =
                    anchor_usable &&
                    std::abs(off - predicted_room) <= kRoomContinuityGateMs;
                const bool cand_usable =
                    wk.cand_offset_ms >= 0 && t > wk.cand_ns &&
                    t - wk.cand_ns < kRoomPredictionMaxAgeNs;
                const bool tracks_cand =
                    cand_usable &&
                    std::abs(off - (static_cast<double>(wk.cand_offset_ms) +
                                    static_cast<double>(t - wk.cand_ns) / 1e6)) <=
                        kRoomContinuityGateMs;

                if (anchor_usable && wk.room_anchor_confirmed && !tracks_room &&
                    wk.estimator.has_player_state() &&
                    std::abs(off - wk.estimator.local_audible_ms(t)) <=
                        kSelfMatchWindowMs) {
                    if (++wk.consecutive_self_rejects >=
                        kMaxConsecutiveSelfRejects) {
                        // We are almost certainly judging with a bad
                        // reference. Forget it; the next fix re-seeds.
                        wk.room_anchor_offset_ms = -1;
                        wk.room_anchor_confirmed = false;
                        wk.consecutive_self_rejects = 0;
                    }
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
                // Maintain the room timeline. Field Test 5 (song 2) showed
                // why this must NOT simply re-seed on every accepted fix:
                // with the recognizer alternating between the room and our
                // own audio, every other fix broke continuity, so the anchor
                // was never confirmed, so the guard never rejected anything —
                // and the engine settled 1.7 s ahead of the room while
                // reporting −3 ms. The established timeline has to survive an
                // isolated bad offset.
                if (tracks_room) {
                    wk.room_anchor_offset_ms = cmd.fix.match_offset_ms;
                    wk.room_anchor_ns = t;
                    wk.room_anchor_confirmed = true;
                    wk.cand_offset_ms = -1;
                } else if (tracks_cand) {
                    // Two fixes now agree on a DIFFERENT continuous timeline:
                    // the room really did move (new song, someone skipped).
                    wk.room_anchor_offset_ms = cmd.fix.match_offset_ms;
                    wk.room_anchor_ns = t;
                    wk.room_anchor_confirmed = true;
                    wk.cand_offset_ms = -1;
                } else {
                    // Hold it aside; keep whatever room timeline we had.
                    wk.cand_offset_ms = cmd.fix.match_offset_ms;
                    wk.cand_ns = t;
                    if (!anchor_usable) {
                        wk.room_anchor_offset_ms = cmd.fix.match_offset_ms;
                        wk.room_anchor_ns = t;
                        wk.room_anchor_confirmed = false;
                    }
                }
                wk.consecutive_self_rejects = 0;
                // The OBSERVATION belongs at capture time t (above), but the
                // DECISION belongs at now: a fix is 0.8–1.9 s old by the time
                // the recognizer answers, and a seek target computed for t
                // lands that much behind the room. The policy already leads
                // by the command latency; it must also lead by the
                // recognition round trip. wk.now_ns is real session time —
                // continuous capture pushes advance it — so this costs
                // nothing and keeps the no-clocks-in-the-core rule.
                //
                // Field Test 4: this is the systematic ~1 s lag that survived
                // every other fix, because every correction re-established it.
                const uint64_t decide_ns = wk.now_ns;
                const synccore::Estimate est = wk.estimator.estimate_at(decide_ns);
                wk.last_emit_ns = decide_ns;
                emit_estimate(est);
                apply(wk.policy.on_estimate(
                    est, wk.estimator.projected_local_ms(decide_ns), decide_ns));
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
                aec_mode_mirror.store(static_cast<int32_t>(cmd.value_ms),
                                      std::memory_order_relaxed);
                break;
            case Command::Kind::kPushReference:
                wk.aec.push_reference(cmd.audio.data(), cmd.audio.size());
                break;
            case Command::Kind::kBeginCalibration: {
                // FIELD FIX (device test, 2026-07-28): arming with wk.now_ns
                // directly trusted a contract nothing enforced — "capture
                // already flowing". When the shell starts capture and
                // calibration in the same breath (the idle-screen flow), no
                // block has drained yet, so wk.now_ns is frozen at the LAST
                // session's final timestamp. Armed with a t0 minutes stale,
                // the detector's 8 s window expired on its first poll —
                // before the chirp ever sounded — and a rapid re-tap
                // "succeeded" by measuring the staleness of the clock
                // (device log: a 2232 ms "latency" that was exactly the gap
                // between two attempts). Defer the arm to the next drained
                // capture block, whose timestamp is by construction the
                // present: t0 can then never be staler than one block.
                wk.pending_calibration_arm = true;
                std::lock_guard<std::mutex> lock(mtx);
                calibrating = true;
                break;
            }
            case Command::Kind::kCancelCalibration: {
                wk.detector.disarm();
                wk.pending_calibration_arm = false;
                std::lock_guard<std::mutex> lock(mtx);
                calibrating = false;
                break;
            }
            case Command::Kind::kSampleLatencyResidual: {
                // Gate on convergence FIRST: the estimator's current
                // projection at session time, the same state
                // SC_EVT_SYNC_ESTIMATE.converged reports. While locked the
                // position error is ~0, so any acoustic gap this window
                // finds is attributable to output-chain latency rather than
                // estimator error (tech-req §2.6's attribution argument);
                // sampling while unconverged would conflate the two.
                //
                // The analysis still RUNS when unconverged (below) so
                // residual_ms/peak_ratio are populated for diagnostics —
                // only the `valid` bit is gated on convergence, matching the
                // acceptance test's "even with a clean high-peak_ratio
                // signal present" case.
                const synccore::Estimate est = wk.estimator.estimate_at(wk.now_ns);
                const bool converged_locked = est.valid && est.converged;

                // Force AEC off for the sampled window, restore immediately
                // after. With today's passthrough stub (aec.h) this changes
                // nothing observable — SC_AEC_FULL doesn't actually cancel
                // anything yet — but once the real APM lands, leaving AEC on
                // would cancel the very echo of our own output this
                // measurement is trying to hear. The referee only measures
                // and restores; it never leaves the session in a different
                // AEC state than it found it in.
                const sc_aec_mode_t prior_aec_mode = wk.aec.mode();
                wk.aec.set_mode(SC_AEC_OFF);
                aec_mode_mirror.store(static_cast<int32_t>(SC_AEC_OFF),
                                      std::memory_order_relaxed);

                // Reads capture history only — no new audio captured or
                // played. Reuses the public accessor (safe to call from the
                // worker thread itself: it only takes history_mtx, which the
                // worker never holds across command processing).
                if (wk.residual_scratch.size() != kHistoryFrames)
                    wk.residual_scratch.assign(kHistoryFrames, 0.0f);
                const int32_t n = sc_copy_recent_capture(
                    this, wk.residual_scratch.data(),
                    static_cast<int32_t>(kHistoryFrames), nullptr);

                wk.aec.set_mode(prior_aec_mode);
                aec_mode_mirror.store(static_cast<int32_t>(prior_aec_mode),
                                      std::memory_order_relaxed);

                sc_evt_latency_residual_t out{};
                if (n > 0) {
                    // Single-buffer autocorrelation, no reference signal
                    // (tech-req §2.6): the mic hears two copies of the same
                    // song (ours and the room's); the peak between them is
                    // the acoustic error a listener perceives.
                    const synccore::WindowLag lag = synccore::analyze_window(
                        wk.residual_scratch.data(), static_cast<size_t>(n),
                        kSupportedRateHz, kResidualMinLagMs, kResidualMaxLagMs);
                    out.residual_ms = static_cast<int32_t>(std::lround(lag.lag_ms));
                    out.peak_ratio = static_cast<float>(lag.peak_ratio);
                    // lag.found already IS "peak_ratio > 4.0" (lag_window.cpp);
                    // AND it with convergence for the full gate.
                    out.valid = converged_locked && lag.found;
                }
                // Verifier, not a servo: no write to output_latency_prior_ms,
                // route_latency_prior_ms, the estimator, or the policy —
                // this command only measures and emits.
                dispatch(SC_EVT_LATENCY_RESIDUAL, &out);
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

sc_status_t sc_get_input_level(sc_session_t* s, float* out_level) {
    if (!s || !out_level) return SC_ERR_INVALID_ARG;
    *out_level = s->input_level.load(std::memory_order_relaxed);
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

sc_status_t sc_sample_latency_residual(sc_session_t* s) {
    if (!s) return SC_ERR_INVALID_ARG;
    Command cmd;
    cmd.kind = Command::Kind::kSampleLatencyResidual;
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

// CAL-03: last AEC mode the worker actually applied — lets a test confirm a
// sc_sample_latency_residual call restored the prior mode instead of
// leaving AEC forced off.
void sc_test_get_aec_mode(sc_session_t* s, sc_aec_mode_t* out_mode) {
    if (!s || !out_mode) return;
    *out_mode = static_cast<sc_aec_mode_t>(
        s->aec_mode_mirror.load(std::memory_order_relaxed));
}

}  // extern "C"
