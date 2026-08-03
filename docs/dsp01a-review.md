# DSP-01a review — onset-strength ring & tempogram · 2026-08-03

**Status: implemented and verified (this session).** Spec `technical-requirements.md`
§2.10, ticket DSP-01a in backlog Epic 9. New module only — `core/src/dsp/oss_ring.h/.cpp`
— no existing file's behavior changes. DSP-01b (wiring `push`/`estimate_beat_period`
into the worker drain loop, the §2.8 cross-check, and the `lag_analyzer --tempo`
CSV column) is a separate, not-yet-started ticket; this module is standalone and
unconsumed until then.

## What landed

`OnsetStrengthRing` (`core/src/dsp/oss_ring.h/.cpp`), reusing the house `RealFft`
wrapper (`dsp/fft.h`) rather than adding a second FFT path:

- **STFT**: frame N=1024, hop H=512, Hann window precomputed once at construction.
  `push(samples, n, end_mono_ns)` accumulates arbitrary-size input into a fixed
  1024-sample overlap buffer (`memcpy`/`memmove` only, no reallocation) and runs
  one frame of processing each time a fresh 512-sample hop is available.
- **Spectral flux**: log-compressed magnitude `Y(m,k) = ln(1 + γ·|X(m,k)|)`,
  half-wave-rectified flux `Δ(m) = Σ_k max(0, Y(m,k) − Y(m−1,k))` against the
  previous frame's `Y` (kept in a fixed `double[513]` member).
- **Causal local-mean removal**: a 94-sample running-sum delay line (`W≈47`)
  maintained with an incrementally-updated running sum (O(1) per frame, not a
  94-element rescan); emits `o(m) = max(0, Δ(m−47) − mean)` into the OSS ring.
- **OSS ring**: fixed `M=1125` (~12 s at 93.75 Hz), circular buffer with
  count/write-index bookkeeping, no separate head pointer.
- **`estimate_beat_period(now_ns)`**: unbiased normalized autocorrelation
  `r̂(ℓ) = r(ℓ)/r(0)` computed over the *full* lag range `[0, 224]` (not just the
  search band), so the harmonic term `r̂(2ℓ)` is always available for every
  candidate `ℓ` in the search band `[24, 112]`. Harmonic sum
  `s(ℓ) = r̂(ℓ) + 0.5·r̂(2ℓ)` picks `ℓ*`; parabolic interpolation around `ℓ*`
  refines to sub-bin precision (defensively clamped to `±0.5` bin — not
  spec-mandated, guards a degenerate/near-flat neighborhood, never fires on a
  clean peak). `period_ms = 1000·(ℓ*+δ)/93.75`.
- **Stability**: a small fixed history ring (capacity 8) of `{period_ms, mono_ns}`;
  `stable` requires the last 3 entries to agree within ±10 ms *and* span ≥ 20 s
  (the §2.7 `confirm_window_ns` idiom, not a new rule).
- **`reset()`**: clears the OSS ring, the frame/flux-delay state, and the
  stability history — epoch rule, mirrors `CorrectionPolicy::reset()`.

## Constants and their provisional markers

Per §2.10's "correction to the original brief" (the constants are cited from
model knowledge, not yet checked against Peeters 2007 / Grosche & Müller 2011):

- `kLogCompressionGamma = 100.0` — named, documented at its declaration in
  `oss_ring.h` as provisional/field-tunable, explicitly not to be silently
  frozen the way `confirm_floor_ms` was after its RFC 5905 grounding resolved.
- `kHarmonicSumWeight = 0.5` — same provisional/field-tunable status, same
  comment pattern.

Neither constant is inlined as a bare literal anywhere in `oss_ring.cpp`.

## Memory-safety / allocation discipline

