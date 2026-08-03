# Research: revised DSP upgrades — OSS tempogram · β-PHAT whitening · control-plane probe

**Date:** 2026-08-03 · **Status:** research/design only — nothing in this document is
shipped. Spec sections, when these graduate, would be tech-req §2.10 (beat-period
tracker), §2.11 (whitening exponent), §2.12 (duck probe). Companion docs:
`research-closed-loop-control.md` (the MHT bank this seeds),
`research-offset-disambiguation.md`, `docs/REFERENCES.md` (citation conventions).

## 0. Corrections to the brief

Three factual amendments before any design, each grounded in the current tree:

1. **There is no "12 s STFT ring buffer."** What exists is a 12 s **post-AEC PCM**
   history ring — `kHistoryFrames = 48000 × 12` mono floats in `synccore.cpp`,
   written by `append_history()` during worker drain, read via
   `sc_copy_recent_capture()`. No STFT is stored anywhere. Feature 1 therefore adds
   an **incremental onset-strength ring** computed as capture drains; it does not
   read a spectral structure that doesn't exist.
2. **The shipped whitening already IS fractional GCC weighting, at β = 0.5.**
   `lag_window.cpp` keeps `p = |X|²/|X| = |X|¹`, which in the Knapp–Carter
   framing (weighted spectrum `|G|/|G|^β` with `G = |X|²`) is exactly
   `|X|^{2(1−β)}` at β = 0.5. The brief's "modify lag_window.cpp to use β = 0.7"
   is a **move from 0.5 to 0.7**, i.e. *more* whitening — and `lag_window.h`
   carries a load-bearing warning: the field-test corpus grades the current math
   byte-for-byte; any exponent change is corpus-gated (see §2.3).
3. **CTL-01's pause probe is shipped** (`7d0cc28`, tech-req §2.9): triggers
   (referee agreement starvation + Wittenmark turn-off), cooldown, seek
   suppression, echo epoch, and the estimate-shift verdict all exist in
   `policy.h/.cpp`. Feature 3 designs a **gentler actuation and a new verdict
   channel** that composes with that machinery — not a new subsystem. Note the
   duck probe cannot reuse the shipped verdict at all: a volume duck does not
   shift the playback timeline, so the estimate-shift verdict reads zero by
   construction. The verdict source moves to capture energy (§3.3).

---

## 1. Beat tracking & MHT seeding — OSS autocorrelation tempogram

**References:** Peeters (2007), *Template-based estimation of time-varying tempo*;
Grosche & Müller (2011), *Extracting predominant local pulse information from
music recordings*; lineage back to Scheirer (1998). Retrieval status: cited from
model knowledge this session, **not retrieved** — retrieve before promoting any
constant below into a spec section (REFERENCES.md convention).

### 1.1 Onset Strength Signal (spectral flux)

Computed incrementally as the worker drains capture, alongside `append_history`
(same post-AEC tap — no new tap into the capture path):

- Frame `N = 1024` samples (21.33 ms at 48 kHz), hop `H = 512` (10.67 ms),
  Hann window `w[n]`. OSS rate `F_oss = 48000/512 = 93.75 Hz`.
- Per frame `m`, real FFT (`RealFft(1024)`, the existing kissfft wrapper):
  `X(m,k), k = 0..512`.
- Log compression (Grosche & Müller):
  `Y(m,k) = ln(1 + γ·|X(m,k)|)`, `γ = 100` — flattens dynamics so quiet
  passages still contribute onsets.
- Half-wave-rectified first difference, summed over bins:

  ```
  Δ(m) = Σ_k max(0, Y(m,k) − Y(m−1,k))
  ```

- Local-mean removal + rectification (kills slow loudness ramps, keeps pulses):

  ```
  o(m) = max(0, Δ(m) − mean(Δ(m−W..m+W))),  W ≈ 47 (≈ ±0.5 s)
  ```

  Implemented causally with a 94-sample running-sum delay line (output delayed
  ~0.5 s — irrelevant, the consumer looks at 12 s of history).
- Storage: fixed ring of `M = 1125` OSS values ≈ 12 s, mirroring the PCM
  history's span. All buffers sized at init; zero allocation after; worker
  thread only (same non-RT position as the referee).

