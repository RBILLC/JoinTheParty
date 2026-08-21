# CTL-06/W3 — offline drift-clamp reproduction with skew=0.0

Resolves wayfinder ticket #44 (map #41). Research by a Sonnet 5 subagent;
every claim below re-verified firsthand by the orchestrator (harness
rebuilt from source and rerun; every cited estimator.cpp line read
directly; repo confirmed untouched).

## (a) What walks `d_` to the clamp when skew=0.0

The **plain position-channel Kalman update**, not the skew branch. The
skew-derived drift observation (`estimator.cpp:108-122`) is guarded by
`if (frequency_skew != 0.0)` and FT11 logged `skew=0.0` on every fix —
it never executes. `d_` moves only via `d_ += k1 * innov`
(`estimator.cpp:98`) in the ordinary `H=[1 0]` update, with
`k1 = p01_/s` — and `p01_` is built purely by the process model in
`predict_to` (`p01_ += dt*p11_`, `p11_ += q_drift_per_s*dt`,
`estimator.cpp:52,55`), never by any drift measurement. A sustained
one-directional innovation stream — exactly what the chronic ~350–520 ms
zEnd bias produces — is therefore indistinguishable from genuine drift,
and walks `d_` to the ±0.8 ms/s clamp (`estimator.cpp:124`,
`estimator.h:35`).

**Reproduced**: `repro.cpp` compiles the real, unmodified `estimator.cpp`
and replays 19 fixes + 4 seeks copied verbatim from
`scratchpad/ft11/jtp_ft11.log:33-290` (S1). Drift saturates at exactly
800.0 ppm on the same fix (L290, offset=216780) where the real log first
prints `drift=800ppm` (`jtp_ft11.log:291`, 18:03:10.115). Run at both
ends of the unlogged `provider_confidence` range (0.70/0.80 per
`ACRCloudProvider.kt`) — same outcome. Honest caveat: intermediate
per-fix values differ from the ~1 Hz display ticks by ~20–150 ppm
(endpoints exact); plausible causes are the unlogged confidence, unlogged
seek command latency, and display sampling offset. The terminal finding
does not hinge on them.

## (b) Why drift parks at the rail under starvation

**There is no decay/leak on `d_` anywhere.** `predict_to` is the only
state mutation and is called only from `on_fix` and `on_local_seek` —
there is no periodic tick path. `estimate_at` (`estimator.cpp:155-179`)
is `const`: it projects `e`/`p00` locally for display, returns the raw
frozen `d_` as `drift_ppm`, and decays only the *displayed* confidence
(`conf_age_tau_s=45 s`). Under fix starvation `d_` is simply never
touched again. Starvation probe: from the clamped state with zero further
inputs, drift is bit-exact 800.0 at +10/30/60/120/210/400 s while
confidence decays 0.821→0.000 — matching FT11's S4 post-pause tail
(−800 ppm held 3m23s+, conf 0.79→0.00, same code path, opposite sign).

## (c) Knobs available to the hardening remedy (#48)

| Knob | Where | Constraint |
|---|---|---|
| `drift_clamp_ms_per_s` ±0.8 | `estimator.h:35` → `estimator.cpp:124` | Symptom cap only; keep some rail — it is also FT2's runaway guard |
| `q_drift_per_s` 4e-5 | `estimator.h:26` → `:55` | Slows bias→drift reinterpretation but also legitimate drift tracking |
| `q_error_ms2_per_s` 1.0 | `estimator.h:25` → `:53` | Lower leverage; doesn't touch `p01_`/`p11_` directly |
| Innovation gate 1200 ms / p00<10000 | `estimator.h:41-42` → `:79-86` | Never fires here (bias ≪ 1200 ms); lowering risks rejecting real 300 ms corrections |
| **Drift-channel gate (new)** | would sit near `:90-105` | Most surgical: require repeated same-sign innovations before `k1*innov` may move `d_`, mirroring the existing two-outliers-in-a-row philosophy |
| **Decay-toward-zero on `d_` (new)** | needs a new mutation path; `estimate_at` is const | Must be gated so it doesn't fire during normal ~10 s inter-fix gaps |
| `seek_exec_var_ms2` 2500 | `estimator.h:29` → `:148` | Upstream of CTL-05's field-verified post-seek machinery — re-validate if touched |
| `meas_noise_ms2` 25 | `estimator.h:23` → `:91` | Blunt; affects CTL-02 convergence broadly — lowest priority |

Cross-cutting: any remedy must leave the skew branch fully effective and
must not regress CTL-05's post-seek corroboration
(`synccore.cpp:787-847`) or the CTL-02 persistence gate.

## Artifacts

- Harness: `scratchpad/ctl06-w3/repro.cpp` (build command in header —
  two-file clang++ compile, no CMake; `SyncEstimator` has no other deps).
- Orchestrator's independent rebuild: `build/ctl06-w3/repro-verify.exe`,
  output identical at the terminal fix and starvation probe.
