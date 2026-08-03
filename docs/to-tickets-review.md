# to-tickets review — Epic 9: DSP & Probe Upgrades · 2026-08-03

**Status: tickets only.** Six tickets added to `backlog-tickets.md`'s Epic 9
(status-table rows + ticket bodies), decomposing tech-req §2.10–§2.12
(promoted to spec by `docs/to-spec-review.md`) into implementable work.
Nothing in this pass touches code, tests, or build files — that's
`/implement`'s job, per the standing JTP workflow (spec → tickets →
implement, orchestrator-verified Sonnet subagents).

## Tickets

- **DSP-01a** — `core/src/dsp/oss_ring.h/.cpp`: incremental onset-strength
  ring + on-demand autocorrelation tempogram (`BeatEstimate{period_ms,
  salience, stable}`), per §2.10. Fixed allocation after init; synthetic
  click-track tests incl. octave-ambiguity and no-beat cases; γ=100 and the
  0.5 harmonic weight land as named, explicitly-provisional constants.
- **DSP-01b** — Wires `OnsetStrengthRing::push` into the worker drain loop
  (same tap as `append_history`) and `estimate_beat_period()` onto the
  `kSampleLatencyResidual` cadence; implements the §2.8 cross-check against
  `WindowLag.second_lag_ms`; adds `lag_analyzer --tempo`'s additive
  `beat_period_ms` column; documents (does not build) the MHT hypothesis-bank
  seeding contract.
- **DSP-02a** — Adds `analyze_window`'s trailing defaulted `whiten_beta =
  0.5` parameter and the non-default β-PHAT branch, per §2.11's
  non-negotiable byte-identical rule for the default path; `lag_analyzer
  --beta` tooling in both file and `--stream` modes with an additive `beta`
  CSV column.
- **DSP-02b** — Runs the β ∈ {0.5, 0.6, 0.7, 0.8} corpus sweep
  (`docs/sync-test-results.md` + FT8 captures) using DSP-02a's tooling;
  deliverable is sweep data plus a written promotion *recommendation* in
  `docs/` — not a default-value change.
- **DSP-03a** — `SC_EVT_ACTIVE_DUCK` / `sc_evt_active_duck_t` /
  `sc_notify_duck_executed` ABI additions (incl. the required
  `abi_c_check.c` switch-case + contract coverage); worker-side matched-filter
  capture-energy dip detector; `CorrectionPolicy::on_duck_result` verdict
  bands; duck-first/pause-escalation trigger composition, per §2.12.
- **DSP-03b** — Kotlin/JNI actuator: `SessionViewModel` handler for
  `Event.ActiveDuck`, bounded-coroutine `AudioManager` STREAM_MUSIC duck with
  achieved-dB echo, same shell gates and no-free-running-loop discipline as
  CTL-01b's pause-probe handler.

## Dependency chain

```
DSP-01a ─▶ DSP-01b                                                   (§2.10)

DSP-02a ─▶ DSP-02b ─▶ [future spec amendment] ─▶ [future default-flip ticket]   (§2.11)

CTL-01a (landed) ─▶ DSP-03a ─▶ DSP-03b ─▶ [CTL-01 device-pass gate on
                                            duck-as-default-tier promotion]     (§2.12)
```

The three chains touch disjoint files (`core/src/dsp/oss_ring.*` vs.
`core/src/dsp/lag_window.*` + `core/tools/lag_analyzer.cpp` vs.
`core/src/policy/policy.*` + ABI headers + the Android shell) and carry no
ordering dependency on each other — they can implement in parallel.

## Wording decisions

1. **DSP-02b's no-silent-flip constraint.** §2.11 is explicit that the
   on-device default flip is a *future* spec section's decision, gated on
   the sweep's promotion criteria (no lag flips/`found` regressions on
   healthy locks; measurable reverberant-window gains). To make sure this
   ticket can't quietly become the default-flip vehicle, the AC states
   outright that **the ticket's own diff must contain zero changes to any
   default parameter value, `PolicyConfig` field, or `analyze_window`'s
   default argument**, and that the deliverable's *recommendation* is framed
   as "open a future spec amendment," never as an instruction that changes
   behavior. A follow-on default-change ticket is named as a distinct,
   not-yet-created future item in the dependency chain rather than folded
   into DSP-02b's scope.
2. **DSP-03a's dependency on CTL-01a.** CTL-01a/CTL-01b are already
   ✅ Done, so DSP-03a doesn't block on unstarted work — but it composes
   directly with `CorrectionPolicy`'s existing sentinel/probe triggers and
   `sc_event_type_t` enum (appending at the end, after
   `SC_EVT_ACTIVE_PROBE`), so CTL-01a is listed as a dependency the same way
   CAL-08/CAL-09/CAL-10 list already-landed CAL-04 as theirs — it documents
   the coupling, not a scheduling gate.
3. **DSP-03b's field-sequencing note is carried as a caveat, not a
   dependency-blocking gate.** §2.12 says the CTL-01 device pass (still
   pending — see CTL-01's 🟡 status row) must complete before the duck
   becomes the *default* probe tier, but it doesn't say DSP-03b's
   implementation must wait for that pass to *land the mechanism*. The
   ticket's Dependencies line is kept to `DSP-03a` (the actual code
   prerequisite), with the §2.12 sequencing constraint restated verbatim as
   a separate note so the distinction between "may be implemented now" and
   "may not become the shipped default yet" isn't lost.
4. **DSP-01a/01b split mirrors CTL-03a/03b** (DSP-module vs. CLI/consumer
   wiring) rather than CAL-02/CAL-03's shared-helper-then-ABI shape, since
   §2.10 has no C-ABI surface at all (the MHT bank, the only public
   consumer, is explicitly out of scope) — the natural seam is "the ring and
   its math" vs. "wiring it into the worker loop and the CLI."
5. **No ticket added for the MHT hypothesis bank itself.** §2.10 and the
   task brief are explicit that the bank is future, separately-specced work;
   DSP-01b's AC requires the seeding *contract* to be documented in code
   comments but forbids any hypothesis-bank code from landing under this
   ticket, so a reviewer can't accidentally treat partial bank code as
   in-scope.

## Ambiguities resolved

- **§2.10's "worker-thread realtime-product rule, same as the policy
  rings"** (task brief wording) doesn't name a single canonical example in
  the spec text; DSP-01a's AC points at `CorrectionPolicy`'s existing fixed
  rings (CTL-02a's error ring, CTL-01a's referee ring) as the precedent,
  since those are the shipped instances of "fixed-size, worker-thread-only,
  zero-allocation-after-init" in this codebase.
- **DSP-03a's "Dependencies: none" vs. CTL-01a.** The task brief's ticket
  text for DSP-03a doesn't state a Dependencies line explicitly (only
  DSP-03b's brief says "Dependencies: DSP-03a"). Given DSP-03a's own
  description composes directly with `on_referee_window`/`on_tick` and
  appends to the existing `sc_event_type_t` enum, CTL-01a was added as its
  dependency rather than leaving it dependency-free — consistent with how
  every other ABI/policy-composing ticket in this file (e.g. CAL-03 → CAL-02)
  names its concrete code coupling.