Cost: ~94 FFTs of 1024/s plus O(N) bin math — well under 1 ms of CPU per
second of audio; the on-demand step below is ~100k MACs. Real-time safe by a
wide margin.

### 1.2 Beat period via 1D autocorrelation of the OSS

On demand (proposed cadence: alongside each referee sample, i.e. the
`kSampleLatencyResidual` rhythm — one shared "analysis moment" per window):

```
r(ℓ) = (1 / (M − ℓ)) · Σ_{m=0}^{M−1−ℓ} o(m)·o(m+ℓ)      (unbiased)
r̂(ℓ) = r(ℓ) / r(0)                                       (normalized)
```

Search `ℓ ∈ [24, 112]` bins ⇔ **lag 250–1200 ms** ⇔ 240–50 BPM. Tempo-octave
disambiguation by harmonic reinforcement before the argmax:

```
s(ℓ) = r̂(ℓ) + 0.5·r̂(2ℓ)          (2ℓ ≤ 224 always exists in the full array)
```

Sub-bin refinement: parabolic interpolation on `r̂` around the winning `ℓ*`:

```
δ = 0.5·(r̂(ℓ*−1) − r̂(ℓ*+1)) / (r̂(ℓ*−1) − 2·r̂(ℓ*) + r̂(ℓ*+1))
beat_period_ms = 1000 · (ℓ* + δ) / 93.75
```

Bin quantization alone is 10.67 ms; interpolation brings the estimate to ~2–3 ms,
comfortably inside the MHT gates' tolerances.

### 1.3 Confidence: reproducibility, never a single-array threshold

Standing warning 3 applies verbatim: a peak-vs-mean ratio over one array is an
extreme-value statistic. The publishable gate is **agreement across successive
independent computations** — the same principle §2.7 and the referee sentinel
already use:

```
struct BeatEstimate { double period_ms; double salience; bool stable; };
```

`stable` = the last 3 estimates (each from a window ≥ 8 s newer-audio apart is
not required — the 12 s windows overlap, so require the estimates to span
≥ 20 s, reusing the §2.7 `confirm_window_ns` idiom) agree within ±10 ms.
`salience = s(ℓ*) / mean(s)` is exported **for diagnostics/CSV only**, never as
an admission gate.

### 1.4 Placement and consumers

New `core/src/dsp/oss_ring.h/.cpp` (`OnsetStrengthRing::push(samples, n)` from
the drain loop; `OnsetStrengthRing::estimate_beat_period()` on demand). Owned by
`sc_session::wk` next to `residual_scratch`. Consumers:

- **MHT bank seeding (the point):** hypothesis offsets for the bank =
  `fix_offset ± k·beat_period_ms`, `k = 1..3`, replacing "guess the comb spacing
  from one window." χ²/existence machinery per `research-closed-loop-control.md`
  §5 item 3 — unchanged by this doc.
- **§2.8 cross-check:** if `|WindowLag.second_lag_ms − k·beat_period_ms| < 30 ms`
  for small integer `k`, the competitor peak is the music's own beat comb —
  corroborates a low `comb_ratio` reading as *ambiguity* (Billie Jean class)
  rather than a genuine second copy. `second_lag_ms` remains the free
  cross-check; the tempogram is the principled estimator (the handoff's routes
  (a) and (b) — this design takes (b) and keeps (a) as corroboration).
- Optional UI BPM readout, and a `beat_period_ms` column appended **last** to
  `lag_analyzer` CSVs behind a `--tempo` flag (CTL-03a precedent: additive
  columns only).

**Hard limits restated (standing warnings 3–4):** the bank never touches
self-match — its clutter is self-correlated by construction; CTL-01's sentinel
and probe own that problem. And nothing in this feature consumes `peak_ratio`
as evidence.

---

## 2. Reverberation resistance — fractional whitening (GCC-PHAT-β)

**References:** Knapp & Carter (1976) — retrieved, already in `REFERENCES.md`.
The β-fractional variant traces to reverberant-room studies (e.g. Donohue et
al., 2007, who measured best β ≈ 0.6–0.8 for reverberant environments) —
**not retrieved this session**; verify before speccing.

