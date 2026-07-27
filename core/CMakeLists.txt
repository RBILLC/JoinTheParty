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
    src/policy/policy.cpp
    src/correlate/correlate.cpp
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
    synccore_add_test(policy_tests tests/test_policy.cpp)
    synccore_add_test(correlate_tests tests/test_correlate.cpp)

    # Field-test ground-truth tool (desktop only): windowed autocorrelation
    # over a PC-mic recording of two devices playing the same song.
    add_executable(lag_analyzer tools/lag_analyzer.cpp)
    target_link_libraries(lag_analyzer PRIVATE synccore)
    target_include_directories(lag_analyzer
        PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/third_party/kissfft)
    add_test(NAME lag_analyzer_selftest COMMAND lag_analyzer --selftest)

    # The public header must stay consumable as plain C99 — compile a pure C
    # translation unit that includes it.
    file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/abi_c_check.c
        "#include <synccore/synccore.h>\nint main(void) { return (int)SC_OK; }\n")
    add_executable(synccore_abi_c_check ${CMAKE_CURRENT_BINARY_DIR}/abi_c_check.c)
    target_link_libraries(synccore_abi_c_check PRIVATE synccore)
    add_test(NAME synccore_abi_c_check COMMAND synccore_abi_c_check)
endif()
