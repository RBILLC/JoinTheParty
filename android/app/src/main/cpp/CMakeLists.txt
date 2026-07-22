# NAT-04: JNI bridge. Consumes the single source-of-truth core build
# (core/CMakeLists.txt) — the same tree desktop tests and CI compile.
cmake_minimum_required(VERSION 3.28)

project(synccore_jni LANGUAGES C CXX)

set(SYNCCORE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../../core synccore_core)

add_library(synccore_jni SHARED
    synccore_jni.cpp
)

target_link_libraries(synccore_jni
    PRIVATE synccore
    PRIVATE log
)

target_compile_options(synccore_jni PRIVATE -Wall -Wextra)
