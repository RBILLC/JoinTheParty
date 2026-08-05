# SyncCore — the single build definition consumed by desktop tests/CI,
# the iOS build, and the Android externalNativeBuild (architecture-spec §12).
cmake_minimum_required(VERSION 3.28)

project(synccore LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_C_STANDARD 99)

option(SYNCCORE_BUILD_TESTS "Build the desktop test suite" ON)
option(SYNCCORE_SANITIZE "Enable Address/Undefined sanitizers (clang/gcc) or ASan (MSVC)" OFF)

add_library(synccore STATIC
    src/synccore.cpp
    src/estimator/estimator.cpp
    src/estimator/hypothesis_bank.cpp
    src/policy/policy.cpp
    src/correlate/correlate.cpp
    src/dsp/fft.cpp
    src/dsp/lag_window.cpp
    src/dsp/oss_ring.cpp
    src/aec/aec.cpp
    third_party/kissfft/kiss_fft.c
    third_party/kissfft/kiss_fftr.c
    third_party/webrtc_stub/webrtc_apm.cpp
)

target_include_directories(synccore
    PUBLIC  ${CMAKE_CURRENT_SOURCE_DIR}/include
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/third_party/kissfft
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/third_party/webrtc_stub
)

# Vendored code builds with warnings suppressed — ours does not.
set_source_files_properties(
    third_party/kissfft/kiss_fft.c third_party/kissfft/kiss_fftr.c
    PROPERTIES COMPILE_OPTIONS "$<IF:$<CXX_COMPILER_ID:MSVC>,/w,-w>"
)

find_package(Threads REQUIRED)
target_link_libraries(synccore PUBLIC Threads::Threads)

if(MSVC)
    target_compile_options(synccore PRIVATE /W4 /permissive-)
else()
    target_compile_options(synccore PRIVATE -Wall -Wextra -Wpedantic)
endif()

if(SYNCCORE_SANITIZE)
    if(MSVC)
        target_compile_options(synccore PRIVATE /fsanitize=address)
    else()
        target_compile_options(synccore PRIVATE -fsanitize=address,undefined -fno-omit-frame-pointer)
        target_link_options(synccore PRIVATE -fsanitize=address,undefined)
    endif()
endif()

if(SYNCCORE_BUILD_TESTS)
    enable_testing()

    # Test targets may include internal headers (src/…).
    function(synccore_add_test name source)
        add_executable(${name} ${source})
        target_link_libraries(${name} PRIVATE synccore)
        target_include_directories(${name} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
        if(MSVC)
            target_compile_options(${name} PRIVATE /W4 /permissive-)
        else()
            target_compile_options(${name} PRIVATE -Wall -Wextra -Wpedantic)
        endif()
        if(SYNCCORE_SANITIZE AND NOT MSVC)
            target_compile_options(${name} PRIVATE -fsanitize=address,undefined)
            target_link_options(${name} PRIVATE -fsanitize=address,undefined)
        endif()
        add_test(NAME ${name} COMMAND ${name})
    endfunction()

    synccore_add_test(synccore_tests tests/test_synccore.cpp)
    synccore_add_test(estimator_tests tests/test_estimator.cpp)
    synccore_add_test(hypothesis_bank_tests tests/test_hypothesis_bank.cpp)
    # hypothesis_bank.h pulls in dsp/oss_ring.h for BeatEstimate, which
    # (per test_oss_ring's own precedent above) holds a RealFft member
    # directly and so needs kissfft's private include dir too.
    target_include_directories(hypothesis_bank_tests
        PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/third_party/kissfft)
    synccore_add_test(policy_tests tests/test_policy.cpp)
    synccore_add_test(correlate_tests tests/test_correlate.cpp)
    synccore_add_test(input_level_tests tests/test_input_level.cpp)
    synccore_add_test(dsp_tests tests/test_lag_window.cpp)
    synccore_add_test(test_oss_ring tests/test_oss_ring.cpp)
    # oss_ring.h (unlike lag_window.h) holds a RealFft member directly, so
    # its header pulls in kissfft's own headers — give the test target the
    # same private kissfft include dir the synccore library target has.
    target_include_directories(test_oss_ring
        PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/third_party/kissfft)

    # Field-test ground-truth tool (desktop only): windowed autocorrelation
    # over a PC-mic recording of two devices playing the same song. Consumes
    # the core-owned dsp/lag_window module (CAL-02) — no FFT code of its own.
    add_executable(lag_analyzer tools/lag_analyzer.cpp)
    target_link_libraries(lag_analyzer PRIVATE synccore)
    target_include_directories(lag_analyzer
        PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
    # DSP-01b: --tempo pulls in dsp/oss_ring.h, which (like test_oss_ring
    # above) holds a RealFft member directly and so needs kissfft's private
    # include dir too — same precedent, same reason.
    target_include_directories(lag_analyzer
        PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/third_party/kissfft)
    add_test(NAME lag_analyzer_selftest COMMAND lag_analyzer --selftest)

    # The public header must stay consumable as plain C99 — compile a pure C
    # translation unit that includes it. Checked in under tests/ (CTL-01a)
    # instead of generated inline, so it can carry real ABI coverage as new
    # surface is added instead of staying a permanent one-liner.
    add_executable(synccore_abi_c_check tests/abi_c_check.c)
    target_link_libraries(synccore_abi_c_check PRIVATE synccore)
    add_test(NAME synccore_abi_c_check COMMAND synccore_abi_c_check)
endif()
