// synccore_jni.cpp — NAT-04: JVM ↔ SyncCore C ABI bridge.
//
// Design notes (see docs/android-implementation-review.md):
//  - One BridgeHandle per Kotlin SyncCore instance: owns the sc_session plus
//    the JNI plumbing (global object ref + cached method ID).
//  - Events arrive on SyncCore's worker thread. That thread is attached to
//    the JVM lazily with AttachCurrentThreadAsDaemon (daemon: it must never
//    block JVM shutdown, and repeated calls are cheap no-ops once attached).
//  - Destruction order is the memory-safety contract: sc_destroy joins the
//    worker (guaranteeing no further callbacks) BEFORE the global ref is
//    dropped, so the callback can never touch a dead reference. NAT-02
//    extends this: BridgeHandle::capture->stop() runs BEFORE sc_destroy in
//    nativeDestroy (see the comment there) — capture must never push into a
//    session that sc_destroy has begun tearing down.
//  - nativePushCapture uses GetPrimitiveArrayCritical: sc_push_capture only
//    memcpys into the lock-free ring, so the critical window is tiny. This
//    entry point serves Kotlin-side tests and bring-up only; the production
//    audio path (NAT-02, audio_capture.h/.cpp) pushes straight from the
//    Oboe C++ callback and skips JNI entirely.

#include <jni.h>

#include <atomic>
#include <cstring>
#include <memory>

#include "audio_capture.h"
#include "synccore/synccore.h"

namespace {

JavaVM* g_vm = nullptr;

struct BridgeHandle {
    sc_session_t* session = nullptr;
    jobject target = nullptr;    // global ref to the Kotlin SyncCore
    jmethodID on_event = nullptr;
    // NAT-02: production audio capture, owned here so its lifetime tracks
    // the session's. Created (but not started) alongside the session in
    // nativeCreate; started/stopped explicitly via nativeStartCapture /
    // nativeStopCapture.
    std::unique_ptr<synccore_android::OboeCapture> capture;
    // CTL-06/W1 (technical-requirements.md §2.17): SC_EVT_POLICY_STATE and
    // SC_EVT_FIX_DIAG carry more fields (2 and 9 respectively) than the
    // generic packed onNativeEvent(type,d0,d1,d2,i0,i1,l0) signature has
    // slots for (6), so they get their own dedicated callback methods/IDs
    // instead of trying to shoehorn them into that packing scheme. Every
    // existing event keeps using on_event unchanged.
    jmethodID on_policy_state = nullptr;
    jmethodID on_fix_diag = nullptr;
};

JNIEnv* attached_env() {
    JNIEnv* env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) ==
        JNI_OK)
        return env;
    if (g_vm->AttachCurrentThreadAsDaemon(&env, nullptr) == JNI_OK) return env;
    return nullptr;
}

