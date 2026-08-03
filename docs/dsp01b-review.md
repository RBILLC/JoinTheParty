# DSP-01b review — tempogram consumer wiring · 2026-08-03

**Status: implemented and verified (this session).** Spec `technical-requirements.md`
§2.10, the §2.8 cross-check paragraph inside it, ticket DSP-01b in backlog
Epic 9. Wires DSP-01a's standalone `OnsetStrengthRing` (`core/src/dsp/oss_ring.h/.cpp`,
`docs/dsp01a-review.md`) into the live worker, adds the §2.8 harmonic
cross-check as a pure function, and appends a `beat_period_ms` column to
`lag_analyzer` behind `--tempo`. No existing test's expected output changed.

## Wiring points (`core/src/synccore.cpp`)

- **`sc_session::wk.oss_ring`**: a `synccore::OnsetStrengthRing` member,
  constructed at `kSupportedRateHz`, sitting alongside `residual_scratch` —
  same worker-thread-only, non-RT home as the referee's own scratch buffer.
- **Drain tap**: `wk.oss_ring.push(wk.scratch.data(), wk.scratch.size(), block_end)`
  runs immediately after `append_history(...)` in the drain loop, on the
  identical post-AEC buffer — no new capture tap. Empty blocks pass through
  unchanged (no special-casing), matching `append_history`'s own treatment.
- **`kSampleLatencyResidual` cadence**: `estimate_beat_period(wk.now_ns)` is
  called exactly once, after the existing `dispatch(SC_EVT_LATENCY_RESIDUAL, &out)`
  and `wk.policy.on_referee_window(...)` calls (neither reordered), making
  the residual sample the one shared "analysis moment" tech-req §2.10
  specifies. `estimate_beat_period` is invoked nowhere else in the tree.
  The existing `synccore::WindowLag lag` local was hoisted out of the
  `if (n > 0)` block it previously lived in (assignment instead of
  declare-and-initialize) purely so the cross-check below can still read
  `lag.second_lag_ms` from the same window's result; the `n > 0` computation
  itself is untouched, and `WindowLag{}`'s default (`second_lag_ms == 0`,
  its own "no competitor" sentinel) is exactly correct when `n <= 0`.
- **`kTrackLost` (in `apply()`)**: `wk.oss_ring.reset()` runs alongside the
  existing `wk.estimator.reset()`/`wk.policy.reset()`, and the two beat-state
  mirrors are cleared to `0`/`0.0`. §2.10's own text doesn't spell this out,
  but it's a direct application of the epoch rule §2.7's persistence ring
  and `CorrectionPolicy::reset()` already follow — a re-listen must never
  let a beat estimate (or its corroboration) survive into a new epoch.

## §2.8 harmonic cross-check

Pure function, declared in `oss_ring.h`, defined in `oss_ring.cpp` — unit
tested without a session:

```cpp
bool beat_comb_corroborated(double second_lag_ms, const BeatEstimate& beat);
```

