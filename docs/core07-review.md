# CORE-07 review — fixture regression suite + CI gate

**Ticket:** [#5](https://github.com/RBILLC/JoinTheParty/issues/5) (`CORE-07`).
**Scope this pass:** the fixture *format*, a data-driven replay *engine*
(`fixture_tests`, `core/tests/test_fixture_suite.cpp`) wired into the
existing ctest/CI gate, and an initial regression corpus of 11 fixtures.

## What "replays through the full core" means here

The ticket's language ("beach/party captures," "SNR 6-30 dB," raw audio
fixtures) describes a corpus SyncCore as built cannot literally replay: the
desktop core never touches audio or does recognition — `SyncEstimator` and
`CorrectionPolicy` (`core/src/estimator/`, `core/src/policy/`) consume
already-recognized fixes (timestamp, offset, confidence, skew) and player
states, exactly as `test_estimator.cpp`'s `World` and `test_policy.cpp`'s
closed-loop simulations already do by hand. ACRCloud, the microphone, and
`lag_analyzer`'s acoustic ground truth all live outside this boundary (the
Android/iOS shells and `tools/lag_analyzer.cpp`).

So "the full core" that a fixture regression suite can actually gate is the
estimator + policy closed loop — which is also where every field-test
defect this repo has chased (self-match, phantom fixes, persistence gate,
drift, track-lost) was actually diagnosed and fixed. This pass delivers
that gate, data-driven instead of hand-written C++, and designs the format
so raw audio/recognition captures slot in as files later without touching
the runner (see "Path to real captures" below).

## Fixture format

One `*.fixture` file per scenario, in `core/tests/fixtures/`. Plain text,
line-oriented, three line kinds:

- `# comment` — ignored, along with blank lines.
- `key: value` — a directive. Namespaced (`world.*`, `policy.*`,
  `estimator.*`, `fixes.*`, `expect.*`) so the parser stays a flat
  key-value map; no nesting, no schema library.
- `FIX ...` / `PLAYER ...` — whitespace-separated event rows, interpreted
  according to `mode` (below).

`name`, `desc`, `mode`, and `horizon_s` are required; a malformed or
incomplete file is itself a suite failure (`FAIL <name>: parse error: ...`),
not a silent skip — a typo in a new fixture fails CI instead of quietly
running zero cases.

### `mode: world` — synthetic closed loop

The file gives a true-error/drift model and a fix cadence; the runner
computes each fix's reported offset from that model, so behavior stays
internally consistent even when the policy's own seeks shift the local
timeline underneath it (mirrors `test_policy.cpp`'s sawtooth/Vienna/
phantom-fix/genuine-jump simulations exactly, just data-driven):

```
world.true_error0_ms   world.drift_ms_per_s   world.local_start_ms
world.step_at_s / world.step_to_ms     (optional one-time discontinuity —
                                         "the room re-seeks" scenarios)
fixes.schedule: auto | explicit
fixes.continue_auto: true|false   (explicit rows first, then auto cadence)
fixes.noise_ms / fixes.confidence / fixes.skew / fixes.apply_seeks
```

