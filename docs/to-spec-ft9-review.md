# To-Spec Review — Field Test 9 → technical-requirements.md §2.13–§2.16 · 2026-08-04

**Scope.** Four new, additive sections were appended to `technical-requirements.md` (§2.13–§2.16), promoting field test 9's three real-world findings plus the Epic 10 MHT design from `docs/field-test-9-results.md` and the two prior research docs (`docs/research-closed-loop-control.md`, `docs/research-offset-disambiguation.md`). No existing spec text was edited. No code was changed. `backlog-tickets.md` was not touched. This document summarizes each section for PM review.

---

## §2.13 — Self-Initiated Playback & Auto-Advance Guardian Suppression

**FT9 evidence.** Test 2 (Billie Jean): "12 real `play(uri)` restarts vs. 6 correctly-guarded ... resumes." A concrete 3-restart-in-2.8s oscillation is logged (`0fHbLv7...` → `6vR5u5b8...` → `0fHbLv7...`, `11:55:30.463`–`11:55:33.302`), and the guardian fires on our own mid-oscillation `play()` at `11:55:31.673` ("Spotify auto-advanced to spotify:track:0fHbLv7QZDpD2tHqzxOg1e — pausing to hear the room"), stacking an extra pause/re-listen cycle on top of the churn already in progress.

**Mechanism (2–3 sentences).** `handlePlayerState` today compares `state.trackUri` only against `_syncState.value.track?.spotifyUri` — the single latest resolved URI — so a late player-state confirmation for an OLDER self-issued `play(uri)` gets misread as an unprompted advance once a newer resolution has superseded it in shared state. The fix is a bounded, time-boxed **set** ("latch") of recently self-issued URIs, checked by set-membership (not by a blanket timer) before the guardian fires, so it can only suppress additional false positives and never masks a genuine advance to a URI we never issued.

**New knobs.**

| Knob | Default | Layer |
|---|---|---|
| `self_play_latch_window_ms` | 5000 | Kotlin, `SessionViewModel` |
| `self_play_latch_max_entries` | 4 | Kotlin, `SessionViewModel` |

**Open questions for PM.** None raised in this section specifically — the mechanism is a strict, conservative narrowing of an existing false-positive path.

---

## §2.14 — Post-Lost / Aim-Failure Match Corroboration

**FT9 evidence.** Test 3 (Dreams [Extended]): after `aim gave up after 4 attempts` and a garbage `sync err=763715ms` reading, five ACR "Everywhere" matches arrived over ~17 s (`12:15:06.829`–`12:15:23.112`); the first three were correctly rejected `SELF_HEARING` (`zEnd` 233/213/203 ms), but the last two were accepted once `zEnd` fell to 54/44 ms, leading to `Spotify connected → play spotify:track:0CQ2EPgBXhJEnTaxbb4rWt` — a restart to the wrong song. FT9's own read: the defect isn't misrecognition, it's that "a sustained run of low-margin matches ... was eventually accepted once it became self-consistent rather than independently corroborated against the room."

**Mechanism.** Because track identity (URI) is a shell-only concept SyncCore never sees, this is a shell-side (`SessionViewModel`) gate: after a `kTrackLost` re-listen or an aim-failure (both now force the same LOST→LISTENING→MATCHING re-bootstrap), every recognizer result — including ones §7.3's CORE-06 guard independently rejected — is checked against a same-URI streak whose offsets progress with elapsed wall-clock time; only once `ident_confirm_min_fixes` (3) agree is the shell allowed to resolve/actuate on the identity, using the newest evidence. This runs downstream of and independent from §7.3's own per-fix self-match check — it doesn't loosen §7.3, it adds a second axis (cross-fix wall-clock agreement) that doesn't depend on JTP's own possibly-unreliable position estimate.

**New knobs.**

