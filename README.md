# JoinTheParty

Hear a speaker playing music. Open the app. Your Spotify plays the same song,
in sync, in your ears.

## Repo layout (architecture-spec.md §12)

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

## Literature & Algorithms

The sync controller's design is literature-grounded: every mechanism in
`core/src/policy/policy.h`/`core/src/dsp/lag_window.h` traces to a cited
academic or standards source, with an honest retrieval status attached (read
in full, partially retrieved, or unreachable-and-substituted — never
implied read when it wasn't). Sources span Shazam-style audio fingerprinting,
NTP clock discipline, event-triggered control theory, multi-target data
association, dual control, and Smith-predictor delay compensation. See
[`docs/REFERENCES.md`](docs/REFERENCES.md) for the formal bibliography, and
[`docs/research-offset-disambiguation.md`](docs/research-offset-disambiguation.md)
/ [`docs/research-closed-loop-control.md`](docs/research-closed-loop-control.md)
for the full research behind it.

## Version policy

Every third-party dependency is pinned exactly (vendored tags in
`core/third_party/`, Gradle version catalog, SPM pins). Upgrades to vendored
DSP deps must pass the fixture regression suite (CORE-07) before merge.