### 2.1 The one-line change, derived

Knapp–Carter weighted GCC: `Ψ(f) = G(f) / |G(f)|^β`. For a single-buffer
autocorrelation, `G = |X|²`, so the weighted magnitude is:

```
|X|^{2(1−β)}
```

| β    | retained spectrum | status                                             |
|------|-------------------|----------------------------------------------------|
| 0.0  | `|X|²`            | plain autocorrelation — music's own comb dominates |
| 0.5  | `|X|¹`            | **shipped** (`lag_window.cpp` "mild whitening")     |
| 0.7  | `|X|^0.6`         | **proposed**                                        |
| 1.0  | `1` (flat)        | full PHAT — rejected in `lag_window.h`'s header for single-buffer program material |

### 2.2 Why 0.7 should help in reverberant rooms — and might not

Late reverberation piles energy into the strong tonal bins (it is the room
re-emitting the music's own loudest partials, temporally smeared), which under
β = 0.5 still carry weight ∝ `|X|`. The direct-path copy-lag evidence, by
contrast, is phase coherence spread across *many* bins, including the broadband
transient bins. Lowering the retained exponent (0.6 vs 1.0 in magnitude terms)
shrinks the tonal bins' dominance so the cross-bin phase agreement — the true
lag evidence — sets the peak. The countervailing risk is exactly the one the
existing header comment documents: as β → 1 the noise-floor bins are boosted
toward equality and the peak destabilizes on ordinary non-flat music. 0.7 is a
hypothesis that the optimum sits *between* the shipped 0.5 and the rejected
1.0 — plausible per the reverberant-room literature, **unproven on our corpus**.

### 2.3 Corpus gate — how this must land (non-negotiable)

`lag_window.h`: "Ported verbatim — do not 'improve' the math here without
re-running the field-test corpus." Therefore:

1. **Parameterize, default legacy.** Add a trailing defaulted parameter to
   `analyze_window(..., double whiten_beta = 0.5)`. When `whiten_beta == 0.5`
   the code takes the **existing branch verbatim** (`p = power/(mag + 1e-9f)`),
   preserving byte-identical results — `pow(power, 0.5)` is *not* bit-identical
   to the shipped `power/mag` epsilon handling, so the legacy path stays as
   written. Non-default betas take:

   ```cpp
   const float power = b.r * b.r + b.i * b.i;
   const float p = std::pow(power + 1e-18f, 1.0f - beta_f);  // |X|^{2(1-β)}
   ```

2. **Offline A/B first.** `lag_analyzer --beta <v>` flag threading the parameter
   through both file and `--stream` modes (CSV gains a trailing `beta` column
   only when the flag is passed). Sweep β ∈ {0.5, 0.6, 0.7, 0.8} over the full
   field corpus (`docs/sync-test-results.md` recordings + the FT8 captures).
3. **Promotion criteria:** no lag flips or `found` regressions on the corpus's
   healthy-lock windows; measurable gain on the reverberant/echoey windows
   (higher `peak_ratio` margin, lower window-to-window lag jitter, or
   `comb_ratio` separation improving on the churn class). Only then does a spec
   section flip the on-device default — and the referee (`synccore.cpp`
   `kSampleLatencyResidual`) inherits it automatically through the default
   parameter, in a change the corpus has by then re-graded.

This ordering makes step 1+2 **zero-risk to ship immediately** (device behavior
unchanged), with the actual exponent decision made by data.

---

## 3. Control-plane active probing — volume-duck self-match detection

### 3.1 Constraint recap

Standing warning 1: no reference PCM exists — Spotify renders playback and DRM
forecloses touching (or watermarking) the stream. Every probe is therefore a
**control-plane** perturbation of our own output, verified acoustically through
the mic. Available actuators: pause/resume (shipped CTL-01 probe), stream
volume, seek. The shipped 200 ms hard pause is audible and socially costly at a
party; the duck is the gentle tier.

### 3.2 Actuation: 150 ms, −6 dB duck via `AudioManager`

Kotlin shell, `SessionViewModel` — same bounded-coroutine shape as
`onActiveProbe` (no free-running loops; the `maybeSampleReferee` hang precedent
stands):

```kotlin
val am = context.getSystemService(AudioManager::class.java)
val stream = AudioManager.STREAM_MUSIC
val original = am.getStreamVolume(stream)
// Pick the largest index whose dB is ≤ current dB − 6 (API 28+):
val targetIdx = (original downTo 0).first { idx ->
    am.getStreamVolumeDb(stream, idx, deviceType) <=
        am.getStreamVolumeDb(stream, original, deviceType) - 6f
}
val achievedDb = am.getStreamVolumeDb(stream, original, deviceType) -
                 am.getStreamVolumeDb(stream, targetIdx, deviceType)
am.setStreamVolume(stream, targetIdx, 0)
delay(duckMs.toLong())                       // 150 ms nominal
am.setStreamVolume(stream, original, 0)
engine.notifyDuckExecuted((achievedDb * 10).roundToInt())
```

Same shell gates as the pause probe: skip (no echo) when playback is already
paused or calibration is Running/ByEarRunning. New caveats the pause probe
doesn't have:

- **Volume-index quantization:** −6.0 dB exactly is rarely reachable; the echo
  reports `achievedDb` (deci-dB int over JNI) so the core judges the dip
  against the depth *actually commanded*, not the nominal 6.
- **Bluetooth absolute volume:** on A2DP sinks the index change propagates to
  the speaker with sink-dependent latency (tens to a few hundred ms). The
  detector therefore *searches* for the dip in a window rather than assuming
  it lands at the echo instant (§3.3), and `duck_ms` is field-tunable upward
  (150 → 400) exactly like `probe_pause_ms`.
- **Micro-seek variant:** seek-to-current-position forces an App Remote
  rebuffer gap. Rejected as primary — gap length is nondeterministic
  (~100–300 ms), it's as audible as a pause, and it perturbs the timeline the
  estimator is tracking. The escalation tier is the shipped pause probe, which
  is simply an infinite-depth duck with an unambiguous verdict.

### 3.3 Detection: matched-filter dip in the capture-ring log-energy

Worker-side DSP over the existing history (mirrors `kSampleLatencyResidual`'s
pattern — `sc_copy_recent_capture` into a scratch buffer, no new capture path):

1. **Envelope:** 20 ms non-overlapping RMS hops over the analysis span →
   `e(j) = 10·log10(mean(x²) + ε)` at 50 Hz.
2. **Search window:** capture-time `[echo_ns − 250 ms, echo_ns + duck_ms + 750 ms]`
   — wide enough to absorb App Remote + BT-absolute-volume actuation latency.
   Epoch rule holds: every sample consumed postdates the current epoch; all new
   state clears in `reset()`.
3. **Matched filter:** slide a rectangular dip template of width
   `duck_ms / 20 ms` hops across the window; at each position, dip depth
   `D = median(flanking baseline hops) − mean(template hops)`. Take the max-D
   position. Robustness: normalize by the baseline's MAD over the preceding
   3 s of envelope → a z-score `z = D / (1.4826·MAD)`, so a loud, choppy mix
   (which has deep natural 150 ms valleys) demands a deeper dip than a smooth
   ballad.

**The physics of the verdict.** Mic power is a mixture
`P_mic = P_room + P_self`; ducking scales only `P_self` by
`10^(−D_cmd/10)` (≈ 0.251 at 6 dB). Observed dip:

```
D_obs = −10·log10( (P_room + 0.251·P_self) / (P_room + P_self) )
```

| self fraction of mic energy | expected dip (6 dB duck) |
|-----------------------------|--------------------------|
| 100 % (pure self-match)     | 6.0 dB                   |
| 80 %                        | 4.6 dB                   |
| 50 % (true lock, both audible) | 2.9 dB                |
| 20 %                        | 1.0 dB                   |
| 0 % (room only / BT headphones) | 0 dB                 |

So the dip depth is an **estimator of our fraction of captured energy** — finer
information than the pause probe's binary shift. Verdict bands (scaled to
`achievedDb` when it differs from 6):

