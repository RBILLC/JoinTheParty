# Build environment (this machine)

Exact, load-bearing paths for building and testing on this Windows box.
Every one of these was previously re-discovered or re-briefed per session;
they are stable now — use them as written.

## Desktop core (C++)

- **Canonical build dir**: `build/core` (Ninja, Release). Scratch/agent
  builds use their own dir (`build/<ticket>`), never `build/core`.
- **CMake**: `C:/Users/RBILLC/tools/cmake/bin/` — the `cmake` on PATH is
  3.22, too old for `cmake_minimum_required(3.28)`. Always use the full
  path.
- **Ninja**: `C:/Users/RBILLC/AppData/Local/Android/Sdk/cmake/3.22.1/bin/ninja.exe`
- **Compilers**: llvm-mingw clang/clang++ at
  `C:/Users/RBILLC/tools/llvm-mingw-20260616-ucrt-x86_64/bin/`
  (clang 22.1.8). This is the STABLE copy — an identical copy existed in a
  session temp scratchpad and older docs/briefs may still reference that
  path; prefer this one, temp dirs get cleaned.
- **DLL rule**: prepend that same `bin/` dir to PATH before running ctest
  or any built exe, or they die with `0xc0000135` (missing DLLs).
- **Toolchain quirk**: this llvm-mingw's libc++ is trimmed — no
  `<filesystem>`, `<fstream>`, `<map>`, `<set>`, no `<windows.h>`. Use
  `<unordered_map>`, C stdio, `<io.h>` where needed (precedent:
  `core/tests/test_fixture_suite.cpp`, see `docs/core07-review.md`).
- **Configure line**:
  ```
  C:/Users/RBILLC/tools/cmake/bin/cmake.exe -S core -B build/core -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_MAKE_PROGRAM=C:/Users/RBILLC/AppData/Local/Android/Sdk/cmake/3.22.1/bin/ninja.exe \
    -DCMAKE_C_COMPILER=C:/Users/RBILLC/tools/llvm-mingw-20260616-ucrt-x86_64/bin/clang.exe \
    -DCMAKE_CXX_COMPILER=C:/Users/RBILLC/tools/llvm-mingw-20260616-ucrt-x86_64/bin/clang++.exe
  ```
- **Suites**: 11 ctest targets as of 2026-08-15 (incl. `fixture_tests`).
  House test convention: print only failures; a passing run says
  `<suite>: all tests passed` and nothing else.

## Android

- **Gradle**: from the `android/` directory, `./gradlew.bat :app:assembleDebug`
  (build) and `./gradlew.bat :app:testDebugUnitTest` (JVM suite).
- **UP-TO-DATE trap**: after C++ changes, do not trust the task summary —
  verify the packaged `libsynccore_jni.so` timestamps under
  `android/app/build/intermediates/merged_native_libs/debug/.../lib/*/`
  are newer than the changed sources.
- **Test-count trap**: gradle may report partial counts on warm runs. The
  authoritative count is a `--rerun-tasks` run plus summing the JUnit XMLs
  in `android/app/build/test-results/testDebugUnitTest/`.
- **adb**: `C:/Users/RBILLC/AppData/Local/Android/Sdk/platform-tools/adb.exe`.
  Package name: `com.jointheparty.app`.

## CI

- `.github/workflows/core-ci.yml` (4 jobs: Linux ASan/UBSan, Linux TSan,
  Windows MSVC, macOS clang) runs full ctest on every `core/**` push/PR.
  First green: 2026-08-15 (`aeb8368` after rerun). Sanitizer/timing
  lessons already encoded: sanitizer link flags must be PUBLIC on the
  static lib; tests that wait on the async analysis worker must poll
  bounded (or push-while-polling), never assume a single fixed sleep.
  `input_level_tests` on macOS is the known former flake — if it fails
  once on a shared runner, rerun before investigating.
