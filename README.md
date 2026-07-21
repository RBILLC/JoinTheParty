# JoinTheParty

Hear a speaker playing music. Open the app. Your Spotify plays the same song,
in sync, in your ears.

## Repo layout (architecture-spec.md §10)

| Path | Contents |
|---|---|
| `core/` | SyncCore — C++17 DSP/control core, pure C ABI, no platform deps |
| `ios/` | Swift/SwiftUI shell (scaffolded in SCAF-02) |
| `android/` | Kotlin/Compose shell (scaffolded in SCAF-03) |
| `backend/` | Token vending + ISRC→URI map service (AUTH-03/04) |
| `tools/` | Fixtures, latency bench |
| `docs/` | Specs and research notes |

Project documents, in reading order: `architecture-spec.md` →
`technical-requirements.md` → `ui-ux-design-system.md` → `backlog-tickets.md`.

## Building SyncCore (desktop)

Requires CMake ≥ 3.28 and a C++17 toolchain (MSVC, clang, or gcc).

```sh
cmake -S core -B build/core -G Ninja
cmake --build build/core
ctest --test-dir build/core --output-on-failure
```

## Version policy

Every third-party dependency is pinned exactly (vendored tags in
`core/third_party/`, Gradle version catalog, SPM pins). Upgrades to vendored
DSP deps must pass the fixture regression suite (CORE-07) before merge.