// Event payloads flatten into one primitive signature:
//   onNativeEvent(type, d0, d1, d2, i0, i1, l0)
// SYNC_ESTIMATE:      d0=error_ms d1=drift_ppm d2=confidence i0=converged l0=last_fix_ns
// CORRECTION:         l0=seek_to_ms
// FIX_REJECTED:       i0=reason
// CALIBRATION_RESULT: i0=measured_latency_ms i1=valid
// LATENCY_RESIDUAL:   i0=residual_ms d0=peak_ratio i1=valid
// ACTIVE_PROBE:       i0=pause_ms (CTL-01b)
// ACTIVE_DUCK:        i0=duck_ms (DSP-03b)
// REQUEST_FIX / TRACK_LOST: no payload
// POLICY_STATE / FIX_DIAG (CTL-06/W1): NOT packed into this signature — too
// many fields (2 and 9). Dispatched via their own dedicated onPolicyStateEvent
// / onFixDiagEvent callback methods instead; see event_trampoline below.
void event_trampoline(sc_event_type_t type, const void* payload, void* user) {
    auto* h = static_cast<BridgeHandle*>(user);
    JNIEnv* env = attached_env();
    if (!env) return;

    // CTL-06/W1 (technical-requirements.md §2.17): dedicated dispatch, ahead
    // of the generic packed switch below — see BridgeHandle's on_policy_state
    // / on_fix_diag comment for why these two don't fit the (d0,d1,d2,i0,i1,
    // l0) packing scheme every other event below uses.
    if (type == SC_EVT_POLICY_STATE) {
        auto* e = static_cast<const sc_evt_policy_state_t*>(payload);
        env->CallVoidMethod(h->target, h->on_policy_state,
                            e->settled ? JNI_TRUE : JNI_FALSE,
                            static_cast<jint>(e->in_deadband_streak));
        if (env->ExceptionCheck()) env->ExceptionClear();
        return;
    }
    if (type == SC_EVT_FIX_DIAG) {
        auto* e = static_cast<const sc_evt_fix_diag_t*>(payload);
        env->CallVoidMethod(h->target, h->on_fix_diag,
                            static_cast<jlong>(e->match_offset_ms),
                            static_cast<jint>(e->verdict),
                            e->tracks_room ? JNI_TRUE : JNI_FALSE,
                            e->tracks_cand ? JNI_TRUE : JNI_FALSE,
                            static_cast<jlong>(e->room_anchor_offset_ms),
                            static_cast<jlong>(e->room_anchor_age_ms),
                            static_cast<jdouble>(e->off),
                            static_cast<jdouble>(e->predicted_room),
                            static_cast<jdouble>(e->local_audible_ms));
        if (env->ExceptionCheck()) env->ExceptionClear();
        return;
    }

    jdouble d0 = 0, d1 = 0, d2 = 0;
    jint i0 = 0, i1 = 0;
    jlong l0 = 0;
    switch (type) {
        case SC_EVT_SYNC_ESTIMATE: {
            auto* e = static_cast<const sc_evt_sync_estimate_t*>(payload);
            d0 = e->error_ms;
            d1 = e->drift_ppm;
            d2 = e->confidence;
            i0 = e->converged ? 1 : 0;
            l0 = static_cast<jlong>(e->last_fix_mono_ns);
            break;
        }
        case SC_EVT_CORRECTION:
            l0 = static_cast<const sc_evt_correction_t*>(payload)->seek_to_ms;
            break;
        case SC_EVT_FIX_REJECTED:
            i0 = static_cast<const sc_evt_fix_rejected_t*>(payload)->reason;
            break;
        case SC_EVT_CALIBRATION_RESULT: {
            auto* c = static_cast<const sc_evt_calibration_result_t*>(payload);
            i0 = c->measured_latency_ms;
            i1 = c->valid ? 1 : 0;
            break;
        }
        case SC_EVT_LATENCY_RESIDUAL: {
            auto* r = static_cast<const sc_evt_latency_residual_t*>(payload);
            i0 = r->residual_ms;
            d0 = r->peak_ratio;
            i1 = r->valid ? 1 : 0;
            break;
        }
        case SC_EVT_ACTIVE_PROBE:
            i0 = static_cast<const sc_evt_active_probe_t*>(payload)->pause_ms;
            break;
        // DSP-03b: mirrors the SC_EVT_ACTIVE_PROBE case above exactly — same
        // int payload slot, same packing shape (technical-requirements.md
        // §2.12).
        case SC_EVT_ACTIVE_DUCK:
            i0 = static_cast<const sc_evt_active_duck_t*>(payload)->duck_ms;
            break;
        case SC_EVT_REQUEST_FIX:
        case SC_EVT_TRACK_LOST:
            break;
        // CTL-06/W1: both handled and returned above via their own dedicated
        // methods; listed here only so -Wswitch sees an exhaustive switch.
        case SC_EVT_POLICY_STATE:
        case SC_EVT_FIX_DIAG:
            break;
    }
    env->CallVoidMethod(h->target, h->on_event, static_cast<jint>(type), d0,
                        d1, d2, i0, i1, l0);
    if (env->ExceptionCheck()) env->ExceptionClear();  // never kill the worker
}

