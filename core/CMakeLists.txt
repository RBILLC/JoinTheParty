# SyncCore — the single build definition consumed by desktop tests/CI,
# the iOS build, and the Android externalNativeBuild (architecture-spec §10).
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
)

target_include_directories(synccore
    PUBLIC  ${CMAKE_CURRENT_SOURCE_DIR}/include
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src
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

    add_executable(synccore_tests tests/test_synccore.cpp)
    target_link_libraries(synccore_tests PRIVATE synccore)
    # Tests may include internal headers (synccore_testing.h).
    target_include_directories(synccore_tests PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
    if(MSVC)
        target_compile_options(synccore_tests PRIVATE /W4 /permissive-)
    else()
        target_compile_options(synccore_tests PRIVATE -Wall -Wextra -Wpedantic)
    endif()
    if(SYNCCORE_SANITIZE AND NOT MSVC)
        target_compile_options(synccore_tests PRIVATE -fsanitize=address,undefined)
        target_link_options(synccore_tests PRIVATE -fsanitize=address,undefined)
    endif()

    add_test(NAME synccore_tests COMMAND synccore_tests)

    # The public header must stay consumable as plain C99 — compile a pure C
    # translation unit that includes it.
    file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/abi_c_check.c
        "#include <synccore/synccore.h>\nint main(void) { return (int)SC_OK; }\n")
    add_executable(synccore_abi_c_check ${CMAKE_CURRENT_BINARY_DIR}/abi_c_check.c)
    target_link_libraries(synccore_abi_c_check PRIVATE synccore)
    add_test(NAME synccore_abi_c_check COMMAND synccore_abi_c_check)
endif()