`fixes.schedule: auto` fires a bootstrap fix then follows
`CorrectionPolicy::fix_request_due` exactly like a real session.
`fixes.schedule: explicit` gives `FIX <t_s> <confidence> <noise_ms>` rows
for scenarios that need fixes at *exact* times (the phantom-fix and
genuine-jump fixtures need a controlled long gap for the estimator's
posterior variance to regrow past its outlier gate — cadence-driven timing
can't guarantee that).

### `mode: trace` — literal replay

`FIX <t_s> <offset_ms> <confidence> <skew>` rows carry an absolute offset
taken as-is — the shape a converter from a real captured recognition-fix
log (timestamp/offset/confidence tuples) would produce. Optional
`PLAYER <t_s> <position_ms> <paused>` rows supply literal player-state
pushes; without them the runner synthesizes a 1:1 timeline. Because there's
no world model to compute ground truth from, `expect.final_abs_error_ms_max`
compares against an independently supplied `expect.true_error_ms` (e.g. an
acoustic mic measurement) instead of a value the runner computed itself —
same two-numbers-rule `sync-test-results.md` already uses by hand.

### Config overrides and expectations

`policy.*` / `estimator.*` directives override an allowlisted subset of
`PolicyConfig`/`EstimatorConfig` (deadband, confirm floor/window/agree,
large-correction threshold, command latency, lost threshold, outlier gate,
convergence fixes, drift clamp) — enough to reach every scenario in this
corpus; extending the allowlist is a one-line addition in
`build_ecfg`/`build_pcfg`, never a change to the fixture grammar itself.

`expect.*` directives are all optional and independent: `track_lost`,
`converged`, `final_abs_error_ms_max`, `min_seeks`/`max_seeks`,
`min_large_seeks`/`max_large_seeks` (a seek issued while `|filtered error|
>= 1000 ms`, mirroring `test_policy.cpp`'s own `large_seeks` bookkeeping),
`min_fixes`. A fixture states only the properties its scenario cares about.

## Suite structure

`core/tests/test_fixture_suite.cpp` (registered as ctest target
`fixture_tests`) discovers every `*.fixture` under a compile-time-baked
absolute path (`SYNCCORE_FIXTURES_DIR`, set from
`CMAKE_CURRENT_SOURCE_DIR` in `core/CMakeLists.txt` — so the test is
correct regardless of which build directory invokes it, load-bearing given
this pass builds from the isolated `build/core07` tree), parses each,
replays it through a real `SyncEstimator` + `CorrectionPolicy`, and checks
its `expect.*` directives. House convention followed: only failures print
(`FAIL <fixture>: <reason>`), plus one info line per fixture and a final
summary — matching every other suite in `core/tests/`.

**Adding a new regression case is a new file, never a code or CMake
change** — this was the explicit design constraint from the ticket, and is
what makes this suite the right foundation for the future §2.11-style
corpus gate MHT promotion is waiting on: a promotion decision needs a
growing, reviewable pile of scenario files, not pull requests that touch
`test_fixture_suite.cpp` for every new case.

### Portability note: two headers not used

`<filesystem>` and `<fstream>` were the obvious choices for directory
listing and file reading and are used nowhere in this file — not because
they're unportable in general, but because the specific llvm-mingw archive
this pass builds with locally ships a trimmed libc++ missing `<filesystem>`
(no umbrella header, no `libc++fs`), `<fstream>`, `<map>`, `<set>`, and
`<windows.h>` outright (verified by grep over the installed headers, not
guessed). Rather than special-case that one toolchain, the suite uses
`std::unordered_map` in place of `std::map`, C `stdio` (`fopen`/`fread`)
plus an in-memory `std::istringstream` in place of `std::ifstream`, and
`<io.h>`'s `_findfirst`/`_findnext`/`_findclose` (the MSVC CRT API mingw
also implements) in place of `<windows.h>`/`<filesystem>` for directory
listing, with a `<dirent.h>` branch for POSIX. All of this compiles and
links unmodified against a normal, non-trimmed toolchain — nothing here is
a workaround that would need reverting once a full toolchain is used.

## The 11 shipped fixtures

| File | Scenario | Ticket/doc reference |
|---|---|---|
| `calm_quick_lock` | 40 ms offset, single clean correction | baseline |
| `deadband_healthy_no_correction` | 15 ms, inside default deadband, must never fire | baseline / negative control |
| `moderate_offset_single_correction` | 60 ms, instantaneous-deadband path | architecture-spec §6.2 |
| `sawtooth_drift_500ppm` | 500 ppm fast clock, recurring pre-emptive corrections | `test_policy.cpp` sawtooth sim |
| `vienna_persistence_285ms` | constant ~285 ms inside the widened 350 ms deadband | CTL-02, FT8/FT9 Vienna |
| `churn_no_false_fire_350` | wide scatter around zero at 350 ms deadband, must never fire | FT8's deadband-150 lesson |
| `track_lost_large_error` | 3000 ms, past `lost_threshold_ms` | §6.2/§2.4 |
| `phantom_large_fix_held` | one conf-0.74 fix reads 1259 ms off, must be held not fired | CTL-03b, FT8 headline defect |
| `genuine_large_jump_corrects` | true error genuinely steps to 1200 ms, must fire once and reconverge | CTL-03b companion case |
| `fast_drift_near_clamp` | 800 ppm true drift, at the estimator's own `drift_clamp_ms_per_s` bound | edge case |
| `trace_replay_illustrative` | demonstrates `mode: trace` end-to-end | mechanism demo (see below) |

`snr_db` is carried as fixture metadata (a proxy for scenario difficulty,
matching the ticket's framing) but is not literal decoded audio SNR — the
core never sees audio, so nothing downstream consumes it numerically. It's
there for a human skimming the corpus, and for a future converter that
derives it from a real capture's actual measured SNR.

**Honesty about provenance:** `trace_replay_illustrative`'s FIX rows are
hand-authored (documented in the file itself), not a literal transcription
of a raw field capture — the repo has narrative field-test logs
(`docs/field-test-9-results.md`, `docs/live_lag_ft9.csv`,
`docs/live_lag_ft10.csv`) but no committed per-fix (timestamp, offset_ms,
confidence) triples to transcribe. Labeling reconstructed numbers as a real
capture would misrepresent the corpus; the file says so explicitly so
nobody mistakes it for one later. The other 10 fixtures are synthetic
world-model scenarios in the same style `test_estimator.cpp`/
`test_policy.cpp` already use by hand, reconstructing documented field-test
failure shapes rather than inventing new ones.

## Path to real captures

When a real per-fix log becomes available (e.g. a debug build logging
`(capture_mono_ns, match_offset_ms, provider_confidence, frequency_skew)`
per `on_fix` call, or raw ACRCloud match responses), converting it to a
`mode: trace` fixture needs no runner changes: transcribe the tuples into
`FIX` rows, set `expect.true_error_ms` from an independent acoustic
measurement (mic capture, `lag_analyzer --stream`) taken over the same
session, and drop the file into `core/tests/fixtures/`. This is the literal
mechanism the ticket's "≥ 10 fixtures with ground-truth offsets" criterion
describes; this pass builds and proves the mechanism and seeds it with 11
scenario files reconstructed from the field-test record, since no raw
per-fix capture is committed to the repo yet to transcribe directly.

## CI design

No new workflow was added. `.github/workflows/core-ci.yml` (pre-existing,
`SCAF-01`) already configures + builds `core/` and runs `ctest
--output-on-failure` on four jobs (Linux ASan/UBSan, Linux TSan, Windows
MSVC, macOS clang) for every push/PR touching `core/**`. Because
`fixture_tests` is registered through the same `synccore_add_test` helper
every other suite uses, it is picked up by all four jobs automatically —
and because it's discovered by directory scan, every fixture file under
`core/tests/fixtures/` is too, with no additional workflow wiring. A ctest
failure already fails the job (`ctest`'s non-zero exit propagates), which
is the acceptance criterion's "regression thresholds enforced" gate.

I verified this suite's only non-`<filesystem>` complication
(`<io.h>`/`<dirent.h>` vs `<windows.h>`) is what four broad, real
toolchains actually ship, not a further compromise for CI specifically:
MSVC (`windows-msvc` job) and mingw both implement the `_findfirst` family
in `<io.h>`; Linux/macOS clang and gcc both ship a real `<dirent.h>`. No
further CI-specific accommodation should be needed.

## Evidence

Built and tested in the isolated `build/core07` tree (never `build/core`),
per this pass's assigned build environment: cmake 3.28+ from
`C:/Users/RBILLC/tools/cmake/bin/`, Ninja from the Android SDK's bundled
copy, llvm-mingw clang/clang++ as the C/C++ compiler, that toolchain's
`bin/` prepended to `PATH` before running the built executables.

```
$ cmake -S core -B build/core07 -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_MAKE_PROGRAM=<ninja> -DCMAKE_C_COMPILER=<clang> -DCMAKE_CXX_COMPILER=<clang++>
-- The C compiler identification is Clang 22.1.8
-- The CXX compiler identification is Clang 22.1.8
...
-- Configuring done
-- Generating done

$ cmake --build build/core07
[35/35] Linking CXX executable fixture_tests.exe   (0 warnings, -Wall -Wextra -Wpedantic)

$ ctest --test-dir build/core07 --output-on-failure
 1/11 synccore_tests ............. Passed 16.02 sec
 2/11 estimator_tests ............ Passed  0.01 sec
 3/11 hypothesis_bank_tests ...... Passed  0.01 sec
 4/11 policy_tests ............... Passed  0.01 sec
 5/11 correlate_tests ............ Passed  1.03 sec
 6/11 input_level_tests .......... Passed  9.65 sec
 7/11 dsp_tests .................. Passed  0.92 sec
 8/11 test_oss_ring .............. Passed  0.36 sec
 9/11 lag_analyzer_selftest ...... Passed  0.05 sec
10/11 synccore_abi_c_check ....... Passed  0.01 sec
11/11 fixture_tests .............. Passed  0.01 sec

100% tests passed, 0 tests failed out of 11
Total Test time (real) = 28.08 sec
```

`fixture_tests`' own stdout (11/11 fixtures passed):

```
  calm_quick_lock: 12 fixes, 2 seeks (0 large), final est=-19.8 ms conv=1 lost=0
  churn_no_false_fire_350: 13 fixes, 0 seeks (0 large), final est=-147.5 ms conv=1 lost=0
  deadband_healthy_no_correction: 5 fixes, 0 seeks (0 large), final est=12.9 ms conv=1 lost=0
  fast_drift_near_clamp: 27 fixes, 7 seeks (0 large), final est=6.2 ms conv=1 lost=0
  genuine_large_jump_corrects: 23 fixes, 2 seeks (1 large), final est=-23.9 ms conv=1 lost=0
  moderate_offset_single_correction: 11 fixes, 2 seeks (0 large), final est=-22.6 ms conv=1 lost=0
  phantom_large_fix_held: 28 fixes, 5 seeks (0 large), final est=-2.9 ms conv=1 lost=0
  sawtooth_drift_500ppm: 40 fixes, 10 seeks (0 large), final est=2.8 ms conv=1 lost=0
  trace_replay_illustrative: 19 fixes, 5 seeks (0 large), final est=284.1 ms conv=1 lost=0
  track_lost_large_error: 1 fixes, 0 seeks (0 large), final est=2994.9 ms conv=0 lost=1
  vienna_persistence_285ms: 15 fixes, 1 seeks (0 large), final est=-51.5 ms conv=1 lost=0
fixture_tests: ran 11 fixture(s) from .../core/tests/fixtures
fixture_tests: all tests passed
```

## Known limitations / deliberately out of scope this pass

- No literal raw-audio or raw-ACRCloud-response fixtures — see "Path to
  real captures" above for exactly what's missing and how to add it.
- `mode: world`'s config-override allowlist (`build_ecfg`/`build_pcfg`) is
  deliberately small; it covers this corpus, not all of
  `PolicyConfig`/`EstimatorConfig`. Extend it as new scenarios need more
  knobs exposed.
- Nothing here replaces `test_estimator.cpp`/`test_policy.cpp`'s existing
  fine-grained, exact-value CORE-02/CORE-03 unit tests — this is a coarser
  regression net layered above them, matching the ticket's framing
  ("regression suite," not a replacement for unit coverage).
- MHT (`HypothesisBank`, tech-req §2.16) is not wired into the replay
  engine — fixtures currently exercise `SyncEstimator` + `CorrectionPolicy`
  only. The `mode`/`fixes.*`/`expect.*` namespacing leaves room for a
  future `bank.*` namespace and an MHT-aware replay path without touching
  fixtures already committed, but that engine change itself is future work
  for whoever drives the §2.11-style corpus gate.
