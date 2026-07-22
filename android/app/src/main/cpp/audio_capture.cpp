// audio_capture.cpp — see audio_capture.h for the RT contract,
// timestamping derivation, and restart-on-disconnect design.

#include "audio_capture.h"

#include <ctime>
#include <utility>

namespace synccore_android {

OboeCapture::OboeCapture(sc_session_t* session) : session_(session) {}

OboeCapture::~OboeCapture() { stop(); }

bool OboeCapture::start() {
    stopping_.store(false, std::memory_order_release);
    std::lock_guard<std::mutex> lock(lifecycle_mutex_);
    return openAndStartLocked();
}

void OboeCapture::stop() {
    stopping_.store(true, std::memory_order_release);

    // Join any in-flight restart first. This must NOT be done while holding
    // lifecycle_mutex_: the restart thread needs that same mutex to do its
    // open/close work, so holding it here while joining would deadlock.
    {
        std::lock_guard<std::mutex> guard(restart_thread_mutex_);
        if (restart_thread_.joinable()) restart_thread_.join();
    }

    {
        std::lock_guard<std::mutex> lock(lifecycle_mutex_);
        closeLocked();
    }

    stopping_.store(false, std::memory_order_release);
}

bool OboeCapture::openAndStartLocked() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setSampleRate(kTargetSampleRateHz)
        ->setChannelCount(kTargetChannelCount)
        ->setFormat(oboe::AudioFormat::Float)
        ->setInputPreset(oboe::InputPreset::VoiceRecognition)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    std::shared_ptr<oboe::AudioStream> stream;
    oboe::Result result = builder.openStream(stream);
    if (result != oboe::Result::OK) {
        // Oboe is documented to fall back from Exclusive to Shared
        // automatically when the exclusive path is unavailable, but some
        // devices/ROMs still fail the open outright rather than falling
        // back internally. Retry once, explicitly Shared, before giving up.
        builder.setSharingMode(oboe::SharingMode::Shared);
        result = builder.openStream(stream);
        if (result != oboe::Result::OK) return false;
    }

    if (stream->getSampleRate() != kTargetSampleRateHz ||
        stream->getChannelCount() != kTargetChannelCount) {
        // See formatSupported() in the header: v1 only accepts 48 kHz mono.
        format_supported_.store(false, std::memory_order_release);
        stream->close();
        return false;
    }
    format_supported_.store(true, std::memory_order_release);

    result = stream->requestStart();
    if (result != oboe::Result::OK) {
        stream->close();
        return false;
    }

    stream_ = std::move(stream);
    running_.store(true, std::memory_order_release);
    return true;
}

void OboeCapture::closeLocked() {
    running_.store(false, std::memory_order_release);
    if (!stream_) return;
    stream_->requestStop();
    stream_->close();
    stream_.reset();
}

void OboeCapture::restartAfterDisconnect() {
    {
        std::lock_guard<std::mutex> lock(lifecycle_mutex_);
        if (!stopping_.load(std::memory_order_acquire)) {
            closeLocked();
            openAndStartLocked();
        }
    }
    restart_in_flight_.store(false, std::memory_order_release);
}

oboe::DataCallbackResult OboeCapture::onAudioReady(oboe::AudioStream* stream,
                                                   void* audioData,
                                                   int32_t numFrames) {
    // RT path: no allocation, no locks, no logging. Format is validated
    // once in openAndStartLocked() before requestStart(), so this callback
    // only ever runs against a stream we already know is 48 kHz mono.
    const uint64_t captureMonoNs = firstFrameTimestampNs(stream, numFrames);
    sc_push_capture(session_, static_cast<const float*>(audioData), numFrames,
                    captureMonoNs);
    return oboe::DataCallbackResult::Continue;
}

uint64_t OboeCapture::firstFrameTimestampNs(oboe::AudioStream* stream,
                                            int32_t numFrames) const {
    int64_t hwFramePosition = 0;
    int64_t hwTimeNanoseconds = 0;
    const oboe::Result result = stream->getTimestamp(
        CLOCK_MONOTONIC, &hwFramePosition, &hwTimeNanoseconds);

    if (result == oboe::Result::OK) {
        // getFramesRead() already includes this callback's frames (Oboe
        // updates the counter before invoking the callback), so the first
        // frame of *this* block is (framesRead - numFrames). Extrapolate
        // the HAL's (framePosition, time) reference point to that frame at
        // the nominal sample rate. See the header for why recomputing this
        // fresh every callback avoids accumulating drift.
        const int64_t framesRead = stream->getFramesRead();
        const int64_t firstFrameIndex = framesRead - numFrames;
        const int64_t frameDelta = firstFrameIndex - hwFramePosition;
        const int64_t deltaNs =
            (frameDelta * 1'000'000'000LL) / kTargetSampleRateHz;
        return static_cast<uint64_t>(hwTimeNanoseconds + deltaNs);
    }

    // Fallback: common right after start() before the HAL has produced a
    // timestamp. Treat "now" as roughly the arrival time of the last frame
    // in this buffer and back-compute the first frame from the buffer
    // duration.
    struct timespec now {};
    clock_gettime(CLOCK_MONOTONIC, &now);
    const int64_t nowNs =
        static_cast<int64_t>(now.tv_sec) * 1'000'000'000LL + now.tv_nsec;
    const int64_t bufferDurationNs =
        (static_cast<int64_t>(numFrames) * 1'000'000'000LL) /
        kTargetSampleRateHz;
    return static_cast<uint64_t>(nowNs - bufferDurationNs);
}

void OboeCapture::onErrorAfterClose(oboe::AudioStream* /*stream*/,
                                    oboe::Result /*error*/) {
    // Runs on an Oboe-internal thread (never the audio callback thread).
    // Oboe has already closed the stream by the time this fires; route
    // changes (Bluetooth disconnect, headset unplug, focus loss) surface
    // here rather than through a clean stop(). Reopening synchronously
    // inside this callback is unsafe per Oboe's guidance, so hand off to a
    // dedicated thread.
    if (stopping_.load(std::memory_order_acquire)) return;
    if (restart_in_flight_.exchange(true, std::memory_order_acq_rel)) {
        return;  // a restart is already in progress
    }
    running_.store(false, std::memory_order_release);

    std::lock_guard<std::mutex> guard(restart_thread_mutex_);
    if (restart_thread_.joinable()) restart_thread_.join();
    restart_thread_ = std::thread(&OboeCapture::restartAfterDisconnect, this);
}

}  // namespace synccore_android
