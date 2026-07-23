// STUB — see webrtc_apm.h. Passthrough implementations.

#include "webrtc_apm.h"

#include <cstring>

namespace webrtc {

std::unique_ptr<AudioProcessing> AudioProcessing::Create() {
    return std::unique_ptr<AudioProcessing>(new AudioProcessing());
}

int AudioProcessing::ProcessStream(const float* const* src,
                                   const StreamConfig& input_config,
                                   const StreamConfig& /*output_config*/,
                                   float* const* dest) {
    if (!src || !dest || !src[0] || !dest[0]) return kNoError;
    if (dest[0] != src[0])
        std::memcpy(dest[0], src[0],
                    input_config.num_frames() * sizeof(float));
    return kNoError;
}

int AudioProcessing::ProcessReverseStream(const float* const* src,
                                          const StreamConfig& input_config,
                                          const StreamConfig& /*output_config*/,
                                          float* const* dest) {
    if (!src || !dest || !src[0] || !dest[0]) return kNoError;
    if (dest[0] != src[0])
        std::memcpy(dest[0], src[0],
                    input_config.num_frames() * sizeof(float));
    return kNoError;
}

}  // namespace webrtc
