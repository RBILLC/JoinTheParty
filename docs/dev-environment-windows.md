# Windows dev environment notes (SCAF-01)

State of this machine as found on 2026-07-21, and what the core build needs.

## Findings

- No CMake ≥ 3.28 installed system-wide; the Android SDK ships 3.22 (too old
  for `core/CMakeLists.txt`). A portable CMake 3.31.6 works fine.
- Both Visual Studio installs (`2022 Professional`, `18 Professional`) are
  **compiler-front-end only**: `VC\Tools\MSVC\*\bin` exists, but there is no
  `include\` and the `lib\` directories are empty — no CRT, no STL, no
  `vcvarsall.bat`. MSVC cannot link a C++ program on this machine as-is.
- Windows SDK 10.0.22621 is present (headers + ucrt libs + rc/mt).

## Working local setup

Portable [llvm-mingw](https://github.com/mstorsjo/llvm-mingw) (ucrt-x86_64)
+ portable CMake ≥ 3.28 + ninja (Android SDK's is fine). No system install
required — unzip both anywhere and put their `bin` directories on `PATH` for
the build shell:

```powershell
cmake -S core -B build/core -G Ninja -DCMAKE_BUILD_TYPE=Release `
  -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++
cmake --build build/core
ctest --test-dir build/core --output-on-failure
```

To restore MSVC as a local option, install the VS workload
"Desktop development with C++" (adds MSVC libs/headers + vcvars). CI covers
MSVC on `windows-2022` regardless (`.github/workflows/core-ci.yml`), so local
MinGW use doesn't reduce toolchain coverage.
