# References — Literature Behind SyncCore's DSP and Control Loop

This is a bibliography, not a replacement for the research: it summarizes
what two research passes found and where each finding actually landed in
the codebase. For the full retrieval trail, mechanism-by-mechanism mapping
against measured field failures, and the parts of each paper that do *not*
apply to this codebase, read the source documents themselves:

- [`docs/research-offset-disambiguation.md`](research-offset-disambiguation.md) — Wang (2003) and Gururani & Lerch (ISMIR 2017) against the harmonic-comb / self-match / deadband failure classes.
- [`docs/research-closed-loop-control.md`](research-closed-loop-control.md) — event-triggered control, NTP clock discipline, PDA/MHT data association, dual control, Smith predictor, and IDMS/adaptive-playout against the control-loop failure modes.

**Retrieval-status convention.** Both research docs are scrupulous about
what was actually read versus inferred, and this bibliography carries that
distinction forward without laundering it. A citation below is marked:

- **Read in full** — the primary source itself was retrieved and read cover to cover (page count given).
- **Partially retrieved** — part of the primary was read (pages given); the rest was paywalled and is named as a gap, not filled in from memory.
- **Unreachable — substituted** — the requested primary could not be retrieved by any free channel tried; a specific substitute that *was* read is named, with its own status.

Where a mechanism has no shipped code yet (the planned MHT hypothesis bank,
the probe-magnitude formulas), that is stated plainly rather than implied.

---

## Read in full

### 1. Avery Li-Chun Wang, "An Industrial-Strength Audio Search Algorithm," Shazam Entertainment Ltd., Proc. ICMIR 2003, pp. 7–13

**Status: read in full** (7 pp., all text/figures legible), retrieved from
`https://www.ee.columbia.edu/~dpwe/papers/Wang03-shazam.pdf`
(research-offset-disambiguation.md §5, source 1).

**Application in this codebase.** §2.3.1's scoring logic — a match's
strength judged by the dominant peak *relative to competing peaks*, not the
raw peak alone — is implemented as `WindowLag.comb_ratio` in
`core/src/dsp/lag_window.h`/`.cpp`: the best autocorrelation peak divided by
the strongest peak outside a ±20 ms exclusion band. Specified in
`technical-requirements.md` §2.8 Part A, shipped under ticket CTL-03a
(commit `9237e3a`, per `docs/ctl03-review.md`). Wang's §3.1 "transparency"
property (self-fingerprinting a known signal inside a mixed capture) was
also evaluated as a direct self-match detector for CTL-01, but is
**blocked**: this app has no reference PCM of Spotify's own rendered audio
(no `AudioPlaybackCapture`/`Visualizer` access confirmed, no decoded copy
available — the same gap that invalidated an earlier `sc_push_reference`
cross-correlation design, `technical-requirements.md` §2.6). The buildable
substitute actually shipped instead is the active probe
(`technical-requirements.md` §2.9, CTL-01, commit `7d0cc28`).

### 2. RFC 5905, "Network Time Protocol Version 4: Protocol and Algorithms Specification," IETF, June 2010

**Status: read in full** (241 KB plaintext, ~6,163 lines), retrieved via
direct `curl` of `https://www.ietf.org/rfc/rfc5905.txt` after WebFetch's
HTML pipeline truncated before reaching Appendix A/Figures 27–28
(research-closed-loop-control.md §6, source 3).

**Application in this codebase.** Two independent mechanisms:

- The clock filter's persistence-before-acting discipline (§10–11, Figure
  28: a single sample over the step threshold moves to state SPIK and
  waits; only persistence past the WATCH stepout window earns a step) is
  the design basis for `technical-requirements.md` §2.7's persistence gate
  — `CorrectionPolicy`'s residual ring + `confirm_min_fixes`/
  `confirm_window_ns`/`confirm_agree_ms`/`confirm_floor_ms` in
  `core/src/policy/policy.h`/`.cpp`, ticket CTL-02 (commit `5f03d08`,
  `docs/ctl02-resumption-review.md`). `confirm_floor_ms = 125` is a
  **deliberate resolution** of an inconsistency inside RFC 5905 itself:
  Figure 27's parameter table lists `STEPT 125` while Appendix A.5.5.6's
  reference pseudocode defines `#define STEPT .128` — 125 ms in prose, 128
  ms in code, unresolved in the RFC text. `technical-requirements.md` §2.7
  picks the table's 125 on purpose and states why.