Returns `false` unless `beat.stable`, `beat.period_ms > 0`, and
`second_lag_ms > 0` (0 is `WindowLag::second_lag_ms`'s own "no competitor"
sentinel); otherwise `true` iff `second_lag_ms` sits within
`kBeatCombAgreeMs` (30.0, matching §2.8's own "< 30 ms" wording verbatim)
of `k * beat.period_ms` for some integer `k` in `[1, kMaxBeatHarmonicMultiplier]`.

`kMaxBeatHarmonicMultiplier = 4` per the PM directive — §2.10's spec text
only says "a small integer k," so 4 is within that latitude. Flagged
explicitly because the ticket's own negative test example only exercises
`k = 1..3`; `k = 4` is pinned by its own dedicated positive test
(`test_beat_comb_corroborated_k4_within_range`) rather than inherited from
that negative case.

Both constants live in `oss_ring.h` next to `kMaxHarmonicLag`/`kStableAgreeMs`
etc., named and commented, not inlined.

## Worker-side plumbing for the cross-check

`sc_session` gains two atomics, following `aec_mode_mirror`'s exact pattern
(worker-thread relaxed store, any-thread relaxed load):

```cpp
std::atomic<int32_t> beat_comb_mirror{0};
std::atomic<double> beat_period_ms_mirror{0.0};
```

Both are written in the `kSampleLatencyResidual` handler, immediately after
computing `beat` and `beat_comb`, and cleared in the `kTrackLost` path.

## Test hook (`core/src/synccore_testing.h`)

```cpp
void sc_test_get_beat_state(sc_session_t*, int32_t* out_beat_comb,
                            double* out_beat_period_ms);
```

Mirrors `sc_test_get_aec_mode`'s exact shape (null-safe, relaxed loads).
Not part of the public ABI — `synccore_testing.h` is explicitly test-only.

## `lag_analyzer --tempo`

Both file mode and `--stream` mode accept `--tempo` (a standalone flag,
parsed by pulling it out of the `argv` tail before the existing
flag/value-pair scan runs, so that scan — and therefore output when
`--tempo` is absent — is untouched). When passed, an `OnsetStrengthRing` is
run over the same audio stream, fed every sample that feeds the windowing
exactly once in stream order (file mode advances an `oss_pushed` cursor up
through each window's end, since consecutive 8 s/2 s windows overlap;
stream mode pushes each freshly-read chunk directly, independent of the
lag-search buffer's own sliding-window eviction). `beat_period_ms` is
appended as the **last** CSV column — the CTL-03a/`comb_ratio` precedent —
carrying the current estimate's `period_ms` at each row (naturally `0.0`
until enough OSS history exists, `BeatEstimate`'s own default; not gated on
`stable`, matching "current estimate" rather than "confirmed estimate").

Sample headers:

```
window_start_s,lag_ms,peak_ratio,confident,comb_ratio,beat_period_ms   (file mode, --tempo)
t_s,lag_ms,peak_ratio,confident,rms_db,comb_ratio,beat_period_ms       (--stream, --tempo)
```

Verified by hand: a 40 s synthetic click-track+delayed-copy WAV, run both
with and without `--tempo` — with the appended column stripped back off,
the `--tempo` output is byte-identical (after normalizing the file's CRLF
line endings, which `cut` handles inconsistently across the two column
counts but the underlying bytes agree) to a plain run's output; `beat_period_ms`
converges to ~500.8 ms for a 500 ms/120 BPM click track. `--stream --tempo`
produces the same additive column. `--selftest` is untouched (never calls
`run`/`run_stream`).

## MHT hypothesis-bank seeding contract

Documented in `oss_ring.h` next to `BeatEstimate`: the future hypothesis
bank (research-closed-loop-control.md §5 item 3; not implemented by this
ticket, no bank code exists anywhere in the tree) seeds its per-fix
hypothesis offsets at `fix_offset ± k*beat_period_ms`, `k = 1..3`, once
`stable` is true. Restated per the standing hard limits: neither the future
bank nor this ticket's cross-check ever touches self-match handling
(CTL-01's exclusive territory — confirmed by grep, see below), and
`salience`/`peak_ratio` are never evidence for either.

## Hard-limit verification (grep/code inspection)

- `peak_ratio` appears in `oss_ring.h`/`oss_ring.cpp` only inside comments
  restating the standing warning — never read by any code path.
- No self-match/self-hearing string or logic appears in `oss_ring.h`/`.cpp`.
- `synccore.cpp`'s `kRecognitionFix` handler (the self-match guard's home)
  is untouched by this diff — confirmed by inspection of the full diff.

## Tests

**`core/tests/test_oss_ring.cpp` (additive, pure-function):**
- `test_beat_comb_corroborated_k2_within_tolerance` — 500 ms stable beat,
  `second_lag_ms=1000.5` → true (k=2).
- `test_beat_comb_corroborated_k4_within_range` — 500 ms stable beat,
  `second_lag_ms=2000.0` → true (k=4 exactly, pins the PM's multiplier range).
- `test_beat_comb_not_corroborated_no_nearby_harmonic` — 500 ms stable beat,
  `second_lag_ms=760` → false (no k in [1,4] within 30 ms).
- `test_beat_comb_not_corroborated_when_unstable` — non-stable beat,
  `second_lag_ms` exactly 2× period → false (stability required regardless
  of exact harmonic alignment).
- `test_beat_comb_sentinels` — `second_lag_ms=0` → false;
  `beat.period_ms=0` (even if degenerately marked stable) → false.

**`core/tests/test_synccore.cpp` (additive, full session/worker):**
- `test_oss_ring_wiring_and_cadence` — pushes an 85 s, 500 ms-period
  (120 BPM) click track (self-contained inline generator, mirroring
  `test_oss_ring.cpp`'s own Lcg/click helpers) via `sc_push_capture` in
  burst-then-drain 10 ms blocks. Confirms the beat-state hook reads
  `0`/`0.0` after 20 s of pushed audio with **no** `sc_sample_latency_residual`
  call issued yet (proving `estimate_beat_period` runs only on that
  cadence, never spontaneously), then issues three residual commands each
  preceded by another 20 s of capture and confirms the mirror lands within
  10 ms of the true 500 ms period.
- `test_beat_comb_cross_check_wiring` — drives the cross-check
  acoustically (no plumbing-pin fallback), **as rewritten by the
  orchestrator after review** (see "Orchestrator verification findings"
  below): the capture is the same 625 ms click track superposed with
  itself at delays of one and two beat periods,
  `x(t) = c(t) + c(t−625ms) + c(t−1250ms)`. Coherent pairs put true
  autocorrelation teeth at 625 ms (two pair contributions) and 1250 ms
  (one), so `analyze_window`'s best lag is ~625 and `second_lag_ms` ~1250 —
  landing on `k = 2` of the beat period the tempogram reads off the
  (unchanged) 625 ms click grid. Polls every 20 s (bounded to 6 attempts,
  per-pass diagnostics printed) until the beat stabilizes near 625 ms AND
  the flag latches; deterministic latch observed at the earliest possible
  pass (pass 2 — stability itself needs 3 estimates spanning ≥ 20 s).
- `test_track_lost_clears_beat_state` — establishes a stable 500 ms beat
  estimate (same pattern as the cadence test), then forces `kTrackLost` via
  a single huge-offset recognition fix (~6 s off, far past
  `lost_threshold_ms`=2000) on a session's very first-ever fix — accepted
  immediately because the estimator's outlier gate only engages once the
  filter is already confident (`p00_` small), which isn't true before any
  fix has landed (same mechanism `test_policy.cpp`'s own track-lost tests
  rely on). Confirms `SC_EVT_TRACK_LOST` actually fired, then asserts both
  beat-state mirrors read `0`/`0.0`.

### Orchestrator verification findings (the flaky comb test, found and fixed)

The subagent's original `test_beat_comb_cross_check_wiring` asserted
`beat_comb == 1` off a **plain** 625 ms click track, reasoning that a click
train's autocorrelation is a comb with teeth only at period multiples. The
subagent reported 9/9 ctest green across three runs; the orchestrator's
independent rerun **failed** on exactly that assertion — agent reports are
claims, and this one was a coin flip that had come up heads three times.

Root cause: the test's clicks are *independent noise bursts* (each drawn
from a continuing LCG stream), so burst `i` and burst `i+k` are different
noise realizations — their lag-`k` products sum to zero-mean realization
noise, not a coherent tooth. And `analyze_window`'s mild spectral whitening
spreads a noise floor across the whole lag range, so `second_lag_ms` (the
max raw value outside the best peak's exclusion band) lands essentially
anywhere in `[40, 2500]` ms. With four ±30 ms harmonic windows to hit, the
flag was roughly a 1-in-10 draw per window — passing occasionally,
including the earlier `lag_analyzer` spot check that had seemed to confirm
the approach.

Fix (orchestrator-authored): make every quantity the flag depends on
coherent — the beat-aligned-copies construction described in the test
inventory above, plus requiring stability AND the latched flag inside the
bounded polling loop (one window-boundary artifact can no longer fail the
run, while the flag still has to genuinely latch off the acoustics), plus
per-pass diagnostics so a future failure prints its own trail. Re-verified:
deterministic latch at pass 2 across three consecutive `synccore_tests`
runs and a full 9/9 ctest pass. Two `lag_analyzer` comments claiming the
ring is "only constructed when --tempo is passed" were also corrected
(it is constructed unconditionally; only feeding/polling is gated).

## Deviations from the ticket's file list

- **`core/CMakeLists.txt`** (not in the ticket's allowed-file list): a
  two-line `target_include_directories(lag_analyzer PRIVATE .../third_party/kissfft)`
  addition was required — `--tempo` pulls in `dsp/oss_ring.h`, which (like
  `test_oss_ring` before it) holds a `RealFft` member directly, so its
  header transitively needs kissfft's private include path. Without this
  the `lag_analyzer` target does not compile at all. This exactly mirrors
  the precedent DSP-01a's own review already documents for `test_oss_ring`
  (`docs/dsp01a-review.md`'s CMake section) — same reason, same one-line
  shape, applied to the one other target that now shares the same header
  dependency. No new build target was added.

## A bug caught during self-verification (test-only)

While driving `test_oss_ring_wiring_and_cadence` through its 4-chunk push
sequence (20 s initial + 3× 20 s before each residual call = 80 s
consumed), the test's own click track was generated at only 70 s,
producing an out-of-bounds read past the vector's end on the final chunk —
a segfault, reproduced consistently, that turned out to have nothing to do
with the production wiring (confirmed by an isolated ~130 s
`OnsetStrengthRing`-only repro and a ~70 s full-session repro outside the
test binary, both clean). Fixed by generating 85 s of track (matching the
actual 80 s consumed, plus margin) and adding a `CHECK(off + count <= sig.size())`
guard in the test's own push helper so a future instance of this class of
bug fails loudly instead of reading past the buffer.

## Build & verify (first-hand)

```
export PATH="C:/Users/RBILLC/tools/llvm-mingw-20260616-ucrt-x86_64/bin:C:/Users/RBILLC/tools/cmake/bin:$PATH"
cd build/core && cmake --build . && ctest --output-on-failure
```

```
Test project C:/Users/RBILLC/source/repos/JoinTheParty/build/core
1/9 Test #1: synccore_tests ...................   Passed   14.55 sec
2/9 Test #2: estimator_tests ..................   Passed    0.00 sec
3/9 Test #3: policy_tests .....................   Passed    0.01 sec
4/9 Test #4: correlate_tests ..................   Passed    0.99 sec
5/9 Test #5: input_level_tests ................   Passed    9.59 sec
6/9 Test #6: dsp_tests ........................   Passed    0.44 sec
7/9 Test #7: test_oss_ring ....................   Passed    0.34 sec
8/9 Test #8: lag_analyzer_selftest ............   Passed    0.06 sec
9/9 Test #9: synccore_abi_c_check .............   Passed    0.01 sec

100% tests passed, 0 tests failed out of 9

Total Test time (real) =  26.00 sec
```

Suite count stays at 9 (DSP-01b adds tests to existing suites, no new test
binary). `synccore_tests` (which now drives ~4 minutes of cumulative
synthetic audio across the whole file) re-run twice more back to back with
no flakiness. `./lag_analyzer.exe --selftest` passes unchanged.
`lag_analyzer --tempo` manually verified (file mode and `--stream`) to
append the column correctly while a plain run's output stays byte-identical
to pre-ticket behavior on the same input.

## What's next

The MHT hypothesis bank itself (research-closed-loop-control.md §5 item 3)
remains unimplemented — this ticket only documents its seeding contract.
DSP-02/DSP-03 (β-PHAT, volume-duck probe) are independent chains per the
Epic 9 dependency graph and unaffected by this work.