All storage — `hann_`, `stage_`, `windowed_`, `spec_` (FFT scratch), the fixed
C arrays for `prev_y_`, the flux delay line, the OSS ring, and the stability
history — is sized once in the constructor's initializer list or as
fixed-size array members. `push()` and `estimate_beat_period()` use only
`memcpy`/`memmove` and indexed array writes; `RealFft::forward`'s internal
`freq.resize(nbins())` call is a no-op once the target vector already holds
that size (true from the first frame onward, since `spec_` is a persistent
member reused every call, not a per-call local). `estimate_beat_period`'s
per-lag autocorrelation arrays (`raw[225]`, `r_hat[225]`) are stack-allocated,
not heap.

Guarded degenerate cases, all returning `{0, 0, false}`: fewer than 225 valid
OSS samples (the harmonic sum needs `r(224)` to have at least one averaging
term), and `r(0) <= 0` (silent/degenerate signal).

## `salience` — diagnostics only, never a gate

Per §2.10's confidence contract and the §2.6 extreme-value-statistic warning
(`test_lag_window.cpp`'s `sqrt(2 ln N)` comment: a peak-vs-mean ratio computed
over one array is not evidence), `salience = s(ℓ*)/mean(s)` is computed and
returned on `BeatEstimate` but is **not** read anywhere in `oss_ring.cpp`
except to populate that field. `stable` is gated exclusively by the
three-estimate agreement-over-time rule. `BeatEstimate`'s doc comment in
`oss_ring.h` states this explicitly as a standing rule for any future caller.

## Test inventory (`core/tests/test_oss_ring.cpp`)

Framework-free: local `CHECK` macro, inline LCG-driven synthetic signal
generation, no fixture files — mirrors `test_lag_window.cpp`/`test_synccore.cpp`.

1. `test_120bpm_click_track_locks` / `test_90bpm_click_track_locks` /
   `test_60bpm_click_track_locks` — a band-limited click track (short decaying
   noise bursts) at 500/666.7/1000 ms period, pushed in varying realistic block
   sizes (480–4096 samples) over 50 s, sampled every 12 s (spacing chosen so the
   last 3 sampled estimates can legitimately span ≥ 20 s, matching the real
   `kSampleLatencyResidual`-cadence polling this module is designed for, not a
   per-frame call). Pins: final estimate `stable == true` and `period_ms` within
   5 ms of truth.
2. `test_octave_ambiguity_picks_fundamental` — alternating strong/weak clicks at
   a 2:1 spacing (32 OSS bins / 64 OSS bins, ~341/683 ms — see the in-code
   comment for why this deviates from the spec's literal 250/500 ms example: a
   perfectly regular synthetic click machine is exactly self-similar at *every*
   multiple of its accent period, so a literal 24/48-bin pair puts the
   fundamental's own octave-up (96 bins, still inside the [24,112] search band)
   in an artificial tie with the fundamental itself — verified empirically
   before the bin choice was corrected, the estimate flip-flopped between 512 ms
   and 1024 ms run to run. Choosing fund=64 puts 2×fund=128 outside the search
   band, removing that synthetic-signal-only artifact while still fully
   exercising the sub-vs-fund disambiguation the ticket asks for). An
   independent proxy computation (same unbiased-autocorrelation formula, run
   directly over the ground-truth click amplitudes, no access to
   `OnsetStrengthRing` internals) confirms the subdivision lag's raw `r̂` is
   genuinely competitive (within 20%) with the fundamental's — a real negative
   test, not a tautology. Pins: harmonic-sum selects the fundamental
   (682.7 ms ± 10 ms), `stable == true`.
3. `test_no_beat_white_noise_never_stable` — 50 s of LCG white noise, sampled
   every 12 s; asserts `stable == false` at *every* sampled call across the
   whole run, not just the last.
4. `test_zero_allocation_after_construction` — mirrors `test_synccore.cpp`'s
   `operator new`/`delete` hook and `tl_forbid_alloc` thread-local; pushes 30 s
   of a click track in varying block sizes and calls `estimate_beat_period`
   after every push, with the guard armed. Pins: zero forbidden allocations
   after construction.
5. `test_frozen_ring_does_not_latch_stable` — orchestrator-added after review;
   see "Orchestrator addition" below. Pins: duplicate polls of an unchanged
   ring never accumulate stability corroboration.
