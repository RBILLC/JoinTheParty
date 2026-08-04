# DSP-02b — β-PHAT Corpus Sweep & Promotion Recommendation (§2.11)

**Ticket:** DSP-02b. **Depends on:** DSP-02a (landed — `analyze_window`'s trailing
`whiten_beta = 0.5` default parameter and `lag_analyzer --beta` tooling).
**This document's deliverable:** the sweep data plus a written promotion
*recommendation*. Per §2.11 and the ticket's own text, this ticket cannot be
closed by a default-value change, and it does not make one — see
[Diff scope](#diff-scope) below.

## 0. Corpus honesty statement — read this first

**This sweep does NOT use the field corpus §2.11 names.** §2.11's corpus gate
reads: *"Sweep β ∈ {0.5, 0.6, 0.7, 0.8} over the full field corpus:
`docs/sync-test-results.md`'s recordings plus the FT8 captures."* Those
recordings and FT8 (field-test-8) captures are real-device, real-room audio
files and **are not present on this disk** — this environment has no access
to them.

What this sweep actually ran against:

1. **Clean program material** — four unmodified WAV files (studio/AI-generated
   tracks, no secondary room copy): `Allentown.wav` plus three `Music_fx_*.wav`
   files, sourced from `C:\Users\RBILLC\Downloads\`.
2. **Two synthetic room fixtures**, synthesized from `Allentown.wav` by a
   deterministic Python script (full parameters and script text in
   [§3](#3-synthetic-fixture-generation-full-reproducible-spec)): a two-copy
   delayed-echo fixture and a multi-tap reverberant-room fixture.

This is a **substitute corpus**, not the field corpus. It exercises the same
DSP code path (`analyze_window` via `lag_analyzer --beta`) and is useful for
catching gross regressions and characterizing directional trends, but it
cannot stand in for real device/room acoustics, real AEC residual, real
Bluetooth/App-Remote latency jitter, or the specific songs and rooms
`docs/sync-test-results.md` and the FT8 captures represent. **Any promotion
signal below is therefore provisional and does not discharge §2.11's field
corpus gate.** A future spec amendment considering this should re-run (or at
minimum re-validate directionally) against the real field corpus before any
default-value change ships.

## 1. Methodology

### 1.1 Build

```
export PATH="C:/Users/RBILLC/tools/llvm-mingw-20260616-ucrt-x86_64/bin:C:/Users/RBILLC/tools/cmake/bin:$PATH"
cd build/core && cmake --build .
```

Result: `ninja: no work to do` — `build/core/lag_analyzer.exe` was already
current with `core/tools/lag_analyzer.cpp` / `core/src/dsp/lag_window.*` at
HEAD (DSP-02a's `--beta` plumbing, verified present by inspection of
`lag_analyzer.cpp`). `./build/core/lag_analyzer.exe --selftest` passed
(`lag=800.0ms found=1`) before the sweep, confirming the binary and its
adjacent DLLs (`libc++.dll`, `libunwind.dll`) are functional.

### 1.2 Fixtures swept

| name | source | kind |
|---|---|---|
| `allentown` | `Downloads\Allentown.wav` (unmodified) | clean, PCM16 stereo 44.1 kHz, ~225 s |
| `musicfx_cool` | `Downloads\Music_fx_cool_quick_sharp_wintery_but_not.wav` (unmodified) | clean, PCM16 stereo 48 kHz, 30 s |
| `musicfx_classical` | `Downloads\Music_fx_warm_soothing_and_slow_classical_mu (1).wav` (unmodified) | clean, PCM16 stereo 48 kHz, 30 s |
| `musicfx_cello` | `Downloads\Music_fx_warm_soothing_slow_solo_cello_clas.wav` (unmodified) | clean, PCM16 stereo 48 kHz, 30 s |
| `two_copy_800ms` | synthesized from Allentown | healthy-lock (two-copy, τ=800 ms) |
| `reverb_room` | synthesized from Allentown | reverberant (multi-tap IR, primary lag 800 ms) |

### 1.3 Sweep command (run once per fixture × β)

```
./build/core/lag_analyzer.exe <fixture.wav> --beta <0.5|0.6|0.7|0.8> > docs/dsp02b-sweep-data/<name>_beta<β>.csv
```

24 runs total (6 fixtures × 4 β values), each using the tool's default file-mode
windowing (8 s window / 2 s hop) and default lag search range (40–2500 ms —
unchanged, no `--min-lag-ms`/`--max-lag-ms` override). `--beta 0.5` was passed
explicitly for the baseline runs (not omitted) so every run, including the
baseline, carries the trailing `beta` CSV column and all four sweeps have an
identical column set, per the orchestrator's environment note.

### 1.4 Raw data location

Per-window CSV output for all 24 runs is committed under
[`docs/dsp02b-sweep-data/`](dsp02b-sweep-data/) — one file per fixture × β,
named `<fixture>_beta<β>.csv`. Columns: `window_start_s,lag_ms,peak_ratio,confident,comb_ratio,beta`.
The synthesized WAV fixtures themselves are **not** committed (large binary,
regenerable — see §3).

## 2. Definitions used throughout this report

- **Healthy-lock window:** a window in the `two_copy_800ms` fixture where the
  **β=0.5 baseline** reports `confident=1` (`found=true`). All 109/109 windows
  in that fixture qualify (see §4.2) — the synthetic two-copy signal locks
  cleanly at every window at the shipped default.
- **Lag flip:** for a given fixture and window, `|lag_ms(β) − lag_ms(0.5)| > 1.0 ms`
  for the same window index. The 1.0 ms tolerance absorbs the CSV's 0.1 ms
  print rounding and normal sub-ms estimation noise while still catching any
  jump to a materially different lag candidate.
- **Found regression:** a window where the β=0.5 baseline reports
  `confident=1` and the swept β reports `confident=0` for the same window.

## 3. Synthetic fixture generation (full reproducible spec)

Both fixtures are generated by a deterministic Python script (no RNG, no
wall-clock dependence) from `Allentown.wav`. Script and fixtures were written
to the session scratchpad, not committed:

```
<scratchpad>/dsp02b-fixtures/synthesize_fixtures.py
<scratchpad>/dsp02b-fixtures/two_copy_800ms.wav
<scratchpad>/dsp02b-fixtures/reverb_room.wav
```

Invocation used:

```
python synthesize_fixtures.py "C:\Users\RBILLC\Downloads\Allentown.wav" .
```

Both outputs: mono PCM16 WAV at the source rate (44.1 kHz), downmixed from
Allentown's stereo source by L/R averaging (matching `read_wav_pcm16`'s own
downmix convention), peak-normalized to 0.95 of int16 full scale post-mix to
guarantee no clipping.

**Fixture 1 — `two_copy_800ms.wav` (healthy-lock):**

```
room(t) = dry(t) + alpha * dry(t - tau)
tau   = 800 ms   (delay; well inside the 40-2500 ms default search range)
alpha = 0.5      (attenuation, i.e. -6.02 dB)
```

**Fixture 2 — `reverb_room.wav` (reverberant room):**

```
room(t) = dry(t) + alpha * sum_i[ g_i * dry(t - (tau + t_i)) ]
tau = 800 ms                              (same primary copy lag as fixture 1)
alpha = 0.5                               (same overall copy attenuation budget)
```

Multi-tap decaying impulse response (`t_i` ms, raw linear weight before
normalization):

| t_i (ms) | raw weight |
|---|---|
| 0 | 1.00 |
| 45 | 0.55 |
| 110 | 0.35 |
| 190 | 0.22 |
| 300 | 0.14 |
| 440 | 0.09 |
| 620 | 0.05 |
| 850 | 0.03 |

`g_i = raw_weight_i / Σ(raw_weights)` — the 8 taps **redistribute** the
copy's alpha=0.5 energy budget (they don't add extra energy on top of the
two-copy fixture), so the two fixtures are energy-comparable and differ only
in whether that copy energy arrives as one clean impulse or an 8-tap
decaying smear. All 8 absolute tap delays (`tau + t_i`) land in **[800,
1650] ms** — fully inside the 40–2500 ms default analyzer search range, per
the environment note that reverb taps must be analyzable, not merely present.

Full script text (reproducible verbatim):

```python
#!/usr/bin/env python3
"""DSP-02b synthetic room-fixture generator (temporary, scratchpad-only).

Deterministic (no RNG, no wall-clock). Reads the clean Allentown.wav source
(PCM16 stereo 44.1 kHz) and writes two mono PCM16 WAV fixtures at the same
44.1 kHz sample rate:

  1. two_copy_800ms.wav   -- healthy-lock fixture
       room(t) = dry(t) + alpha * dry(t - tau),  tau = 800 ms, alpha = 0.5

  2. reverb_room.wav      -- reverberant-room fixture
       room(t) = dry(t) + alpha * sum_i[ g_i * dry(t - (tau + t_i)) ]
       tau = 800 ms (primary direct-path copy lag, same as fixture 1)
       (t_i, raw_weight_i) multi-tap decaying impulse response, ms/linear:
           (0,    1.00)
           (45,   0.55)
           (110,  0.35)
           (190,  0.22)
           (300,  0.14)
           (440,  0.09)
           (620,  0.05)
           (850,  0.03)
       g_i = raw_weight_i / sum(raw_weights)   (energy redistribution, not
       energy addition -- the reverberant copy carries the same total alpha
       budget as fixture 1's single tap, just smeared across 8 delays)
       alpha = 0.5 (same overall copy attenuation convention as fixture 1)
       All 8 absolute tap delays (tau + t_i) land in [800, 1650] ms, i.e.
       fully inside the analyzer's default 40-2500 ms search window.

Both outputs are downmixed to mono by averaging L/R (matching
read_wav_pcm16's own downmix convention in lag_analyzer.cpp) BEFORE the
delay/mix synthesis, then peak-normalized to 0.95 of int16 full scale to
guarantee no clipping, then quantized to PCM16.

Deterministic by construction: every operation is a fixed arithmetic
transform of the source samples (array shifts, fixed-coefficient weighted
sums, a single global peak-normalization scalar). No random number
generator, no time-of-day/state dependence.

Usage:
    python synthesize_fixtures.py <path-to-Allentown.wav> <output-dir>
"""
import sys
import wave

import numpy as np


def read_wav_stereo_pcm16(path):
    with wave.open(path, "rb") as w:
        assert w.getsampwidth() == 2, "expected PCM16"
        rate = w.getframerate()
        nch = w.getnchannels()
        raw = w.readframes(w.getnframes())
    data = np.frombuffer(raw, dtype="<i2").astype(np.float64)
    if nch > 1:
        data = data.reshape(-1, nch).mean(axis=1)
    return rate, data / 32768.0  # normalize to [-1, 1)


def write_wav_mono_pcm16(path, rate, samples):
    peak = np.max(np.abs(samples))
    if peak > 0:
        samples = samples * (0.95 / peak)  # prevent clipping
    quantized = np.clip(np.round(samples * 32767.0), -32768, 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(quantized.tobytes())


def delayed(dry, delay_samples):
    """Return dry shifted right by delay_samples, zero-padded at the head,
    same length as dry (tail truncated)."""
    out = np.zeros_like(dry)
    if delay_samples < len(dry):
        out[delay_samples:] = dry[: len(dry) - delay_samples]
    return out


def main():
    if len(sys.argv) != 3:
        print("usage: synthesize_fixtures.py <Allentown.wav> <output-dir>")
        return 2
    src_path, out_dir = sys.argv[1], sys.argv[2]

    rate, dry = read_wav_stereo_pcm16(src_path)
    tau_ms = 800.0
    alpha = 0.5
    tau_samples = int(round(tau_ms / 1000.0 * rate))

    # --- Fixture 1: two-copy healthy-lock -----------------------------
    copy1 = delayed(dry, tau_samples)
    room1 = dry + alpha * copy1
    write_wav_mono_pcm16(f"{out_dir}/two_copy_800ms.wav", rate, room1)

    # --- Fixture 2: reverberant room -----------------------------------
    taps_ms = [0, 45, 110, 190, 300, 440, 620, 850]
    raw_weights = [1.00, 0.55, 0.35, 0.22, 0.14, 0.09, 0.05, 0.03]
    weight_sum = sum(raw_weights)
    gains = [w / weight_sum for w in raw_weights]

    reverb_copy = np.zeros_like(dry)
    for t_ms, g in zip(taps_ms, gains):
        d_samples = tau_samples + int(round(t_ms / 1000.0 * rate))
        reverb_copy += g * delayed(dry, d_samples)
    room2 = dry + alpha * reverb_copy
    write_wav_mono_pcm16(f"{out_dir}/reverb_room.wav", rate, room2)

    print(f"wrote {out_dir}/two_copy_800ms.wav ({len(room1)/rate:.1f}s @ {rate}Hz)")
    print(f"wrote {out_dir}/reverb_room.wav ({len(room2)/rate:.1f}s @ {rate}Hz)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

## 4. Results

### 4.1 Clean-program-material stability (false-positive check)

All four clean fixtures already report `confident=1` (`found=true`) on
**100% of windows at every β**, including the β=0.5 baseline. This is
expected and not itself a regression: single-source music has its own strong
short-lag periodicity (pitch/rhythm autocorrelation), so `analyze_window`
reports its best candidate peak and clears the confidence threshold on plain
program material even with no second device present — this is why
`comb_ratio`/`second_lag_ms` exist as downstream disambiguators rather than
gating on `found` alone (§2.6/§2.8). The relevant false-positive question for
this ticket's directive is therefore not "does `found` flip" (it's a
constant `true`) but **whether the reported lag itself stays stable as β
increases**, since that's the concrete risk `lag_window.h`'s own header
warns about ("full PHAT would... make the copy-lag peak unstable against
ordinary program material").

| fixture | β | windows | found=true | lag flips vs β=0.5 (>1ms) | max `peak_ratio` | lag range (ms) |
|---|---|---:|---:|---:|---:|---|
| allentown | 0.5 | 109 | 109 | 0 (baseline) | 39.00 | 40.6–70.1 |
| allentown | 0.6 | 109 | 109 | 10 | 45.37 | 40.6–70.1 |
| allentown | 0.7 | 109 | 109 | 16 | 49.01 | **40.6–267.9** |
| allentown | 0.8 | 109 | 109 | 22 | 48.18 | **40.6–267.9** |
| musicfx_cool | 0.5 | 12 | 12 | 0 (baseline) | 29.98 | 40.6–54.2 |
| musicfx_cool | 0.6 | 12 | 12 | 1 | 32.49 | 40.6–54.2 |
| musicfx_cool | 0.7 | 12 | 12 | 3 | 33.01 | 40.5–54.2 |
| musicfx_cool | 0.8 | 12 | 12 | 5 | 30.20 | 40.5–54.2 |
| musicfx_classical | 0.5 | 12 | 12 | 0 (baseline) | 22.08 | 40.6–72.7 |
| musicfx_classical | 0.6 | 12 | 12 | 0 | 26.99 | 40.6–72.7 |
| musicfx_classical | 0.7 | 12 | 12 | 0 | 32.35 | 40.5–72.7 |
| musicfx_classical | 0.8 | 12 | 12 | 1 | 36.45 | 40.5–85.2 |
| musicfx_cello | 0.5 | 12 | 12 | 0 (baseline) | 32.66 | 40.7–86.3 |
| musicfx_cello | 0.6 | 12 | 12 | 2 | 37.92 | 40.7–72.6 |
| musicfx_cello | 0.7 | 12 | 12 | 4 | 41.97 | 40.7–54.4 |
| musicfx_cello | 0.8 | 12 | 12 | 5 | 40.84 | 40.6–54.4 |

**Notable finding — a wild peak on Allentown at β≥0.7.** At windows
`t=184.0s` and `t=186.0s`, β=0.7/0.8 report `lag_ms=267.9`, a lag that never
appears anywhere in the β=0.5 or β=0.6 output for this fixture (whose
baseline lag range stays within 40.6–86.3 ms across the whole 225 s clip).
Context (raw CSV rows):

```
t=184.0  b0.5: lag=45.8  pr=19.84 comb=1.18  |  b0.7: lag=267.9 pr=18.11 comb=1.07  |  b0.8: lag=267.9 pr=18.54 comb=1.37
t=186.0  b0.5: lag=45.8  pr=20.96 comb=1.24  |  b0.7: lag=45.8  pr=18.71 comb=1.16  |  b0.8: lag=267.9 pr=16.33 comb=1.05
```

`peak_ratio` for the 267.9 ms peak (16–19) is not dramatically higher than
neighboring windows' normal peaks, and `comb_ratio` is low (~1.0–1.4,
indicating an ambiguous/comb-like rather than a sharply dominant peak) — so
this reads as a genuine competing-peak switch, not a wild energy spike, but
it is exactly the "peak destabilizes on ordinary non-flat music" failure
mode `lag_window.h` warns about, materializing at β=0.7 and worsening at
β=0.8. The remaining flips across all four clean fixtures stay within a
tight low-lag family (40–90 ms, all musically-plausible short periodicities)
and do not exhibit this kind of large jump — Allentown at β≥0.7 is the one
outlier instance in this corpus.

Lag-flip counts increase monotonically with β on 3 of 4 clean fixtures
(allentown, musicfx_cool, musicfx_cello): 0 → single digits → double digits
by β=0.8. `musicfx_classical` stays essentially flip-free through β=0.7 and
picks up 1 flip at β=0.8.

### 4.2 Healthy-lock fixture (`two_copy_800ms`) — §2.11 Criterion 1

| β | healthy-lock windows | lag flips | found regressions | mean `peak_ratio` delta vs β=0.5 |
|---|---:|---:|---:|---:|
| 0.5 (baseline) | 109/109 | 0 | 0 | +0.00 |
| 0.6 | 109/109 | 0 | 0 | +34.41 |
| 0.7 | 109/109 | 0 | 0 | +71.84 |
| 0.8 | 109/109 | 0 | 0 | +105.06 |

Zero lag flips and zero found regressions at every swept β, on every one of
the 109 healthy-lock windows. `peak_ratio` margin actually grows
substantially with β on this fixture (clean two-copy signal, no reverberant
smear) — expected, since more whitening sharpens a single clean impulse
correlation peak.

### 4.3 Reverberant fixture (`reverb_room`) — §2.11 Criterion 2

| β | mean `peak_ratio` | Δ vs β=0.5 | mean `comb_ratio` | Δ vs β=0.5 | lag jitter (stdev, ms) | Δ vs β=0.5 | found | lag flips | found regr. |
|---|---:|---:|---:|---:|---:|---:|---|---:|---:|
| 0.5 (baseline) | 55.35 | +0.00 | 1.83 | +0.00 | 0.000 | +0.000 | 109/109 | 0 | 0 |
| 0.6 | 69.77 | +14.43 | 1.84 | +0.01 | 0.000 | +0.000 | 109/109 | 0 | 0 |
| 0.7 | 84.81 | +29.46 | 1.85 | +0.01 | 0.000 | +0.000 | 109/109 | 0 | 0 |
| 0.8 | 97.45 | +42.10 | 1.85 | +0.01 | 0.000 | +0.000 | 109/109 | 0 | 0 |

The reverberant fixture's lag never wavers from 800 ms at any β (jitter is
exactly 0 across the whole sweep — the primary tap's energy dominates
enough that the 8-tap smear never displaces the peak on this particular
fixture), and `comb_ratio` separation is essentially flat (+0.01–0.02,
noise-level). But `peak_ratio` margin grows monotonically and substantially
with β: +14.4 at 0.6, +29.5 at 0.7, +42.1 at 0.8, versus the β=0.5 baseline's
mean of 55.35. Note for context: `reverb_room`'s absolute `peak_ratio` values
are markedly lower than `two_copy_800ms`'s at every β (mean 55.35 vs 115.76
at β=0.5) — the 8-tap smear does measurably blunt the peak relative to
a clean single-impulse copy, which is exactly the effect the reverberant
hypothesis predicts, and higher β partially recovers that lost margin.

