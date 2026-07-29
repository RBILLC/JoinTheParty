# CTL-03 review — comb-flatness score + large-correction corroboration · 2026-07-29

**Status: landed and verified in commit `9237e3a`** (spec `technical-requirements.md`
§2.8, tickets CTL-03a/03b in backlog Epic 8). Same workflow as CTL-02:
`/to-spec → /to-tickets → /implement` with Sonnet subagents, every stage
verified first-hand by the orchestrator before commit.

## How the ambiguity score is extracted (CTL-03a)

`analyze_window` (`core/src/dsp/lag_window.{h,cpp}`) already computes the
full autocorrelation of an 8 s capture window before reducing it to an
argmax. CTL-03a adds a **second, wholly additive pass** over that same
array: it finds the strongest value *outside* a ±20 ms exclusion band
around the best lag (so the best peak's own shoulder never scores as its
competitor) and reports:

- `WindowLag.second_lag_ms` — where the runner-up tooth sits (0 = no
  candidate outside the exclusion);
- `WindowLag.comb_ratio` — best ÷ runner-up. High (selftest's clean
  two-copy fixture reads ~31) means one unambiguous copy-lag; ~1.0–1.5
  means a flat comb of near-equal teeth — the Billie Jean class. Consumers
  threshold at roughly 2.0. Unclamped; 0/negative reads "no meaningful
  competitor."

The graded path — argmax, `peak_ratio` (max/mean), `found` — is
**byte-identical**, honoring the header's "do not improve the math without
re-running the field-test corpus" warning; all pre-existing lag-window
tests and `lag_analyzer --selftest` pass unmodified. `lag_analyzer` appends
`comb_ratio` as the **last** CSV column in both file and `--stream` modes,
so the field rig sees ambiguity live and positional parsers don't break.

Honesty note (per §2.8): recognition fixes (ACRCloud) never flow through
`analyze_window`, so this score has no live correction-path consumer yet —
it is field-rig diagnostics today and the seeding/validity input for
CTL-01 and the MHT hypothesis bank later. The runtime defense against the
1259 ms class is the policy hold below.

## How two-fix corroboration works (CTL-03b)

FT8's 1259 ms overshoot came from a single conf-0.74 fix. The estimator's
own outlier gate (`outlier_gate_ms=1200`) already demands a repeated jump —
but only when the filter is confident (`outlier_gate_max_p00`); at
mid-uncertainty a wild single fix lands unchallenged, by design. CTL-03b
closes that gap one layer up, in `CorrectionPolicy`:

1. A proposed seek with |error| ≥ `large_correction_threshold_ms` (1000,
   below `lost_threshold_ms`=2000 which keeps checked-first precedence) is
   **held** as a pending `{error, timestamp}` record — nothing fires.
   Fix cadence tightens to 8 s so the verdict arrives fast.
2. The next fresh fix **fires the seek from its own error** if it agrees
   with the pending record within `large_corroborate_agree_ms` (150 — not
   the ~50 first suggested: FT2 measured ±100–150 ms single-fix noise, and
   a 50 ms gate would starve real large corrections; deliberate deviation,
   field-tunable) and is still ≥ threshold.
3. A disagreeing large error **replaces** the record; a sub-threshold error
   **clears** it; the record also clears on `reset()`, any emitted seek,
   track-lost, and a 30 s expiry. NTP grounding: RFC 5905's SPIK-state rule
   ("a single spike greater than the step threshold is always suppressed"),
   with a two-fix confirmation in place of the wall-clock WATCH stepout.

## Verification (first-hand)

- 8/8 core ctest suites; 126/126 Android JVM tests (`--rerun-tasks`).
- **Phantom-fix sim** (FT8 reproduction — one +1259 ms fix injected at
  mid-uncertainty in a clean stream): 24 fixes, **0 seeks ≥1000 ms**, final
  true error 1.2 ms.
- **Genuine-jump sim** (room really seeks ~1200 ms): exactly **1**
  corroborated large seek, loop re-converges to −22 ms.
- Two existing tests pinned the superseded single-fix behavior and were
  updated under §2.8's "Deliberate test change" authority (both named in
  the spec): `test_track_lost_threshold` (1999 ms now held, fires on the
  second agreeing estimate) and `test_synccore.cpp::
  test_correction_leads_by_recognition_age` (now corroborates with a second
  fix; its FT4 recognition-age lead assertion is preserved — target 17250
  computed from decision-time local, not capture-time).

## What's next

MHT (mission item 3 — needs a beat-period source, still a research gap),
then CTL-01 (active probe + referee sentinel — `comb_ratio` is now
available as one of its inputs). Field check for CTL-03: force a room seek
of ~1.2 s mid-lock; expect one held cycle (~8–10 s) then a single clean
correction, and `--stream` output now showing `comb_ratio` per window.