BridgeHandle* handle_of(jlong h) { return reinterpret_cast<BridgeHandle*>(h); }

}  // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_com_jointheparty_app_core_SyncCore_nativeCreate(
    JNIEnv* env, jobject thiz, jint sample_rate, jint channels, jint route,
    jint output_latency_prior_ms, jint command_latency_prior_ms,
    jint deadband_ms) {
    sc_config_t cfg{};
    cfg.sample_rate_hz = sample_rate;
    cfg.channels = channels;
    cfg.initial_route = static_cast<sc_route_t>(route);
    cfg.output_latency_prior_ms = output_latency_prior_ms;
    cfg.command_latency_prior_ms = command_latency_prior_ms;
    cfg.deadband_ms = deadband_ms;

    sc_session_t* session = nullptr;
    if (sc_create(&cfg, &session) != SC_OK) return 0;

    auto* h = new BridgeHandle();
    h->session = session;
    h->target = env->NewGlobalRef(thiz);
    jclass cls = env->GetObjectClass(thiz);
    h->on_event = env->GetMethodID(cls, "onNativeEvent", "(IDDDIIJ)V");
    // CTL-06/W1 (technical-requirements.md §2.17): dedicated method IDs for
    // the two new diagnostic events (see BridgeHandle's field comment).
    h->on_policy_state = env->GetMethodID(cls, "onPolicyStateEvent", "(ZI)V");
    h->on_fix_diag =
        env->GetMethodID(cls, "onFixDiagEvent", "(JIZZJJDDD)V");
    env->DeleteLocalRef(cls);
    if (!h->target || !h->on_event || !h->on_policy_state || !h->on_fix_diag) {
        sc_destroy(session);
        if (h->target) env->DeleteGlobalRef(h->target);
        delete h;
        return 0;
    }
    sc_set_event_callback(session, event_trampoline, h);
    h->capture = std::make_unique<synccore_android::OboeCapture>(session);
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL
Java_com_jointheparty_app_core_SyncCore_nativeDestroy(JNIEnv* env, jobject,
                                                      jlong handle) {
    auto* h = handle_of(handle);
    if (!h) return;
    // NAT-02 destruction-order contract: stop capture BEFORE sc_destroy.
    // capture->stop() blocks until the Oboe stream is closed and any
    // in-flight restart thread is joined, so once it returns no
    // sc_push_capture call can be in flight or start afterwards — exactly
    // what synccore.h requires ("no sc_push_capture call is in flight or
    // will be made after sc_destroy begins"). Only then do we run the
    // existing contract: sc_destroy joins the worker (no further callbacks)
    // BEFORE the global ref is dropped.
    if (h->capture) h->capture->stop();
    h->capture.reset();
    sc_destroy(h->session);  // joins the worker: no callbacks after this
    env->DeleteGlobalRef(h->target);
    delete h;
}

JNIEXPORT jboolean JNICALL
Java_com_jointheparty_app_core_SyncCore_nativeStartCapture(JNIEnv*, jobject,
                                                            jlong handle) {
    auto* h = handle_of(handle);
    if (!h || !h->capture) return JNI_FALSE;
    // FIELD TEST 8: the capture history ring survives a stop/start, so a new
    // session's first recognition window contained the PREVIOUS session's
    // audio tail — a fresh join matched and played the prior song. A new
    // stream is a new epoch: clear before frames flow.
    sc_reset_capture_history(h->session);
    return h->capture->start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_jointheparty_app_core_SyncCore_nativeStopCapture(JNIEnv*, jobject,
                                                           jlong handle) {
    auto* h = handle_of(handle);
    if (!h || !h->capture) return;
    h->capture->stop();
}

JNIEXPORT void JNICALL
Java_com_jointheparty_app_core_SyncCore_nativePushCapture(
    JNIEnv* env, jobject, jlong handle, jfloatArray samples, jint frames,
    jlong capture_mono_ns) {
    auto* h = handle_of(handle);
    if (!h || !samples) return;
    void* data = env->GetPrimitiveArrayCritical(samples, nullptr);
    if (!data) return;
    sc_push_capture(h->session, static_cast<const float*>(data), frames,
                    static_cast<uint64_t>(capture_mono_ns));
    env->ReleasePrimitiveArrayCritical(samples, data, JNI_ABORT);
}

JNIEXPORT jint JNICALL
Java_com_jointheparty_app_core_SyncCore_nativeSubmitRecognitionFix(
    JNIEnv*, jobject, jlong handle, jint source, jlong match_offset_ms,
    jlong capture_mono_ns, jdouble frequency_skew, jfloat confidence) {
    auto* h = handle_of(handle);
    if (!h) return SC_ERR_INVALID_ARG;
    sc_recognition_fix_t fix{};
    fix.source = static_cast<sc_fix_source_t>(source);
    fix.match_offset_ms = match_offset_ms;
    fix.capture_mono_ns = static_cast<uint64_t>(capture_mono_ns);
    fix.frequency_skew = frequency_skew;
    fix.confidence = confidence;
    return sc_submit_recognition_fix(h->session, &fix);
}

JNIEXPORT jint JNICALL
Java_com_jointheparty_app_core_SyncCore_nativeSubmitPlayerState(
    JNIEnv*, jobject, jlong handle, jlong position_ms, jboolean is_paused,
    jlong received_mono_ns) {
    auto* h = handle_of(handle);
    if (!h) return SC_ERR_INVALID_ARG;
    sc_player_state_t ps{};
    ps.position_ms = position_ms;
    ps.is_paused = is_paused == JNI_TRUE;
    ps.received_mono_ns = static_cast<uint64_t>(received_mono_ns);
    return sc_submit_player_state(h->session, &ps);
}

JNIEXPORT jint JNICALL
Java_com_jointheparty_app_core_SyncCore_nativeSetUserNudgeMs(JNIEnv*, jobject,
                                                             jlong handle,
                                                             jint nudge_ms) {
    auto* h = handle_of(handle);
    return h ? sc_set_user_nudge_ms(h->session, nudge_ms)
             : SC_ERR_INVALID_ARG;
}

JNIEXPORT jint JNICALL
Java_com_jointheparty_app_core_SyncCore_nativeSetOutputRoute(
    JNIEnv*, jobject, jlong handle, jint route, jint latency_prior_ms) {
    auto* h = handle_of(handle);
    return h ? sc_set_output_route(h->session, static_cast<sc_route_t>(route),
                                   latency_prior_ms)
             : SC_ERR_INVALID_ARG;
}

JNIEXPORT jint JNICALL
Java_com_jointheparty_app_core_SyncCore_nativeSetAecMode(JNIEnv*, jobject,
                                                         jlong handle,
                                                         jint mode) {
    auto* h = handle_of(handle);
    return h ? sc_set_aec_mode(h->session, static_cast<sc_aec_mode_t>(mode))
             : SC_ERR_INVALID_ARG;
}

JNIEXPORT jint JNICALL
Java_com_jointheparty_app_core_SyncCore_nativeNotifySeekIssued(
    JNIEnv*, jobject, jlong handle, jlong target_ms, jlong issued_mono_ns) {
    auto* h = handle_of(handle);
    return h ? sc_notify_seek_issued(h->session, target_ms,
                                     static_cast<uint64_t>(issued_mono_ns))
             : SC_ERR_INVALID_ARG;
}

// CTL-01b: the shell's echo after actually executing an SC_EVT_ACTIVE_PROBE
// (pause -> delay(pause_ms) -> resume), mirroring nativeNotifySeekIssued's
// echo shape (technical-requirements.md §2.9).
JNIEXPORT jint JNICALL
Java_com_jointheparty_app_core_SyncCore_nativeNotifyProbeExecuted(
    JNIEnv*, jobject, jlong handle) {
    auto* h = handle_of(handle);
    return h ? sc_notify_probe_executed(h->session) : SC_ERR_INVALID_ARG;
}

// DSP-03b: the shell's echo after actually executing an SC_EVT_ACTIVE_DUCK
// (duck STREAM_MUSIC -> delay(duck_ms) -> restore), mirroring
// nativeNotifyProbeExecuted's echo shape (technical-requirements.md §2.12).
// achieved_deci_db is the depth ACTUALLY commanded, never a hardcoded
// nominal 60 — volume-index quantization means -6.0 dB exactly is rarely
// reachable, so the worker's dip detector must judge against what really
// happened.
JNIEXPORT jint JNICALL
Java_com_jointheparty_app_core_SyncCore_nativeNotifyDuckExecuted(
    JNIEnv*, jobject, jlong handle, jint achieved_deci_db) {
    auto* h = handle_of(handle);
    return h ? sc_notify_duck_executed(h->session, achieved_deci_db)
             : SC_ERR_INVALID_ARG;
}

JNIEXPORT jint JNICALL
Java_com_jointheparty_app_core_SyncCore_nativeNotifyLocalPlayback(
    JNIEnv*, jobject, jlong handle, jlong commanded_position_ms) {
    auto* h = handle_of(handle);
    return h ? sc_notify_local_playback(h->session, commanded_position_ms)
             : SC_ERR_INVALID_ARG;
}

JNIEXPORT jint JNICALL
Java_com_jointheparty_app_core_SyncCore_nativePushReference(
    JNIEnv* env, jobject, jlong handle, jfloatArray samples, jint frames,
    jlong track_position_ms) {
    auto* h = handle_of(handle);
    if (!h || !samples) return SC_ERR_INVALID_ARG;
    jfloat* data = env->GetFloatArrayElements(samples, nullptr);
    if (!data) return SC_ERR_NO_MEMORY;
    const sc_status_t st =
        sc_push_reference(h->session, data, frames, track_position_ms);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    return st;
}

JNIEXPORT jint JNICALL
Java_com_jointheparty_app_core_SyncCore_nativeBeginCalibration(JNIEnv*,
                                                               jobject,
                                                               jlong handle) {
    auto* h = handle_of(handle);
    return h ? sc_begin_calibration(h->session) : SC_ERR_INVALID_ARG;
}

JNIEXPORT jint JNICALL
Java_com_jointheparty_app_core_SyncCore_nativeCancelCalibration(
    JNIEnv*, jobject, jlong handle) {
    auto* h = handle_of(handle);
    return h ? sc_cancel_calibration(h->session) : SC_ERR_INVALID_ARG;
}

JNIEXPORT jint JNICALL
Java_com_jointheparty_app_core_SyncCore_nativeCopyRecentCapture(
    JNIEnv* env, jobject, jlong handle, jfloatArray out, jint max_frames,
    jlongArray out_end_ns) {
    auto* h = handle_of(handle);
    if (!h || !out) return 0;
    jfloat* buf = env->GetFloatArrayElements(out, nullptr);
    if (!buf) return 0;
    uint64_t end_ns = 0;
    const jint n = sc_copy_recent_capture(h->session, buf, max_frames, &end_ns);
    env->ReleaseFloatArrayElements(out, buf, 0);  // commit copied frames
    if (out_end_ns) {
        jlong end = static_cast<jlong>(end_ns);
        env->SetLongArrayRegion(out_end_ns, 0, 1, &end);
    }
    return n;
}

JNIEXPORT jint JNICALL
Java_com_jointheparty_app_core_SyncCore_nativeGetCommandLatencyMs(
    JNIEnv*, jobject, jlong handle) {
    auto* h = handle_of(handle);
    if (!h) return -1;
    int32_t out = -1;
    if (sc_get_command_latency_ms(h->session, &out) != SC_OK) return -1;
    return out;
}

// CAL-03: fire-and-forget request; the measurement itself arrives later as
// SC_EVT_LATENCY_RESIDUAL through the usual event trampoline above.
JNIEXPORT jint JNICALL
Java_com_jointheparty_app_core_SyncCore_nativeSampleLatencyResidual(
    JNIEnv*, jobject, jlong handle) {
    auto* h = handle_of(handle);
    return h ? sc_sample_latency_residual(h->session) : SC_ERR_INVALID_ARG;
}

// CAL-05: polled getter, mirrors nativeGetCommandLatencyMs's pattern. 0.0f
// on an invalid handle is indistinguishable from genuine silence by design —
// sc_get_input_level's own contract is to report silence, not an error
// sentinel, whenever there's nothing to read.
JNIEXPORT jfloat JNICALL
Java_com_jointheparty_app_core_SyncCore_nativeGetInputLevel(JNIEnv*, jobject,
                                                             jlong handle) {
    auto* h = handle_of(handle);
    if (!h) return 0.0f;
    float out = 0.0f;
    if (sc_get_input_level(h->session, &out) != SC_OK) return 0.0f;
    return out;
}

}  // extern "C"