## 5. §2.11 promotion criteria — pass/fail against this corpus

**Criterion 1 — "No lag flips and no `found` regressions on the corpus's
healthy-lock windows."**

- **PASS**, narrowly, against `two_copy_800ms`'s 109 healthy-lock windows: 0
  lag flips and 0 found regressions at β=0.6, 0.7, and 0.8 (§4.2).
- **Caveat, not covered by the literal criterion wording but directly
  relevant risk:** the clean-program-material check in §4.1 (which this
  ticket's directive explicitly asked for, beyond §2.11's literal healthy-lock
  scope) shows real lag instability on ordinary single-source music as β
  increases — lag-flip counts climb with β on 3 of 4 clean fixtures, and
  Allentown at β≥0.7 exhibits one clear wild-peak jump to a lag (267.9 ms)
  absent from the β=0.5/0.6 output entirely. This is the exact risk
  `lag_window.h`'s own header and §2.11's derivation section (line 478) flag
  for β→1. It does not fail Criterion 1 as literally scoped (that criterion
  is about healthy-lock windows, not clean single-source stability), but it
  is evidence the risk is real and non-hypothetical on real program material,
  and it should weigh against an unqualified reading of "Criterion 1 passed."

**Criterion 2 — "Measurable gain on the reverberant/echoey windows — higher
`peak_ratio` margin, lower window-to-window lag jitter, or `comb_ratio`
separation improving on the churn class."**

