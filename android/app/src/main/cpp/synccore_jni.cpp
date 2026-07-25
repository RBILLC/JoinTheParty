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
// REQUEST_FIX / TRACK_LOST: no payload
void event_trampoline(sc_event_type_t type, const void* payload, void* user) {
    auto* h = static_cast<BridgeHandle*>(user);
    JNIEnv* env = attached_env();
    if (!env) return;

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
        case SC_EVT_REQUEST_FIX:
        case SC_EVT_TRACK_LOST:
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
    env->DeleteLocalRef(cls);
    if (!h->target || !h->on_event) {
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

}  // extern "C"