6. `test_reset_clears_stability_and_ring` — runs a 120 BPM track to a confirmed
   `stable == true`, calls `reset()`, then calls `estimate_beat_period`
   immediately. Pins: `{0, 0, false}` — no estimate or stability state survives
   the epoch boundary.

## Orchestrator addition after review: the frozen-ring guard

Verification of the subagent's work found a stability-latch gap the test
suite didn't cover (the same class as CTL-02's originally-missing
window-span pin): `estimate_beat_period` appended to the stability history
on **every** call, but session time can advance while capture is stalled —
player-state timestamps advance `wk.now_ns` with no audio flowing, and the
shell keeps polling on its own cadence. Three polls of an *unchanged* OSS
ring spaced ≥ 20 s of session time apart would return three identical
periods and latch `stable = true` off a single frozen window — defeating
§2.10's reproducibility-across-independent-windows intent.

Fix (module-level, orchestrator-authored): a poll with no new OSS frame
since the last history append computes and returns its estimate but appends
nothing (`frames_at_last_history_` tracks `frames_emitted_` at the last
append; cleared in `reset()`). Duplicate polls are idempotent with respect
to the stability history; a poll right after a legitimately-stable one still
reads stable from the existing history, so the flag doesn't flap on benign
double-polls.

Pinned by `test_frozen_ring_does_not_latch_stable`: 25 s of clean 120 BPM
audio, one estimate, then two more polls over the byte-identical ring
spanning 24 s of session time → `stable` must stay false. The test was
proven to bite by temporarily disabling the guard (`if (true)`) — it fails
exactly on `!e3.stable` — then re-verified green with the guard restored.

**Carry-forward for DSP-01b:** the wiring ticket should still prefer calling
`estimate_beat_period` only when capture has actually progressed (the
`kSampleLatencyResidual` cadence normally guarantees this), but the module
no longer depends on the caller getting that right.

## CMake

`src/dsp/oss_ring.cpp` added to the `synccore` static library target (same
list as `lag_window.cpp`). `test_oss_ring` registered via the existing
`synccore_add_test` helper, plus one additive `target_include_directories`
call giving it the private kissfft include path — `oss_ring.h` (unlike
`lag_window.h`) holds a `RealFft` member directly, so its header pulls in
kissfft's own headers, which `lag_window.h` never needed to expose.

## Verification (first-hand)

`export PATH="C:/Users/RBILLC/tools/llvm-mingw-20260616-ucrt-x86_64/bin:C:/Users/RBILLC/tools/cmake/bin:$PATH"`
`cd build/core && cmake --build . && ctest --output-on-failure`

```
Test project C:/Users/RBILLC/source/repos/JoinTheParty/build/core
1/9 Test #1: synccore_tests ...................   Passed    8.70 sec
2/9 Test #2: estimator_tests ..................   Passed    0.01 sec
3/9 Test #3: policy_tests .....................   Passed    0.00 sec
4/9 Test #4: correlate_tests ..................   Passed    0.99 sec
5/9 Test #5: input_level_tests ................   Passed    9.56 sec
6/9 Test #6: dsp_tests ........................   Passed    0.47 sec
7/9 Test #7: test_oss_ring ....................   Passed    0.32 sec
8/9 Test #8: lag_analyzer_selftest ............   Passed    0.05 sec
9/9 Test #9: synccore_abi_c_check .............   Passed    0.01 sec

100% tests passed, 0 tests failed out of 9
Total Test time (real) =  20.12 sec
```

Suite count: 8 → 9, as the ticket specified. All pre-existing suites pass
unmodified — no existing test's expected output changed.

## What's next

DSP-01b: wire `push`/`estimate_beat_period` into the worker drain loop at the
`append_history` post-AEC tap, the §2.8 `second_lag_ms` cross-check, and the
`lag_analyzer --tempo` CSV column. The MHT hypothesis-bank seeding contract
(`fix_offset ± k·beat_period_ms`, k=1..3) remains documented as a future
consumer, out of scope for both DSP-01a and DSP-01b.