- **PASS** via the `peak_ratio` margin leg specifically: `reverb_room` shows a
  clear, monotonic, substantial `peak_ratio` gain with β (+14.4 / +29.5 /
  +42.1 at β=0.6/0.7/0.8 respectively, §4.3). Lag jitter was already zero at
  baseline on this fixture (no room to improve — the primary tap dominated at
  every β tested) and `comb_ratio` separation is flat/negligible. The
  criterion is worded as an OR across the three sub-metrics, and one clearly
  cleared.
- This is a **single synthetic fixture**, not the field corpus's reverberant
  windows named in §2.11 — see §0.

## 6. Recommendation

On this substitute corpus, both §2.11 promotion criteria show a pass
signal — Criterion 1 passes narrowly on the healthy-lock definition, and
Criterion 2 passes clearly via `peak_ratio` margin on the one reverberant
fixture tested. However, this sweep also surfaced a concrete, non-hypothetical
instance of the exact destabilization risk §2.11's own derivation section
warns about (a wild lag jump on clean single-source music at β≥0.7), and the
corpus itself is explicitly **not** the field corpus §2.11's gate names (§0).

Given that combination — a real but narrow pass on synthetic/substitute data,
plus direct evidence that the predicted instability risk is not merely
theoretical — **this report recommends that a future spec amendment be
opened to consider promoting β=0.7** (not β=0.8; β=0.8 shows worse clean-audio
lag-flip counts and a deeper wild-peak occurrence at t=186.0s than β=0.7 does,
for no additional Criterion 2 gain that a lower β wouldn't also show — 0.7
sits at the better point of the margin-vs-stability tradeoff observed here),
**contingent on that future amendment re-running (or at minimum directionally
re-validating) this sweep against the actual field corpus**
(`docs/sync-test-results.md`'s recordings and the FT8 captures) before any
on-device default changes. This report does **not** recommend flipping
`whiten_beta`'s default itself — per §2.11 and this ticket's own charter, that
requires a separate future spec section, and this ticket's diff makes no such
change (§7).

## 7. Diff scope

This ticket's diff is:

- `docs/dsp02b-sweep-results.md` (this file)
- `docs/dsp02b-sweep-data/*.csv` (24 files, raw per-window sweep output)

No file under `core/` is touched. No default parameter value,
`PolicyConfig` field, or `analyze_window` default argument changes anywhere
in this diff. No existing test's expected output changes; no on-device
behavior changes. The synthesis script and synthesized WAV fixtures used to
produce the reverberant/two-copy data live only in the session scratchpad
(not part of this repo's diff) — §3 reproduces the script in full so the
fixtures can be regenerated exactly.
