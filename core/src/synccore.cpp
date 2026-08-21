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
#include <array>
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
#include "dsp/oss_ring.h"
#include "estimator/estimator.h"
#include "estimator/hypothesis_bank.h"
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

// DSP-03a (tech-req §2.12): volume-duck dip detector. 20 ms non-overlapping
// RMS hops -> 50 Hz log-envelope, matching kSampleLatencyResidual's own
// field-validated windowing granularity.
constexpr int32_t kDuckHopFrames = 960;  // 20 ms @ 48 kHz
constexpr long long kDuckHopNs = 20'000'000ll;
// Preceding-3s baseline, expressed in hops (3000 ms / 20 ms) — fixed
// regardless of duck_ms, since the baseline window's OWN span never
// changes, only the search window past it does.
constexpr long long kDuckBaselineHops = 150;
constexpr uint64_t kDuckBaselineNs = 3'000'000'000ull;
// Search window bounds (tech-req §2.12, R2): [echo_ns - 250 ms,
// echo_ns + duck_ms + 750 ms] — wide enough to absorb App Remote and
// BT-absolute-volume actuation latency around the echo.
constexpr uint64_t kDuckPreWindowNs = 250'000'000ull;
constexpr uint64_t kDuckPostMarginNs = 750'000'000ull;
// R2: small margin on top of the search window's own +750 ms reach so
// tick() only runs the deferred analysis once the LAST hop of that window
// has actually drained into capture history.
constexpr uint64_t kDuckAnalysisMarginNs = 250'000'000ull;

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
        kSampleLatencyResidual,
        kProbeExecuted,
        kDuckExecuted
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

    // DSP-01b (tech-req §2.10/§2.8): worker-maintained mirrors of the OSS
    // tempogram's most recent estimate, relaxed store by the worker /
    // relaxed load by the test hook sc_test_get_beat_state. Not part of the
    // public ABI (synccore_testing.h only) — same pattern as
    // aec_mode_mirror above. beat_comb_mirror is 0/1 rather than bool so an
    // atomic<int32_t> can be used uniformly with the other mirrors;
    // beat_period_ms_mirror is 0.0 whenever no estimate has ever been
    // computed (session start) or after a kTrackLost epoch reset, exactly
    // mirroring BeatEstimate{}'s own default.
    std::atomic<int32_t> beat_comb_mirror{0};
    std::atomic<double> beat_period_ms_mirror{0.0};

    // DSP-03a (tech-req §2.12): worker-maintained mirrors of the duck
    // detector's most recent matched-filter result (dip depth D in dB,
    // significance z), relaxed store by the worker / relaxed load by the
    // test hook sc_test_get_duck_metrics. Same pattern as beat_comb_mirror/
    // beat_period_ms_mirror above — not part of the public ABI. Both are
    // 0.0 whenever no analysis has ever completed (session start) or after
    // a kTrackLost epoch reset.
    std::atomic<double> duck_dip_db_mirror{0.0};
    std::atomic<double> duck_z_mirror{0.0};

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
        // MHT-01 (tech-req §2.16): parallel hypothesis bank. Default-
        // constructed exactly like `estimator` above (MhtConfig{} — default
        // mht_enabled=false — and EstimatorConfig{}, the SAME default
        // EstimatorConfig `estimator` gets: sc_create's setters below apply
        // identically to both, see set_output_latency_ms/set_deadband_ms
        // call sites), so every entry point is a true no-op and this pass
        // changes zero on-device behavior while mht_enabled stays false.
        synccore::HypothesisBank mht;
        // Referee-analysis-moment values (tech-req §2.10's own "one shared
        // analysis moment," kSampleLatencyResidual), forwarded to
        // wk.mht.on_fix at the next accepted fix — see that handler for why
        // this is stored unconditionally rather than only when warranted.
        synccore::BeatEstimate last_beat;
        double last_comb_ratio = 0.0;
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
        // CTL-05 (docs/ctl05-investigation.md §6.2): after a local
        // corrective seek, a single fix must not alone regain the
        // self-match guard's full arbitration authority. FT10's cascade was
        // exactly this — the first post-seek fix happened to track the
        // STALE pre-seek room_anchor_offset_ms within kRoomContinuityGateMs
        // by a ~100 ms coincidence, instantly re-confirmed it, and the
        // guard then spent ~25 s rejecting three real, mutually-consistent
        // fixes from the ACTUAL room timeline as self-hearing. Mirrors the
        // anti-poisoning rule that a session's very first fix can't
        // arbitrate (cand_offset_ms's own comment above): while
        // anchor_pending_reconfirm is set, room_anchor_offset_ms/
        // room_anchor_confirmed are FROZEN at their pre-seek values — still
        // fully able to reject, exactly as before the seek, so protection
        // is not weakened — while this SEPARATE post-seek candidate slot
        // requires two agreeing post-seek fixes (same kRoomContinuityGateMs
        // tolerance already trusted for "two fixes agree on a new room
        // timeline" below) before the live anchor is replaced. Cleared by a
        // fresh seek, by successful promotion, by kMaxConsecutiveSelfRejects
        // dropping the anchor outright, or by a track-lost epoch reset.
        bool anchor_pending_reconfirm = false;
        int64_t post_seek_cand_offset_ms = -1;
        uint64_t post_seek_cand_ns = 0;
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
        // DSP-01b (tech-req §2.10): worker-thread-only OSS beat-period
        // tracker, alongside residual_scratch — same non-RT worker-thread
        // home as the referee's own scratch buffer. push() runs at the
        // drain loop's post-AEC tap (no new capture tap); estimate_beat_
        // period() runs only on the kSampleLatencyResidual cadence, the
        // one shared "analysis moment" per tech-req §2.10.
        synccore::OnsetStrengthRing oss_ring{kSupportedRateHz};
        // CTL-01a (tech-req §2.9): worker-local mirror of the last
        // sc_player_state_t's is_paused, fed to CorrectionPolicy::on_tick as
        // playback_live — the estimator holds is_paused privately with no
        // getter, and it stays that way (estimator.h is untouched).
        bool playback_paused = false;
        // DSP-03a (tech-req §2.12, R2): deferred duck-analysis state.
        // kDuckExecuted stamps the echo epoch + achieved depth here and
        // arms pending; tick() runs the matched-filter analysis once the
        // search window's reach has actually drained into capture history,
        // then clears pending. Worker-local, cleared on kTrackLost (epoch
        // rule) alongside every other epoch-scoped worker state.
        bool duck_analysis_pending = false;
        uint64_t duck_echo_ns = 0;
        int32_t duck_achieved_deci_db = 0;
        // Reused across calls to avoid a fresh ~2.3 MB history copy per
        // duck analysis (rate-limited by duck_cooldown_ns anyway, but the
        // codebase's steady-state-zero-allocation convention — see
        // residual_scratch above — is followed here too). Sized lazily to
        // kHistoryFrames on first use.
        std::vector<float> duck_scratch;
        // 20 ms-hop log-envelope, reused across calls the same way
        // (constant hop count per session, since duck_ms doesn't change at
        // runtime — cleared, not reallocated, after the first call grows
        // it).
        std::vector<double> duck_hops;
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
                // DSP-01b (tech-req §2.10): same post-AEC tap, no new tap.
                // Empty blocks (wk.scratch.size() == 0) are harmless — push
                // just accumulates zero new bytes into the frame buffer —
                // so no special-casing here, matching append_history's own
                // treatment immediately above.
                wk.oss_ring.push(wk.scratch.data(), wk.scratch.size(), block_end);
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

    // tech-req §2.17 (CTL-06/W1): dedicated per-tick policy-state diagnostic,
    // dispatched at the exact same call sites as emit_estimate above — "the
    // same worker cadence as SC_EVT_SYNC_ESTIMATE, no new timer." Pure
    // observation of wk.policy's own already-tracked state (settled_,
    // ring_count_ via the new read-only accessors); never influences
    // anything downstream.
    void emit_policy_state() {
        sc_evt_policy_state_t out{};
        out.settled = wk.policy.settled();
        out.in_deadband_streak = wk.policy.in_deadband_streak();
        dispatch(SC_EVT_POLICY_STATE, &out);
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
                // MHT-01 epoch rule (tech-req §2.16): a re-listen is a new
                // epoch for the bank too — no hypothesis, existence, or
                // sidecar state may survive into it, exactly like the
                // estimator/policy resets immediately above (and the OSS
                // ring's own reset just below, which the bank's own
                // seeding inputs — BeatEstimate — depend on).
                wk.mht.reset();
                // DSP-01b epoch rule (tech-req §2.10): a re-listen is a new
                // epoch — the tempogram's OSS ring and stability history
                // must not survive into it, exactly like the estimator/
                // policy resets alongside it. Not spelled out verbatim in
                // §2.10's own text, but implied by the epoch rule §2.7's
                // persistence ring and CorrectionPolicy::reset() already
                // follow.
                wk.oss_ring.reset();
                // The room timeline we were predicting is gone; a stale
                // reference would arbitrate the re-bootstrap fixes.
                wk.room_anchor_offset_ms = -1;
                wk.room_anchor_confirmed = false;
                wk.consecutive_self_rejects = 0;
                wk.cand_offset_ms = -1;
                // CTL-05: a re-listen is a new epoch for the post-seek
                // reconfirmation state too — no pending flag or candidate
                // may survive into it, exactly like room_anchor_* above.
                wk.anchor_pending_reconfirm = false;
                wk.post_seek_cand_offset_ms = -1;
                beat_comb_mirror.store(0, std::memory_order_relaxed);
                beat_period_ms_mirror.store(0.0, std::memory_order_relaxed);
                // DSP-03a epoch rule (tech-req §2.12): a re-listen is a new
                // epoch for the duck detector too — pending analysis from
                // before this reset must never resolve into it, exactly
                // like the OSS ring's own reset just above.
                wk.duck_analysis_pending = false;
                wk.duck_echo_ns = 0;
                wk.duck_achieved_deci_db = 0;
                duck_dip_db_mirror.store(0.0, std::memory_order_relaxed);
                duck_z_mirror.store(0.0, std::memory_order_relaxed);
                dispatch(SC_EVT_TRACK_LOST, nullptr);
                break;
        }
    }

    // DSP-03a (tech-req §2.12): matched-filter dip detector over the
    // post-AEC capture history sc_copy_recent_capture already retains — no
    // new capture tap, same pattern as kSampleLatencyResidual. Reads dip
    // depth D (dB) and its MAD-normalized significance z from a rectangular
    // template slid across the search window, then hands the RESULT (never
    // raw samples) to the policy via on_duck_result — policy.cpp stays
    // DSP-free per §2.12's division of labor. Called from tick() only once
    // wk.duck_analysis_pending's deferred window has actually elapsed.
    void run_duck_analysis() {
        if (wk.duck_scratch.size() != kHistoryFrames)
            wk.duck_scratch.assign(kHistoryFrames, 0.0f);
        uint64_t out_end_ns = 0;
        const int32_t n = sc_copy_recent_capture(
            this, wk.duck_scratch.data(), static_cast<int32_t>(kHistoryFrames),
            &out_end_ns);

        const int32_t duck_ms = wk.policy.duck_ms();
        const uint64_t duck_ns = static_cast<uint64_t>(duck_ms) * 1'000'000ull;
        const uint64_t env_start_ns =
            wk.duck_echo_ns - kDuckBaselineNs - kDuckPreWindowNs;
        const uint64_t env_end_ns = wk.duck_echo_ns + duck_ns + kDuckPostMarginNs;

        // Session too young / copy doesn't reach back far enough to cover
        // the baseline: not enough evidence to say anything. Passing
        // achieved_deci_db = 0 here is deliberate (orchestrator fix): it
        // triggers on_duck_result's explicit no-depth guard, which forces
        // the INCONCLUSIVE path (escalate once, never silently drop).
        // Passing the real achieved depth with D = 0 would instead read as
        // room-dominant (0 <= the scaled 1.5 dB band) and CLEAR sentinel
        // suspicion off no evidence at all — the wrong resolution.
        if (n <= 0 || out_end_ns < env_end_ns) {
            duck_dip_db_mirror.store(0.0, std::memory_order_relaxed);
            duck_z_mirror.store(0.0, std::memory_order_relaxed);
            apply(wk.policy.on_duck_result(0.0, 0.0, /*achieved_deci_db=*/0,
                                           wk.now_ns));
            return;
        }

        // Map a capture-time to an index in the copied buffer: buffer[n-1]
        // corresponds to out_end_ns, each preceding frame exactly one
        // sample period earlier — the copy's own end-timestamp pairing
        // (sc_copy_recent_capture's documented contract).
        const double frame_period_ns =
            1e9 / static_cast<double>(kSupportedRateHz);
        auto index_for = [&](uint64_t t_ns) -> long long {
            const double frames_before_end =
                (static_cast<double>(out_end_ns) - static_cast<double>(t_ns)) /
                frame_period_ns;
            return static_cast<long long>(n - 1) -
                   static_cast<long long>(std::llround(frames_before_end));
        };
        const long long start_idx = index_for(env_start_ns);
        if (start_idx < 0) {
            duck_dip_db_mirror.store(0.0, std::memory_order_relaxed);
            duck_z_mirror.store(0.0, std::memory_order_relaxed);
            // achieved 0 -> forced inconclusive; see the guard above.
            apply(wk.policy.on_duck_result(0.0, 0.0, /*achieved_deci_db=*/0,
                                           wk.now_ns));
            return;
        }

        // 20 ms non-overlapping RMS hops -> 50 Hz log-envelope
        // e(j) = 10*log10(mean(x^2) + eps) over the full combined
        // baseline+search span.
        const long long total_span_ns =
            static_cast<long long>(env_end_ns - env_start_ns);
        const long long n_hops = total_span_ns / kDuckHopNs;

        wk.duck_hops.clear();  // keeps capacity — no realloc once stable
        for (long long h = 0; h < n_hops; ++h) {
            const long long frame0 = start_idx + h * kDuckHopFrames;
            const long long frame1 = frame0 + kDuckHopFrames;
            if (frame0 < 0 || frame1 > n) {
                wk.duck_hops.push_back(-120.0);  // out of bounds: silence
                continue;                        // floor, not a crash
            }
            double sum_sq = 0.0;
            for (long long i = frame0; i < frame1; ++i) {
                const double v =
                    static_cast<double>(wk.duck_scratch[static_cast<size_t>(i)]);
                sum_sq += v * v;
            }
            const double mean_sq = sum_sq / static_cast<double>(kDuckHopFrames);
            wk.duck_hops.push_back(10.0 * std::log10(mean_sq + 1e-12));
        }

        if (static_cast<long long>(wk.duck_hops.size()) <= kDuckBaselineHops) {
            duck_dip_db_mirror.store(0.0, std::memory_order_relaxed);
            duck_z_mirror.store(0.0, std::memory_order_relaxed);
            // achieved 0 -> forced inconclusive; see the guard above.
            apply(wk.policy.on_duck_result(0.0, 0.0, /*achieved_deci_db=*/0,
                                           wk.now_ns));
            return;
        }

        // Baseline: the fixed 3 s of envelope preceding the search window
        // (tech-req §2.12) -- median for the matched filter's D, MAD (about
        // that same median) for the z normalization.
        std::array<double, static_cast<size_t>(kDuckBaselineHops)> baseline_sorted;
        for (long long i = 0; i < kDuckBaselineHops; ++i)
            baseline_sorted[static_cast<size_t>(i)] =
                wk.duck_hops[static_cast<size_t>(i)];
        std::sort(baseline_sorted.begin(), baseline_sorted.end());
        const double baseline_median =
            baseline_sorted[static_cast<size_t>(kDuckBaselineHops) / 2];

        std::array<double, static_cast<size_t>(kDuckBaselineHops)> abs_dev;
        for (long long i = 0; i < kDuckBaselineHops; ++i)
            abs_dev[static_cast<size_t>(i)] =
                std::abs(wk.duck_hops[static_cast<size_t>(i)] - baseline_median);
        std::sort(abs_dev.begin(), abs_dev.end());
        const double mad = abs_dev[static_cast<size_t>(kDuckBaselineHops) / 2];

        // Matched filter: rectangular dip template of width
        // max(1, duck_ms/20 ms) hops slid across the search-window hops
        // (everything after the baseline). D = median(baseline) -
        // mean(template) at each position; keep the max.
        const long long template_hops =
            std::max<long long>(1, static_cast<long long>(duck_ms) / 20);
        const long long search_hops_available =
            static_cast<long long>(wk.duck_hops.size()) - kDuckBaselineHops;

        double max_d = 0.0;
        bool any = false;
        for (long long pos = 0; pos + template_hops <= search_hops_available;
            ++pos) {
            double sum = 0.0;
            for (long long k = 0; k < template_hops; ++k)
                sum += wk.duck_hops[static_cast<size_t>(kDuckBaselineHops + pos + k)];
            const double template_mean =
                sum / static_cast<double>(template_hops);
            const double d = baseline_median - template_mean;
            if (!any || d > max_d) {
                max_d = d;
                any = true;
            }
        }
        // Robustness normalization (tech-req §2.12): z = D / (1.4826*MAD),
        // guarded against a perfectly flat baseline (mad == 0).
        const double z = mad > 0.0 ? max_d / (1.4826 * mad) : 0.0;

        duck_dip_db_mirror.store(max_d, std::memory_order_relaxed);
        duck_z_mirror.store(z, std::memory_order_relaxed);
        apply(wk.policy.on_duck_result(max_d, z, wk.duck_achieved_deci_db,
                                       wk.now_ns));
    }

    // Time-driven duties: interpolated estimate emissions (≤ 15 Hz) and the
    // recognition-request scheduler. Runs on capture-time progress.
    void tick() {
        if (wk.now_ns == 0) return;
        // MHT-01 (tech-req §2.16) DECISION: deliberately NOT substituting
        // the dominant hypothesis here, unlike kRecognitionFix's decide_ns
        // block. on_tick (below) never emits a seek — it is void, and its
        // only jobs are tracking converged_seen_/confidence for the §2.9
        // referee sentinel and Wittenmark turn-off dwell, neither of which
        // §2.16 says anything about. §2.16's own actuate-on-dominant rule
        // governs SEEK actuation only ("the policy actuates... only off the
        // single dominant hypothesis"); coupling the bank into the
        // unrelated probe-trigger/emitted-telemetry cadence here would be
        // scope creep this ticket doesn't authorize. The periodic
        // SC_EVT_SYNC_ESTIMATE emission just below is the same plain
        // estimator estimate for the same reason.
        const synccore::Estimate est = wk.estimator.estimate_at(wk.now_ns);
        if (est.valid &&
            wk.now_ns - wk.last_emit_ns >= kEstimateEmitPeriodNs) {
            wk.last_emit_ns = wk.now_ns;
            emit_estimate(est);
            emit_policy_state();  // tech-req §2.17: same cadence, no new timer
        }
        if (wk.policy.fix_request_due(wk.now_ns))
            dispatch(SC_EVT_REQUEST_FIX, nullptr);

        // CTL-01a (tech-req §2.9): the Wittenmark turn-off trigger and both
        // probe-request expiries are time-driven, so on_tick runs every
        // worker iteration off capture-time progress — independent of
        // whether any fix is being accepted at all.
        wk.policy.on_tick(est, !wk.playback_paused, wk.now_ns);
        if (wk.policy.probe_request_due(wk.now_ns)) {
            sc_evt_active_probe_t probe{};
            probe.pause_ms = wk.policy.probe_pause_ms();
            dispatch(SC_EVT_ACTIVE_PROBE, &probe);
        }
        // DSP-03a (tech-req §2.12): duck-tier request, dispatched the exact
        // same one-shot way as the pause probe just above.
        if (wk.policy.duck_request_due(wk.now_ns)) {
            sc_evt_active_duck_t duck{};
            duck.duck_ms = wk.policy.duck_ms();
            dispatch(SC_EVT_ACTIVE_DUCK, &duck);
        }
        // DSP-03a (tech-req §2.12, R2): deferred dip-detector analysis —
        // audio PAST the echo hasn't been captured yet when kDuckExecuted
        // processes, so the matched-filter analysis runs here instead, once
        // the search window's own reach (duck_ms + 750 ms) plus a small
        // drain margin has actually elapsed in capture time.
        if (wk.duck_analysis_pending) {
            const uint64_t ready_ns =
                wk.duck_echo_ns +
                static_cast<uint64_t>(wk.policy.duck_ms()) * 1'000'000ull +
                kDuckPostMarginNs + kDuckAnalysisMarginNs;
            if (wk.now_ns >= ready_ns) {
                run_duck_analysis();
                wk.duck_analysis_pending = false;
            }
        }

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

                // Snapshot the self-hearing verdict BEFORE the CTL-05
                // post-seek corroboration bookkeeping below can mutate
                // room_anchor_confirmed — a fix that itself corroborates
                // (promotes) the post-seek anchor must be judged against the
                // anchor as it stood at entry, not the one it just created;
                // promotion never retroactively un-rejects the fix that
                // triggered it (architecture-spec §7.3: a rejected fix must
                // not gain adoption power it didn't have).
                const bool self_hearing_candidate =
                    anchor_usable && wk.room_anchor_confirmed && !tracks_room &&
                    wk.estimator.has_player_state() &&
                    std::abs(off - wk.estimator.local_audible_ms(t)) <=
                        kSelfMatchWindowMs;

                // tech-req §2.17 (CTL-06/W1): snapshot the diagnostic values
                // at the SAME point self_hearing_candidate is judged — "as
                // it stood at entry," exactly like the comment above
                // self_hearing_candidate's own computation. The CTL-05
                // post-seek corroboration block just below (and, for an
                // accepted fix, the room-timeline-maintenance block further
                // down) can go on to replace the live anchor; SC_EVT_FIX_DIAG
                // must report what arbitration actually SAW this fix
                // against, not what this fix's own consequences left
                // behind. local_audible_ms(t) is safe to call unconditionally
                // (no side effects) even when has_player_state() is false —
                // it is exactly the value self_hearing_candidate's own
                // comparison above already reads, computed once here for
                // reuse rather than recomputed.
                const int64_t diag_anchor_offset_ms = wk.room_anchor_offset_ms;
                const int64_t diag_anchor_age_ms =
                    diag_anchor_offset_ms < 0
                        ? -1
                        : static_cast<int64_t>(
                              (t > wk.room_anchor_ns ? (t - wk.room_anchor_ns)
                                                      : 0ull) /
                              1'000'000ull);
                const double diag_local_audible_ms =
                    wk.estimator.local_audible_ms(t);
                auto emit_fix_diag = [&](sc_fix_diag_verdict_t verdict) {
                    sc_evt_fix_diag_t diag{};
                    diag.match_offset_ms = cmd.fix.match_offset_ms;
                    diag.verdict = verdict;
                    diag.tracks_room = tracks_room;
                    diag.tracks_cand = tracks_cand;
                    diag.room_anchor_offset_ms = diag_anchor_offset_ms;
                    diag.room_anchor_age_ms = diag_anchor_age_ms;
                    diag.off = off;
                    diag.predicted_room = predicted_room;
                    diag.local_audible_ms = diag_local_audible_ms;
                    dispatch(SC_EVT_FIX_DIAG, &diag);
                };

                // CTL-05 (docs/ctl05-investigation.md §6.1): while a
                // post-seek anchor awaits reconfirmation, this SEPARATE
                // candidate slot tracks whether consecutive post-seek fixes
                // agree with EACH OTHER — run for every fix, accepted or
                // (about to be) rejected below, which is what lets a real,
                // coherent second timeline promote itself once two of its
                // fixes agree, rather than only after
                // kMaxConsecutiveSelfRejects fixes have been discarded
                // outright. The verdict on THIS fix was already decided
                // above; this only affects fixes AFTER it.
                const bool was_pending = wk.anchor_pending_reconfirm;
                if (was_pending) {
                    const bool corroborates_post_seek =
                        wk.post_seek_cand_offset_ms >= 0 && t > wk.post_seek_cand_ns &&
                        std::abs(off - (static_cast<double>(wk.post_seek_cand_offset_ms) +
                                        static_cast<double>(t - wk.post_seek_cand_ns) /
                                            1e6)) <= kRoomContinuityGateMs;
                    if (corroborates_post_seek) {
                        // Two post-seek fixes agree with each other on a
                        // timeline that may or may not be the stale
                        // pre-seek anchor: promote it to the live,
                        // arbitration-capable anchor.
                        wk.room_anchor_offset_ms = cmd.fix.match_offset_ms;
                        wk.room_anchor_ns = t;
                        wk.room_anchor_confirmed = true;
                        wk.anchor_pending_reconfirm = false;
                        wk.post_seek_cand_offset_ms = -1;
                        wk.cand_offset_ms = -1;
                    } else {
                        wk.post_seek_cand_offset_ms = cmd.fix.match_offset_ms;
                        wk.post_seek_cand_ns = t;
                    }
                }

                if (self_hearing_candidate) {
                    if (++wk.consecutive_self_rejects >=
                        kMaxConsecutiveSelfRejects) {
                        // We are almost certainly judging with a bad
                        // reference. Forget it; the next fix re-seeds.
                        wk.room_anchor_offset_ms = -1;
                        wk.room_anchor_confirmed = false;
                        // CTL-05: the anchor this pending reconfirmation was
                        // guarding is gone — nothing left to (re)confirm.
                        wk.anchor_pending_reconfirm = false;
                        wk.post_seek_cand_offset_ms = -1;
                        wk.consecutive_self_rejects = 0;
                    }
                    sc_evt_fix_rejected_t rej{SC_REJECT_SELF_HEARING};
                    dispatch(SC_EVT_FIX_REJECTED, &rej);
                    emit_fix_diag(SC_FIX_DIAG_SELF_HEARING);
                    return;
                }
                if (!wk.estimator.on_fix(cmd.fix.match_offset_ms, t,
                                         cmd.fix.frequency_skew,
                                         cmd.fix.confidence)) {
                    sc_evt_fix_rejected_t rej{SC_REJECT_LOW_CONFIDENCE};
                    dispatch(SC_EVT_FIX_REJECTED, &rej);
                    emit_fix_diag(SC_FIX_DIAG_LOW_CONFIDENCE);
                    return;
                }
                wk.policy.on_fix_accepted(t);
                emit_fix_diag(SC_FIX_DIAG_ACCEPTED);
                // MHT-01 (tech-req §2.16 hard limit, restated verbatim: "the
                // bank never touches self-match"). This call sits STRICTLY
                // downstream of every §7.3 self-match-guard early return
                // above (settling/self-hearing/low-confidence all already
                // returned) and after the fix has already been accepted
                // into the estimator/policy — that placement IS the
                // invariant; do not move this call earlier, and do not grow
                // a second self-match path around it (hypothesis_bank.h's
                // own header comment states the same limit). last_beat/
                // last_comb_ratio are the most recent referee-window values
                // (kSampleLatencyResidual), the "one shared analysis
                // moment" §2.10 already established for the tempogram.
                wk.mht.on_fix(cmd.fix.match_offset_ms, t, cmd.fix.frequency_skew,
                              cmd.fix.confidence, wk.last_beat,
                              wk.last_comb_ratio);
                // Maintain the room timeline. Field Test 5 (song 2) showed
                // why this must NOT simply re-seed on every accepted fix:
                // with the recognizer alternating between the room and our
                // own audio, every other fix broke continuity, so the anchor
                // was never confirmed, so the guard never rejected anything —
                // and the engine settled 1.7 s ahead of the room while
                // reporting −3 ms. The established timeline has to survive an
                // isolated bad offset.
                //
                // CTL-05: skipped while `was_pending` — the post-seek
                // corroboration block above already decided this fix's
                // effect on the anchor (either promoted it, or left it
                // frozen pending a second agreeing fix). Letting this ALSO
                // run would let a fix that merely tracks the FROZEN
                // pre-seek anchor silently re-confirm on its own — exactly
                // the FT10 bug (fix B tracked the stale anchor by a ~100 ms
                // coincidence and instantly regained full authority).
                if (!was_pending) {
                    if (tracks_room) {
                        wk.room_anchor_offset_ms = cmd.fix.match_offset_ms;
                        wk.room_anchor_ns = t;
                        wk.room_anchor_confirmed = true;
                        wk.cand_offset_ms = -1;
                    } else if (tracks_cand) {
                        // Two fixes now agree on a DIFFERENT continuous
                        // timeline: the room really did move (new song,
                        // someone skipped).
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
                // MHT-01 (tech-req §2.16): actuate off the single dominant
                // hypothesis once its existence clears
                // mht_existence_actuate_threshold — never off a blend of
                // several (§2.16's explicit rejection of PDA's soft-blend,
                // Eq. 3.6). While the bank is active (>=1 live hypothesis)
                // but has no dominant clearing the threshold, the policy is
                // HELD rather than left to actuate off the plain
                // (pre-disambiguation) estimator estimate — that would be
                // exactly the FT9 Billie Jean failure this bank exists to
                // prevent (a seek off one unresolved, possibly-wrong tooth
                // of the comb). While the bank is empty or mht_enabled is
                // false (the shipped default), active() is false and
                // dom.valid is false unconditionally (HypothesisBank's own
                // no-op-while-disabled contract) — set_mht_hold(false) and
                // est falls through to wk.estimator.estimate_at, byte-
                // identical to pre-MHT-01 behavior.
                const auto dom = wk.mht.dominant_at(decide_ns);
                wk.policy.set_mht_hold(wk.mht.active() && !dom.valid);
                const synccore::Estimate est =
                    dom.valid ? dom.estimate : wk.estimator.estimate_at(decide_ns);
                wk.last_emit_ns = decide_ns;
                emit_estimate(est);
                emit_policy_state();  // tech-req §2.17: same cadence
                // projected_local_ms stays the SHARED estimator's own
                // projection unconditionally (§2.16 doesn't touch the
                // player-state projection, only which offset/drift
                // ESTIMATE feeds the seek-target formula that consumes it).
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
                // MHT-01: forwarded identically to every live/future
                // hypothesis — see hypothesis_bank.h's on_player_state.
                wk.mht.on_player_state(cmd.player.position_ms,
                                       cmd.player.is_paused,
                                       cmd.player.received_mono_ns);
                // CTL-01a: the estimator holds is_paused privately with no
                // getter (estimator.h stays untouched) — mirror it here so
                // tick() can feed playback_live to CorrectionPolicy::on_tick.
                wk.playback_paused = cmd.player.is_paused;
                break;
            case Command::Kind::kSeekIssued:
                wk.now_ns = std::max(wk.now_ns, cmd.mono_ns);
                wk.estimator.on_local_seek(cmd.value_ms, cmd.mono_ns,
                                           wk.policy.command_latency_ms());
                // MHT-01: forwarded identically — each hypothesis's own
                // SyncEstimator instance must absorb the same seek-
                // execution-uncertainty widening the shared estimator gets,
                // or a post-seek fix would read as a spurious innovation to
                // every hypothesis.
                wk.mht.on_local_seek(cmd.value_ms, cmd.mono_ns,
                                     wk.policy.command_latency_ms());
                wk.policy.on_seek_issued(cmd.mono_ns);
                // A seek re-commands our own playback position — keep the
                // self-hearing guard's reference fresh.
                wk.last_commanded_position_ms = cmd.value_ms;
                // CTL-05: arm post-seek anchor reconfirmation (see
                // anchor_pending_reconfirm's declaration) — a fresh seek
                // always restarts the corroboration requirement, even if a
                // prior one was still outstanding.
                wk.anchor_pending_reconfirm = true;
                wk.post_seek_cand_offset_ms = -1;
                break;
            case Command::Kind::kLocalPlayback:
                wk.last_commanded_position_ms = cmd.value_ms;
                break;
            case Command::Kind::kSetNudge:
                wk.estimator.set_nudge_ms(static_cast<double>(cmd.value_ms));
                wk.mht.set_nudge_ms(static_cast<double>(cmd.value_ms));  // MHT-01
                break;
            case Command::Kind::kSetOutputLatency:
                wk.estimator.set_output_latency_ms(
                    static_cast<double>(cmd.value_ms));
                wk.mht.set_output_latency_ms(  // MHT-01
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
                // Declared here (rather than inside the `if (n > 0)` block
                // below, where the pre-DSP-01b code computed it) so the
                // §2.8 cross-check after dispatch/on_referee_window can
                // still read lag.second_lag_ms from this same window's
                // result; WindowLag{}'s default (second_lag_ms = 0, its own
                // "no competitor" sentinel) is exactly right when n <= 0.
                synccore::WindowLag lag;
                if (n > 0) {
                    // Single-buffer autocorrelation, no reference signal
                    // (tech-req §2.6): the mic hears two copies of the same
                    // song (ours and the room's); the peak between them is
                    // the acoustic error a listener perceives.
                    lag = synccore::analyze_window(
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

                // CTL-01a (tech-req §2.9): the referee sentinel is a pure
                // additional consumer of this same per-window result — feed
                // it the exact lag value that just went out in the payload
                // (out.residual_ms, the already-rounded WindowLag lag), not
                // the raw unrounded lag.lag_ms.
                wk.policy.on_referee_window(static_cast<double>(out.residual_ms),
                                            out.valid, wk.now_ns);

                // DSP-01b (tech-req §2.10): the kSampleLatencyResidual
                // cadence is the one shared "analysis moment" the tempogram
                // is polled on — estimate_beat_period is called nowhere
                // else. The frozen-ring guard inside OnsetStrengthRing
                // (docs/dsp01a-review.md's orchestrator addition) already
                // makes duplicate polls harmless, so no extra caller-side
                // "did capture actually progress" bookkeeping is needed
                // here.
                const synccore::BeatEstimate beat =
                    wk.oss_ring.estimate_beat_period(wk.now_ns);
                // MHT-01 (tech-req §2.16): stash this analysis moment's
                // referee values, unconditionally — even when n <= 0.
                // WindowLag{}'s default comb_ratio=0 sentinel ("no
                // competitor") can never pass HypothesisBank::warranted's
                // mht_warrant_comb_ratio_max gate, so storing it plainly
                // here is exactly right for that case too; no special-
                // casing needed, matching append_history's/oss_ring.push's
                // own treatment of the empty/n<=0 case elsewhere in this
                // file. Consumed by kRecognitionFix's wk.mht.on_fix call at
                // the next accepted fix.
                wk.last_beat = beat;
                wk.last_comb_ratio = lag.comb_ratio;
                // §2.8 cross-check: corroborates (or not) the SAME window's
                // second_lag_ms — read-only diagnostic, never gates
                // anything above. When n <= 0, `lag` is still WindowLag{}'s
                // default (second_lag_ms == 0), which
                // beat_comb_corroborated already treats as "no competitor."
                const bool beat_comb =
                    synccore::beat_comb_corroborated(lag.second_lag_ms, beat);
                beat_comb_mirror.store(beat_comb ? 1 : 0,
                                       std::memory_order_relaxed);
                beat_period_ms_mirror.store(beat.period_ms,
                                            std::memory_order_relaxed);
                break;
            }
            case Command::Kind::kProbeExecuted: {
                // CTL-01a echo (mirrors kSeekIssued): stamps the probe
                // epoch and snapshots the pre-probe error at the moment the
                // shell reports the pause/resume actually completed, not at
                // SC_EVT_ACTIVE_PROBE emission (App Remote command latency
                // is 100-500 ms and unknowable at emission time). Unlike
                // sc_notify_seek_issued, this call carries no timestamp of
                // its own — session time (wk.now_ns), advanced only by
                // capture-timestamp progress, IS the epoch stamp.
                const synccore::Estimate est = wk.estimator.estimate_at(wk.now_ns);
                wk.policy.on_probe_executed(est.error_ms, wk.now_ns);
                break;
            }
            case Command::Kind::kDuckExecuted: {
                // DSP-03a (tech-req §2.12, R2) echo: unlike kProbeExecuted,
                // the policy has no per-echo state to snapshot here (the
                // duck's verdict is computed entirely from the worker's own
                // deferred capture-energy analysis, not from future
                // estimates) — so this handler is purely worker-local
                // bookkeeping. Stamp the echo epoch as wk.now_ns (session
                // time, never a wall clock) and the achieved depth the
                // shell actually commanded, then arm the deferred analysis
                // tick() will run once the search window has elapsed.
                wk.duck_echo_ns = wk.now_ns;
                wk.duck_achieved_deci_db = static_cast<int32_t>(cmd.value_ms);
                wk.duck_analysis_pending = true;
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
    if (cfg->output_latency_prior_ms > 0) {
        s->wk.estimator.set_output_latency_ms(
            static_cast<double>(cfg->output_latency_prior_ms));
        // MHT-01: the bank's own sidecar estimators get the SAME prior.
        s->wk.mht.set_output_latency_ms(
            static_cast<double>(cfg->output_latency_prior_ms));
    }
    s->wk.policy.set_command_latency_ms(
        static_cast<double>(s->cfg.command_latency_prior_ms));
    s->command_latency_mirror_ms.store(s->cfg.command_latency_prior_ms,
                                       std::memory_order_relaxed);
    if (cfg->deadband_ms > 0) {
        s->wk.estimator.set_deadband_ms(static_cast<double>(cfg->deadband_ms));
        s->wk.policy.set_deadband_ms(static_cast<double>(cfg->deadband_ms));
        // MHT-01: same shared deadband the estimator/policy both get.
        s->wk.mht.set_deadband_ms(static_cast<double>(cfg->deadband_ms));
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

sc_status_t sc_notify_probe_executed(sc_session_t* s) {
    if (!s) return SC_ERR_INVALID_ARG;
    Command cmd;
    cmd.kind = Command::Kind::kProbeExecuted;
    s->enqueue(std::move(cmd));
    return SC_OK;
}

sc_status_t sc_notify_duck_executed(sc_session_t* s, int32_t achieved_deci_db) {
    if (!s) return SC_ERR_INVALID_ARG;
    Command cmd;
    cmd.kind = Command::Kind::kDuckExecuted;
    cmd.value_ms = achieved_deci_db;
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

sc_status_t sc_reset_capture_history(sc_session_t* s) {
    if (!s) return SC_ERR_INVALID_ARG;
    {
        std::lock_guard<std::mutex> lock(s->history_mtx);
        std::fill(s->history.begin(), s->history.end(), 0.0f);
        s->history_write = 0;
        s->history_wrapped = false;
        s->history_end_ns.store(0, std::memory_order_relaxed);
    }
    // The level meter is a property of the CURRENT capture stream; a new
    // stream must not inherit the old one's envelope.
    s->input_level.store(0.0f, std::memory_order_relaxed);
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

// DSP-01b: last OSS tempogram state the worker computed, from the most
// recent kSampleLatencyResidual analysis moment — lets a test confirm the
// drain-loop wiring/cadence and the §2.8 cross-check without any ABI
// surface (mirrors sc_test_get_aec_mode's pattern above).
void sc_test_get_beat_state(sc_session_t* s, int32_t* out_beat_comb,
                            double* out_beat_period_ms) {
    if (!s) return;
    if (out_beat_comb)
        *out_beat_comb = s->beat_comb_mirror.load(std::memory_order_relaxed);
    if (out_beat_period_ms)
        *out_beat_period_ms =
            s->beat_period_ms_mirror.load(std::memory_order_relaxed);
}

// DSP-03a: last duck-detector result the worker computed (dip depth D in
// dB, significance z) -- lets a test confirm the deferred-analysis wiring
// and matched-filter math without any public ABI surface (mirrors
// sc_test_get_beat_state's pattern above).
void sc_test_get_duck_metrics(sc_session_t* s, double* out_dip_db,
                              double* out_z) {
    if (!s) return;
    if (out_dip_db)
        *out_dip_db = s->duck_dip_db_mirror.load(std::memory_order_relaxed);
    if (out_z) *out_z = s->duck_z_mirror.load(std::memory_order_relaxed);
}

}  // extern "C"