- `D_obs ≥ 4 dB` and `z ≥ 3` → self-dominant → the shipped kTrackLost path
  (re-listen; recovery via the §2.7 persistence gate, unchanged).
- `D_obs ≤ 1.5 dB` → room-dominant → clear suspicion (sentinel state resets).
- Between, or `z < 3` → **inconclusive** → escalate to the shipped pause probe
  rather than re-ducking in a loop (one escalation per cooldown).

### 3.4 Plumbing (ABI discipline)

- Append at enum end: `SC_EVT_ACTIVE_DUCK` with payload
  `sc_evt_active_duck_t { int32_t duck_ms; }`; new
  `sc_notify_duck_executed(sc_session_t*, int32_t achieved_deci_db)`.
  `SC_EVT_ACTIVE_PROBE` and `sc_notify_probe_executed` are untouched.
  `core/tests/abi_c_check.c`'s exhaustive event switch gains the new case.
- Worker: on duck echo, run §3.3 over the history and hand the *result* to the
  policy — `policy.on_duck_result(dip_db, z, achieved_db, now_ns)`. DSP stays
  in the worker, decision stays in `policy.cpp` ("pure decision logic, no
  clocks, no DSP" — the file's own charter).
- Policy: both existing triggers (`on_referee_window` starvation, `on_tick`
  Wittenmark) arm a **duck** request first; the pause request becomes the
  escalation tier. Seek suppression while any probe/duck is outstanding is
  unchanged. Proposed: duck cooldown 60 s (it's near-inaudible), escalated
  pause keeps `probe_cooldown_ns` = 120 s. Referee stays a verifier — nothing
  here writes the estimator or a latency prior.
- JNI/Kotlin: event case appended (`i0 = duck_ms`), `Event.ActiveDuck(duckMs)`,
  `notifyDuckExecuted(deciDb)` through `SyncEngine`; JVM tests mirror the three
  CTL-01b probe tests (executes when live, no-op when paused, no-op during
  calibration) plus one asserting the achieved-dB echo value.

### 3.5 Sequencing note

The CTL-01 **device pass should still run first with the pause probe as
shipped** — it validates the triggers and the verdict plumbing with the
unambiguous actuator. The duck then swaps in as the default tier once the
triggers are field-proven, changing UX cost, not logic.

---

## 4. Interaction matrix & suggested order

| feature | touches | risk to shipped behavior | order rationale |
|---|---|---|---|
| §2 β parameterization + `--beta` corpus A/B | `lag_window`, `lag_analyzer` | none (legacy default byte-identical) | **first** — offline, zero-risk, informs whether §2 ships at all |
| §1 OSS ring + tempogram | new `dsp/oss_ring`, worker drain loop, `lag_analyzer --tempo` | additive only | **second** — unblocks the MHT bank (spec §2.10), Track 1's goal |
| §3 duck probe | ABI append, worker, policy tier logic, Kotlin | composes with CTL-01; pause tier retained | **third** — after the CTL-01 pause-probe device pass validates the triggers |

Open questions for the spec pass: tempogram cadence coupling to the referee
(shared analysis moment vs independent timer); whether `BeatEstimate.stable`
uses 3-of-N ring agreement (§2.7 idiom) or plain consecutive agreement; duck
cooldown value; whether the inconclusive→pause escalation counts against the
duck's or the pause's cooldown anchor.

## 5. Reference register (retrieval-honest, per REFERENCES.md convention)

- Knapp & Carter (1976), *The generalized correlation method for estimation of
  time delay*, IEEE TASSP — **retrieved previously**, in REFERENCES.md.
- Peeters (2007), tempo estimation via spectral/temporal periodicity templates,
  and Grosche & Müller (2011), predominant local pulse (PLP), IEEE TASLP —
  **not retrieved this session**; constants (γ = 100, harmonic weights) are
  from model knowledge and must be verified against the papers before §2.10 is
  written.
- Donohue, Hannemann & Dietz (2007), performance of phase transform weighting
  (β sweep) in reverberant rooms — **not retrieved**; the 0.6–0.8 optimum claim
  needs the primary before §2.11 fixes β.
- Scheirer (1998), *Tempo and beat analysis of acoustic musical signals*, JASA —
  lineage citation only, **not retrieved**.
