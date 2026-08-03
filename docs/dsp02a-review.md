# DSP-02a review — parameterized β-PHAT & tooling · 2026-08-03

**Status: implemented, verified locally, not yet committed** (spec
`technical-requirements.md` §2.11, ticket DSP-02a in backlog Epic 9).
Design-only spec section; this ticket lands the parameter, the
byte-identical legacy branch, and the offline `lag_analyzer --beta` sweep
tool. **No on-device behavior changes** — every existing call site
(including the referee's `kSampleLatencyResidual` handler) omits the new
argument and stays on the default.

## Branch structure and why the legacy branch is textually preserved

`analyze_window` (`core/src/dsp/lag_window.{h,cpp}`) gains a trailing
defaulted parameter `double whiten_beta = 0.5`. Inside the per-bin spectral
loop, `whiten_beta == 0.5` selects one of two branches:

```cpp
if (whiten_beta == 0.5) {
    // legacy loop body, verbatim
    const float mag = std::sqrt(b.r * b.r + b.i * b.i) + 1e-9f;
    const float p = (b.r * b.r + b.i * b.i) / mag;
    ...
} else {
    // pow path
    const float power = b.r * b.r + b.i * b.i;
    const float p = std::pow(power + 1e-18f, 1.0f - beta_f);
    ...
}
```

The two expressions are mathematically equal at β = 0.5 (`|X|^{2(1-0.5)}
= |X|^1 = |X|²/|X|`) but **not bit-identical**: `std::sqrt(power) + 1e-9f`
then divide is a different rounding path than `std::pow(power + 1e-18f,
0.5f)` (different epsilon, different intermediate rounding in `pow`'s
implementation vs. `sqrt`+divide). `lag_window.h`'s own header carries a
"do not improve the math" warning because the field-test corpus grades this
computation byte-for-byte — so the spec is explicit (§2.11, "non-negotiable
byte-identical rule") that unifying the two into one generalized `pow()`
call is exactly the failure mode to avoid, even though it would "simplify"
the code and agree in exact arithmetic. The legacy branch body is therefore
copied verbatim from the pre-ticket code, not rewritten.

Everything downstream of the whitening loop — the inverse FFT, the argmax/
`peak_ratio`/`found` computation, and the CTL-03a `second_lag_ms`/
`comb_ratio` second pass — is untouched by this ticket; the `if` lives
entirely inside the per-bin loop that produces the whitened spectrum fed to
the inverse FFT.

## The pow-path guard decisions

Non-default betas compute `p = std::pow(power + 1e-18f, 1.0f - beta_f)`,
i.e. `|X|^{2(1-β)}`, per §2.11's derivation table. Decisions:

- **`+1e-18f` guard on the pow base.** Keeps `pow`'s base well-defined and
  away from a literal `0.0f` for silent bins, without materially changing
  the result for any bin with real energy (1e-18 is far below float
  denormal-adjacent magnitudes that matter here). It also avoids relying on
  `pow(0, x)`'s edge-case behavior across libm implementations.
- **No range clamp on `whiten_beta` inside `analyze_window` itself.** The
  function stays total (well-defined for any double) and the *rejection* of
  out-of-range β lives in the CLI (`lag_analyzer`) rather than the library,
  since `analyze_window` is also called directly from tests that may want
  to probe edge behavior. A future β > 1 (more aggressive than full PHAT)
  would still produce a finite, well-defined `p` thanks to the `+1e-18f`
  guard — just an untested one, outside §2.11's design space.
- **Silent-window safety (`test_beta_silent_input_safe`).** All-zeros input
  drives every bin's `power` to 0, so `p = pow(1e-18f, 1-β)` — finite and
  tiny, never NaN/inf. Downstream, `r0` (autocorrelation at lag 0) reads 0,
  `mean|ac|` reads 0, and `peak_ratio` computes as `best_v / mean` with the
  existing `mean > 0 ? ... : 0` guard already in the argmax loop, landing
  `found = false`. This is the same degenerate path the legacy branch
  already takes on silence — the pow path doesn't need a separate guard
  because it plugs into the same unchanged downstream code.

## CLI flag semantics and combined column order

`lag_analyzer --beta <v>`:

- Value-carrying flag parsed in the existing pair scan over `rest` (the
  vector DSP-01b's `--tempo` extraction leaves behind), alongside
  `--min-lag-ms`/`--max-lag-ms`/`--rate`/`--channels`. `--tempo` stays a
  standalone flag pulled out before the pair scan, so it and `--beta`
  coexist without interfering with each other's parsing.
- `bool beta_passed` gates CSV column emission on the flag being **passed**,
  not on the value differing from 0.5 — `--beta 0.5` is a legitimate sweep
  point (§2.11's sweep set is `{0.5, 0.6, 0.7, 0.8}`) and must still show
  the column so a sweep script's output is uniform across all four runs.
- **Range guard:** `--beta` outside `(0, 1]` is a usage error (exit 2,
  stderr message), checked once in `main()` right after parsing, before
  either `--stream` or file mode dispatch. β = 0 (plain autocorrelation,
  no whitening) and β > 1 (more aggressive than full PHAT) sit outside
  §2.11's design space; silently accepting a typo'd value would let it
  quietly poison a DSP-02b sweep run's CSV instead of failing loudly.
- **Combined column order.** When both `--tempo` and `--beta` are passed,
  the header/rows are:

  ```
  ...,comb_ratio,beta,beat_period_ms
  ```

  i.e. `beta` slots in **before** `beat_period_ms`. This is deliberate so
  that DSP-01b/§2.10's "`beat_period_ms` is appended **last**" invariant
  stays literally true in every flag combination this ticket adds, rather
  than becoming "last, unless `--beta` is also passed." Both `run()` (file
  mode) and `run_stream()` (stream mode) implement this with an explicit
  4-way branch (`tempo && beta_passed` / `tempo` / `beta_passed` / neither)
  rather than always concatenating a beta field and leaving it empty when
  absent — so a run without `--beta` keeps its exact pre-ticket column
  count, per the additive-columns-only, CTL-03a precedent.
- Without `--beta`, every header/row is byte-identical to current output,
  including the `--tempo`-only combination (verified below).
- Usage string gains `[--beta V]`.
- `--selftest` is untouched — it never parses `argv` beyond
  `--selftest` itself, so it always runs at the default β = 0.5.

## Test inventory (`core/tests/test_lag_window.cpp`, additive only)

Four new tests, all appended after the existing eight; no existing test
function was modified.

- `test_beta_default_matches_explicit_05` — on the house two-copy
  synthetic, `analyze_window(x, n, rate, lo, hi)` (no beta arg) and the same
  call with an explicit trailing `0.5` produce bit-identical `WindowLag`
  output across all five fields (`==` comparison, not tolerance-based).
  Pins that the default argument routes through the legacy branch.
- `test_beta_nondefault_finds_known_lag` — for β ∈ {0.6, 0.7, 0.8} on the
  same known-lag synthetic, `analyze_window` still recovers the 800 ms lag
  within 5 ms and `found == true`. Pins that the pow path is functional,
  not that it's better (that judgment is DSP-02b's).
- `test_beta_nondefault_differs_from_legacy` — at β = 0.7, `peak_ratio`
  differs from the β = 0.5 run on the identical input. Pins that the pow
  path is a genuinely different computation, guarding against a future
  "simplification" that unifies the branches (they agree in exact
  arithmetic at 0.5, which is precisely the trap the spec calls out).
- `test_beta_silent_input_safe` — all-zeros input at β = 0.7 produces no
  NaN/inf in any `WindowLag` field and `found == false`. Pins the
  silent-bin guard.

## Verbatim ctest tally

```
Test project C:/Users/RBILLC/source/repos/JoinTheParty/build/core
    Start 1: synccore_tests
1/9 Test #1: synccore_tests ...................   Passed   14.55 sec
    Start 2: estimator_tests
2/9 Test #2: estimator_tests ..................   Passed    0.01 sec
    Start 3: policy_tests
3/9 Test #3: policy_tests .....................   Passed    0.01 sec
    Start 4: correlate_tests
4/9 Test #4: correlate_tests ..................   Passed    1.00 sec
    Start 5: input_level_tests
5/9 Test #5: input_level_tests ................   Passed    9.63 sec
    Start 6: dsp_tests
6/9 Test #6: dsp_tests ........................   Passed    0.81 sec
    Start 7: test_oss_ring
7/9 Test #7: test_oss_ring ....................   Passed    0.34 sec
    Start 8: lag_analyzer_selftest
8/9 Test #8: lag_analyzer_selftest ............   Passed    0.05 sec
    Start 9: synccore_abi_c_check
9/9 Test #9: synccore_abi_c_check .............   Passed    0.01 sec

100% tests passed, 0 tests failed out of 9
Total Test time (real) =  26.43 sec
```

`dsp_tests` (which builds `test_lag_window.cpp`) includes the 4 new tests
alongside the 8 pre-existing ones — 12/12 checks pass in that binary.

## CLI check transcript

```
$ ./lag_analyzer.exe --selftest
selftest: lag=800.0ms (expect 800±5) ratio=186.95 found=1 comb_ratio=31.57
selftest PASS

$ <zeros> | ./lag_analyzer.exe --stream --rate 48000 --channels 1
stream: 48000 Hz, 1 ch, 8s window / 2s hop
t_s,lag_ms,peak_ratio,confident,rms_db,comb_ratio

$ <zeros> | ./lag_analyzer.exe --stream --rate 48000 --channels 1 --beta 0.7
stream: 48000 Hz, 1 ch, 8s window / 2s hop
t_s,lag_ms,peak_ratio,confident,rms_db,comb_ratio,beta

$ <zeros> | ./lag_analyzer.exe --stream --rate 48000 --channels 1 --beta 0.7 --tempo
stream: 48000 Hz, 1 ch, 8s window / 2s hop
t_s,lag_ms,peak_ratio,confident,rms_db,comb_ratio,beta,beat_period_ms

$ ./lag_analyzer.exe test.wav          # synthetic two-copy WAV, 800 ms lag
window_start_s,lag_ms,peak_ratio,confident,comb_ratio
0.0,800.0,185.97,1,36.20
2.0,800.0,182.63,1,36.78

$ ./lag_analyzer.exe test.wav --beta 0.7
window_start_s,lag_ms,peak_ratio,confident,comb_ratio,beta
0.0,800.0,270.48,1,29.42,0.70
2.0,800.0,264.21,1,26.47,0.70

$ ./lag_analyzer.exe test.wav --beta 0.7 --tempo
window_start_s,lag_ms,peak_ratio,confident,comb_ratio,beta,beat_period_ms
0.0,800.0,270.48,1,29.42,0.70,1045.6
2.0,800.0,264.21,1,26.47,0.70,650.4

$ ./lag_analyzer.exe test.wav --tempo   # unchanged vs. pre-DSP-02a output
window_start_s,lag_ms,peak_ratio,confident,comb_ratio,beat_period_ms
0.0,800.0,185.97,1,36.20,1045.6
2.0,800.0,182.63,1,36.78,650.4

$ ./lag_analyzer.exe test.wav --beta 1.5
bad --beta 1.5: must be in (0, 1] (§2.11 sweep range is 0.5-0.8)
$ echo $?
2

$ ./lag_analyzer.exe test.wav --beta 0
bad --beta 0: must be in (0, 1] (§2.11 sweep range is 0.5-0.8)
$ echo $?
2
```

`lag=800.0ms` and the plain-run/`--tempo`-only rows are identical to
pre-ticket output (compared by eye against the CTL-03a/DSP-01b review docs'
own transcripts and by the unmodified `lag_analyzer_selftest` ctest entry),
confirming the byte-identical rule holds through the CLI as well as the
library. Note `peak_ratio`/`comb_ratio` genuinely change at β = 0.7 (270.48
vs. 185.97) — expected and desired, since that's the whole point of
`test_beta_nondefault_differs_from_legacy`'s pin.

## Deviations / uncertainties

- None from the spec's interface or byte-identical requirements. Two
  implementation choices not fully dictated by the spec text, called out
  for visibility:
  - The `whiten_beta` range guard `(0, 1]` lives only in the CLI, not in
    `analyze_window` itself (see "pow-path guard decisions" above) — the
    spec's guard language ("clamp/document behavior for pathological β")
    was left to implementer judgment; documented in both the header and
    `lag_window.cpp`'s comment rather than enforced with an assert/clamp in
    the library function.
  - The combined-column header/row emission uses an explicit 4-way branch
    per call site (8 total: 2 in `run()`, 2 in `run_stream()`, doubled for
    header vs. per-row) rather than a shared column-builder helper. This
    mirrors the existing `--tempo`-only code's style (which already used a
    2-way `if/else` per site) rather than introducing new shared
    infrastructure — kept consistent with the file's existing idiom at the
    cost of some repetition.
- `docs/dsp02a-review.md` is a new file, per the ticket's explicit
  authorization to add it.