- The SPIK-state single-spike-suppression rule ("a single spike greater
  than the step threshold is always suppressed") is the NTP grounding for
  `technical-requirements.md` §2.8 Part B's large-correction corroboration
  hold (`large_correction_threshold_ms`/`large_corroborate_agree_ms` in
  `policy.h`), ticket CTL-03b (commit `9237e3a`), with a two-fix
  confirmation standing in for NTP's wall-clock WATCH stepout since this
  system has no free-running clock to wait out.
- The **popcorn spike suppressor** (Appendix A.5.5, a second, faster,
  jitter-relative gate — SGATE=3× running jitter, time-windowed) is noted
  in the research but **not implemented**. The clock-filter-style sample
  pipeline it belongs to remains open, named as composite-controller item
  2 in `research-closed-loop-control.md` §5.

### 3. W.P.M.H. Heemels, K.H. Johansson, P. Tabuada, "An Introduction to Event-Triggered and Self-Triggered Control," Proc. IEEE 51st CDC, Maui, 2012, pp. 3270–3285

**Status: read in full** (16 pp., all text/figures/tables legible), via
direct `curl` of `https://kth.diva-portal.org/smash/get/diva2:586391/FULLTEXT02`
after WebFetch timed out (research-closed-loop-control.md §6, source 2).
Retrieved as the reachable primary tutorial after K.J. Åström & B.
Bernhardsson's "Comparison of Riemann and Lebesgue Sampling for First Order
Stochastic Systems" (CDC 2002) proved unreachable through every free
channel tried (IEEE Xplore paywalled, Lund research-portal abstract-only,
all guessed file paths returned HTML shells) — Heemels et al. is itself a
primary source, not a weak stand-in, and its own bibliography cites and
characterizes the 1999 IFAC predecessor of the Åström–Bernhardsson result.

**Application in this codebase.** Donkers & Heemels's mixed
relative-plus-absolute event-triggering condition (§V, Eq. 25:
‖e‖² = σ‖v‖² + ε) and its stability result (§V.C: LMI feasibility depends
only on the relative term σ; the absolute floor ε affects only event count
and ultimate-bound size) is the formal license for
`technical-requirements.md` §2.7's design — a corroboration-scaled term
layered *above* a fixed floor (`confirm_floor_ms`, `deadband_ms`) without
reopening the stability question the field-measured deadband-150 churn
experiment closed. Shipped as part of CTL-02 (`policy.h`/`.cpp`, commit
`5f03d08`).

### 4. RFC 7272, "Inter-Destination Media Synchronization (IDMS) Using the RTP Control Protocol (RTCP)," IETF, June 2014

**Status: read in full** (43 KB plaintext, 1,291 lines), via direct `curl`
of `https://www.rfc-editor.org/rfc/rfc7272.txt` after WebFetch's HTML
pipeline returned only fragmentary summaries (research-closed-loop-control.md
§6, source 12). Included here even though the original brief for this
bibliography omitted it, because a research doc that reads a source in full
belongs in the record it summarizes.

**Application in this codebase.** §4's tutorial names two client
strategies for reaching a distributed playout target: continuous rate-slew
(does not apply — this app has no continuous playback-rate control, only
discrete seeks), and "simply pause playback until it catches up." The
second is the step-compatible branch, and it validates — rather than
introduces — a pattern already shipped for a different purpose: the
end-of-track pause guardian (`docs/field-test-8-results.md` Test 3). No new
ticketed mechanism traces to this source; it is corroborating literature
for an existing design choice, and its §8 numeric tolerance ("hundreds of
milliseconds" for social-TV-class sync) is a citable anchor for this
project's own deadband order of magnitude.

### 5. Michael Grinberg, "Data Association for Multi-Target-Tracking," Vision and Fusion Laboratory, Karlsruhe Institute of Technology, Technical Report

**Status: unreachable — substituted; substitute read in full.** The two
primaries this entry was meant to cover are **both unreachable/paywalled**:
Y. Bar-Shalom & E. Tse, "Tracking in a Cluttered Environment with
Probabilistic Data Association," *Automatica* 11 (1975), 451–460 (Elsevier
paywall, no free copy via ResearchGate/Academia.edu/Semantic Scholar); and
D.B. Reid, "An Algorithm for Tracking Multiple Targets," *IEEE Trans.
Automatic Control* AC-24 (1979), 843–854 (IEEE Xplore paywall, no free
copy). The substitute actually read is Grinberg's 22-page technical report,
which explicitly re-derives the PDA equations from Bar-Shalom's own
formulation with full citation to both unreachable papers — a genuine
secondary treatment, not a blog post or slide deck, and it is treated as
secondary throughout (research-closed-loop-control.md §6, source 6).

**Application in this codebase.** The PDA/Mahalanobis χ² gating equation
(Grinberg §3.2, Eq. 3.2: a validation gate sized from a chi-square quantile
for a target false-exclusion probability, applied against the *current*
posterior covariance) informs the **planned** multi-hypothesis-tracking
(MHT) bank for comb-ambiguity resolution — a small set of parallel
`SyncEstimator` instances seeded at comb-implied candidate offsets, gated
and pruned by this rule instead of the fixed `outlier_gate_ms`. **No code
has shipped for this.** It is deferred, tracked as "MHT deferred — needs
the beat-period seeding decision" in `docs/ctl03-review.md` and
`docs/ctl01-review.md`, and its own prerequisite (a tempo/beat estimate to
seed candidate spacing) is an explicitly unmet research gap
(research-offset-disambiguation.md §3 item 7). It is also **explicitly
scoped away from the self-match problem**: PDA's clutter model assumes
independent, identically-distributed clutter, which self-match's
structured, self-correlated, better-fitting "clutter" violates by
construction — applying this machinery there would make CTL-01's failure
worse, not better (research-closed-loop-control.md §2(iii)/§4 item 3).

### 6. Siddharth Gururani & Alexander Lerch, "Automatic Sample Detection in Polyphonic Music," Proc. 18th ISMIR Conference, Suzhou, China, Oct. 23–27 2017, pp. 264–271 (ISMIR archive paper 118)

**Status: read in full** (8 pp., all text/figures legible), retrieved from
`https://archives.ismir.net/ismir2017/paper/000118.pdf`
(research-offset-disambiguation.md §5, source 2). Included here even though
the original brief for this bibliography omitted it, for the same
completeness reason as RFC 7272 above.

**Application in this codebase.** Not the paper's NMF/PFNMF/subsequence-DTW
machinery itself — flagged as too computationally expensive for the phone
CPU budget (12 pitch-shift hypotheses × iterative factorization × DTW per
candidate, versus this core's closed-form FFT operations). What transfers
is the **pattern**: their subsequence-DTW keeps every backtracking
candidate alive rather than committing to one alignment, exporting survival
counts and path features for a downstream adjudicator. This is the same
multi-hypothesis-survival pattern behind the **planned, unshipped** MHT
bank described under Grinberg (entry 5, above) —
`research-offset-disambiguation.md` §4 item 3 names Gururani & Lerch as the
citable precedent for that direction. No code has shipped from this source.

### 7. Y.J. Liang, N. Färber, B. Girod, "Adaptive Playout Scheduling and Loss Concealment for Voice Communication Over IP Networks," IEEE Trans. Multimedia 5(4) (2003), 532–543

**Status: read in full** (12 pp., all figures/tables legible), via
`https://web.stanford.edu/~bgirod/pdfs/LiangMM2003.pdf`
(research-closed-loop-control.md §6, source 13).

**Application in this codebase.** §IV.A's order-statistics-adaptive
playout deadline (sorted recent delays, extended with an estimated
worst-case tail, Eq. 5–8 — the threshold widens or narrows with recently
observed scatter rather than sitting at one hand-tuned constant) is the
scatter-adaptive **framing** cited in `technical-requirements.md` §2.7's
design-basis paragraph for the persistence gate's `confirm_agree_ms = 60`
constant — sized by the same logic (separating a stable, low-scatter
residual cluster from a scattered, multimodal one) though implemented as a
fixed field-derived value, not a running order statistic. The paper's
headline mechanism, single-packet WSOLA time-scale modification, is
**explicitly not applicable**: it requires PCM access to the audio being
played, sample-by-sample, which this app does not have — the identical
phantom-reference gap that blocks Wang's self-fingerprinting (entry 1,
above; `research-offset-disambiguation.md` §2b's `sc_push_reference`
correction).

---

## Partially retrieved

### 8. Björn Wittenmark, "Adaptive Dual Control," in *Control Systems, Robotics and Automation*, Vol. X, EOLSS/UNESCO

**Status: partially retrieved — pp. 1–6 of 11.** §1 ("dual-goal" framing)
and §2 ("Stochastic Adaptive Control," incl. the certainty-equivalence vs.
cautious-controller worked example) were read via direct `curl` of
`https://www.eolss.net/sample-chapters/c18/e6-43-15-06.pdf`. §3 "Optimal
Dual Controllers," §4 "Suboptimal Dual Controllers" (§4.1 "Perturbation
Signals" specifically — the concrete probing-magnitude formulas), and §5
"When To Use Dual Control?" are **paywalled** behind EOLSS's sample-chapter
gate and were not read (research-closed-loop-control.md §6, source 8). The
paper this entry was originally meant to cover — A.A. Feldbaum, "Dual
Control Theory I–IV," *Automation and Remote Control* 21–22 (1960–61) — is
**wholly unreachable**: no free digitized copy of the translated Soviet-era
journal was found through any channel tried.

**Application in this codebase.** The "turn-off phenomenon" (§2, prose
following Eq. 7: a cautious, non-dual controller facing rising parameter
uncertainty responds by acting *less*, starving the estimator of the
excitation it needs, a self-reinforcing silence) is the literature-grounded
trigger condition for `technical-requirements.md` §2.9's active-probe
scheduling — `policy.on_tick`, `probe_turnoff_dwell_ns = 20 s`, firing a
probe when confidence has sat below `min_confidence_to_correct` with no
accepted fix in that span. Shipped under ticket CTL-01 (`policy.h`/`.cpp`,
commit `7d0cc28`, `docs/ctl01-review.md`). **The probe-magnitude formulas
were never retrieved** (§4.1 is exactly the paywalled section) — the
shipped `probe_pause_ms = 200` default is a field-tunable heuristic, not a
value derived from this or any retrieved source; `docs/ctl01-review.md`
names 300–400 ms as the adjustment knob if field verdicts flap.

### 9. Hang C.C., "Smith Predictor and Its Modifications," in *Control Systems, Robotics, and Automation*, Vol. II, EOLSS/UNESCO

**Status: partially retrieved — pp. 1–6 of 18.** §1 "Introduction" and §2
"Controller design" (incl. Eq. 1–10, the delay-mismatch resonance result)
were read via direct `curl` of
`https://www.eolss.net/sample-chapters/c18/E6-43-03-05.pdf`. §3
"Performance comparison," §4 "Modification for high order systems," §5
"Modification for rapid load rejection," and §6 "Modifications for
open-loop unstable systems" are **paywalled** and were not read
(research-closed-loop-control.md §6, source 10). The paper this entry was
originally meant to cover — O.J.M. Smith's 1957/1959 original — is
**wholly unreachable**, and the research uncovered a **citation-year
discrepancy** worth carrying forward rather than silently resolving: the
commonly cited title is "Closer Control of Loops with Dead Time," *Chem.
Eng. Progress* (1957), but Hang's own bibliography instead names "A
Controller to Overcome Dead-Time," *ISA Journal* 6(2) (1959), 28–33, as
"the first classical paper." Neither 1950s process-control-journal paper
was located in any free digital archive.

**Application in this codebase.** Eq. 10's finding — that an uncorrected
delay/rate-estimate mismatch under a Smith-predictor-style feedback
structure produces an internal **resonant** loop (H(s) =
1 + e^(−s(L+ΔL)) − e^(−sL), with resonance peaks, not just a bounded bias)
— is the caution behind retaining `latency_adapt_clamp_ms = 500.0` in
`core/src/policy/policy.h` (line 59), the bound on how much a single fix
can move the online-learned command latency. Field-test-8's 1259 ms
overshoot from a single conf-0.74 fix is read as an instance of exactly the
failure mode this equation predicts (research-closed-loop-control.md §3,
Smith-predictor bullet), and is cited as guard rationale for
`technical-requirements.md` §2.8's large-correction corroboration hold
(CTL-03b). No literal Smith predictor is or could be implemented here —
there is no continuous control signal for an inner-loop model to filter,
since every actuation is a discrete seek — only the resonance lesson
transfers.

---

## Summary table

| # | Source | Status | Shipped mechanism / location | Ticket |
|---|---|---|---|---|
| 1 | Wang (2003) | Read in full | `WindowLag.comb_ratio`, `lag_window.h`/`.cpp` | CTL-03a |
| 2 | RFC 5905 (2010) | Read in full | Persistence gate `confirm_floor_ms` (`policy.h`); SPIK hold (`policy.h`) | CTL-02, CTL-03b |
| 3 | Heemels, Johansson & Tabuada (2012) | Read in full | Floor-plus-corroboration framing (`policy.h`) | CTL-02 |
| 4 | RFC 7272 (2014) | Read in full | Corroborates existing end-of-track pause guardian | — (no new code) |
| 5 | Bar-Shalom & Tse (1975) / Reid (1979), via Grinberg | Unreachable — substituted (substitute read in full) | Planned MHT hypothesis bank | **unshipped** |
| 6 | Gururani & Lerch (ISMIR 2017) | Read in full | Planned MHT hypothesis bank (pattern only) | **unshipped** |
| 7 | Liang, Färber & Girod (2003) | Read in full | Design rationale for `confirm_agree_ms` (`policy.h`) | CTL-02 |
| 8 | Feldbaum (1960–61), via Wittenmark (EOLSS) | Unreachable — substituted (substitute partially retrieved, pp. 1–6/11) | Probe turn-off trigger (`policy.h`, `probe_turnoff_dwell_ns`) | CTL-01 |
| 9 | O.J.M. Smith (1957/1959), via Hang C.C. (EOLSS) | Unreachable — substituted (substitute partially retrieved, pp. 1–6/18) | Guard rationale for `latency_adapt_clamp_ms` (`policy.h`) | CTL-03 (guard) |
