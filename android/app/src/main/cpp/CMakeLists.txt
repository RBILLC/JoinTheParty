# NAT-04: JNI bridge. Consumes the single source-of-truth core build
# (core/CMakeLists.txt) — the same tree desktop tests and CI compile.
cmake_minimum_required(VERSION 3.28)

project(synccore_jni LANGUAGES C CXX)

set(SYNCCORE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../../core synccore_core)

# Oboe ships its CMake config via Prefab (app/build.gradle.kts: prefab = true,
# com.google.oboe:oboe:1.9.3). NAT-02 links it for direct-from-audio-callback
# capture (audio_capture.cpp) — no JNI on the RT path.
find_package(oboe REQUIRED CONFIG)

add_library(synccore_jni SHARED
    synccore_jni.cpp
    audio_capture.cpp
)

target_link_libraries(synccore_jni
    PRIVATE synccore
    PRIVATE log
    PRIVATE oboe::oboe
)

target_compile_options(synccore_jni PRIVATE -Wall -Wextra)