| Knob | Default | Layer |
|---|---|---|
| `ident_confirm_min_fixes` | 3 | Kotlin, `SessionViewModel` |
| `ident_confirm_offset_agree_ms` | 500 | Kotlin, `SessionViewModel` (reuses core's `kRoomContinuityGateMs`) |
| `ident_corrob_max_age_ms` | 30000 | Kotlin, `SessionViewModel` |

**Known limitation (flagged in-section).** All seven "Everywhere" matches in the FT9 episode agreed with each other and with wall-clock time — the failure was internally self-consistent. N-of-M agreement raises the cost of this exact failure (3–5 samples of exposure vs. 1–2) but does not, by itself, prove it can't still be passed by an equally well-behaved wrong match. No retrieved source (Wang03, Gururani & Lerch) offers a stronger mechanism for this specific case.

**Open questions for PM.**
1. Should expiry of the corroboration window (no identity reached within 30 s) surface distinct UI copy, or stay silent inside ordinary `MATCHING`?
2. Should track-lost and aim-failure arm the same thresholds, or does aim-failure (which coincides with a known-unreliable local estimate) warrant a stricter bar?
3. Is the "known limitation" above an acceptable interim bar, given no available mechanism fully closes it?

---

## §2.15 — Convergence Settling Hysteresis

**FT9 evidence.** Test 2's correction table: `11:57:26.885 e=633`, `11:58:14.260 e=150` (CTL-02 gate), `11:58:18.343 e=542`, `11:58:22.419 e=547`, `11:59:19.523 e=87` (CTL-02 gate). Human report: "3–4 corrections then an interfering 5th"; FT9's own read attributes this to one of the instantaneous corrections (633/542/547 ms) "landing while the audible impression was already close." Test 1 (Vienna) supplies the contrast: `trim=+585 ms` held "flat at 43 ms — at floor," while `trim=0 ms` "never stabilized before the room went quiet" (43–52 ms interleaved with 1257/1639/2361 ms harmonic-multiple readings).

**Mechanism.** Once a correction lands at or below `settle_enter_threshold_ms` (150 ms) and the existing post-seek settle/verify window confirms it, the policy enters a **settled** state that raises the bar for every further correction proposal — including the instantaneous path — to a stricter persistence-style test (`settled_confirm_min_fixes`=5, `settled_confirm_agree_ms`=40 ms, both tighter than §2.7's defaults). A fresh error at/above the existing `large_correction_threshold_ms` (1000 ms) or `lost_threshold_ms` (2000 ms) exits settled immediately, so genuine perturbations are never slowed.

**New knobs.**

| Knob | Default | Layer |
|---|---|---|
| `settle_enter_threshold_ms` | 150.0 | `PolicyConfig` (core) |
| `settled_confirm_min_fixes` | 5 | `PolicyConfig` (core) |
| `settled_confirm_agree_ms` | 40.0 | `PolicyConfig` (core) |

**Known limitation (flagged in-section).** Entry requires a correction to have already landed at floor and settled — the mechanism cannot retroactively protect the `633/542/547 ms` corrections that fired while Billie Jean's beat-comb churn (§2.16) was still actively unresolved; it targets the stable, already-converged case (Test 1's Vienna plateau) and the short window right after a gate-firing, not general mid-recovery churn.

**Open questions for PM.** None raised structurally, but note: the mapping from FT9's exact timeline to "which correction this mechanism would have held" is this document's own design analysis, not something the FT9 doc itself asserts — worth a follow-up field test that isolates a clean post-gate-firing window to confirm.

---

## §2.16 — Multi-Hypothesis Tracking (MHT) Bank — Epic 10, NOT scheduled

**FT9 evidence.** Test 2 (Billie Jean): ACRCloud matched at least 6 distinct Spotify catalog editions across ~134 phase transitions; mic autocorrelation alternated between a 40–64 ms low mode and near-integer multiples of `beat_period_ms` (~512–516 ms) — 1025/1026 (~2×), 1637–1661 (~3.2×), 2046–2133 (~4×) — with `comb_ratio` "mostly 1.0–1.7 (near-flat/ambiguous)."

**Correction made during drafting.** The originating task brief cited "comb_ratio 4.3 during the pre-seek plateau" as the comb-aliasing evidence for this section. Per the FT9 doc, comb_ratio 4.3 actually belongs to Test 3's Dreams plateau, which the doc explicitly calls "high confidence, not the ambiguous ~1.0 seen with Billie Jean" — i.e. the *opposite* of ambiguity. §2.16 as written uses Billie Jean's correct 1.0–1.7 reading as its evidence and knob-sizing basis instead, and flags this correction explicitly in-section (matching the house style's existing "Correction to the original brief" pattern used in §2.10/§2.11).

**Mechanism.** A small, bounded bank of parallel `SyncEstimator` instances seeded at `fix_offset ± k·beat_period_ms` (k=1..3), instantiated only when §2.10's beat estimate is stable and §2.8's `comb_ratio` reads ambiguous; each hypothesis carries an IPDA-style existence probability, admission is gated by a per-hypothesis χ² test (replacing the single fixed `outlier_gate_ms`), and the policy actuates only off the dominant hypothesis once its existence probability clears a threshold — never a soft blend of several. Explicitly and permanently excluded from self-match: research-closed-loop-control.md is direct that routing self-match through PDA-style blending would make it worse, not better, since self-match "clutter" is self-correlated and anomalously clean.

**New knobs (all provisional — Epic 10, not field-validated).**

| Knob | Default | Layer |
|---|---|---|
| `mht_max_hypotheses` | 4 | `PolicyConfig`/estimator (core, future) |
| `mht_seed_k_max` | 3 | `PolicyConfig`/estimator (core, future) |
| `mht_warrant_comb_ratio_max` | 1.7 | `PolicyConfig`/estimator (core, future) |
| `mht_warrant_requires_stable_beat` | true | `PolicyConfig`/estimator (core, future) |
| `mht_existence_prune_floor` | 0.05 | `PolicyConfig`/estimator (core, future) |
| `mht_existence_actuate_threshold` | 0.75 | `PolicyConfig`/estimator (core, future) |

**Open questions for PM.**
1. This section is explicitly not scheduled (Epic 10). Does it need a placeholder ticket now, or does it wait until DSP-01b (the beat tracker) ships and is field-validated first?
2. The knob defaults are provisional/unvalidated by design (no corpus run exists for this mechanism yet) — confirm that's acceptable for a design-only section, versus needing a "TBD, pending corpus" placeholder instead of concrete-looking numbers.

---

## Cross-cutting notes

- All four sections were checked against the C-ABI-immutability rules already established in the spec (additive-only, enums appended at end, no struct reordering); §2.13/§2.14 make no ABI change at all (both are shell-only), §2.15 is a `PolicyConfig`-only addition (no ABI surface), and §2.16 explicitly defers any ABI question to its own future, separately-scheduled ticket.
- §2.14 is the one section that changes existing shell *behavior*, not just adds new state: it requires `aimUntilLanded`'s "aim gave up" path to now force the same LOST→LISTENING→MATCHING re-bootstrap `onTrackLost()` already performs, where today it silently continues to `CONVERGING`. This is flagged explicitly in the section text and should get its own explicit sign-off before ticketing, since it's a behavior change to an existing code path, not a purely additive one.
- Every new knob follows `policy.h`'s snake_case/`_ms`/`_ns` naming convention per the brief's instruction, even for the two shell-only sections (§2.13, §2.14) where the actual Kotlin realization will be a `SCREAMING_SNAKE_CASE` companion constant — mirroring the existing `ENGINE_DEADBAND_MS` ↔ `deadband_ms` precedent.
