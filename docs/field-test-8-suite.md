# Field Test 8 — the calibrated sync suite · 2026-07-28

Everything before this measured pieces. This suite measures the product:
**does a calibrated device sync better, faster, and stay honest about it?**

Rig: Pixel 8 = room (YouTube), Pixel 10 Pro = JoinTheParty (phone speaker,
MEASURED 153 ms profile), Beosound A1 between them → `lag_analyzer --stream`.
Rule zero applies throughout: the mic grades, the engine only testifies.

## The baseline being beaten

Field test 7, same hardware, uncalibrated: first aim landed ~207 ms late
(mic) while the engine believed 3 ms; lock at the floor took the correction
loop several fixes to converge. The 153 ms prior should close most of that
gap **on the first seek**.

## Tests, in order

| # | Test | Method | Pass criteria |
|---|------|--------|---------------|
| 1 | **Calibrated first aim** | Join against the room; capture the first 30 s | Mic lag ≤ ~110 ms (floor + margin) at first LOCKED, without a correction seek; engine `e=` and mic agree within ~60 ms — the two-numbers rule |
| 2 | **Referee goes live** | Hold LOCKED 2–3 min | `sc_sample_latency_residual` fires ~every 20 s; ≥3 agreeing windows append a sample to `refereeSamples` in the stored profile; `drifted` stays false while mic is at floor |
| 3 | **Multi-song** | Let the room track end (or skip it) | End-of-track pause fires before Spotify auto-advances; re-acquire and re-lock at floor on the next song, no audible restart |
| 4 | **Perturbation** | Seek the room +10 s while LOCKED | Re-aim without restarting the track; mic returns to floor |
| 5 | **Screen-off soak** (INT-06 leftover) | Long source (mix), screen off 10 min | Mic at floor throughout; notification tracks phases; session alive at the end |
| 6 | **Task-swipe** (INT-06 leftover) | Swipe app from recents while LOCKED | Audio continues; notification remains; Stop still works |
| 7 | **Trim promotion** (stretch) | Commit ~the same trim 3× on the speaker | Promotion banner appears in device detail; accept folds it in and zeroes the wheel |

## Instruments per test

- `live_lag.csv` — acoustic truth (fresh file, floor noted before starting)
- `logcat -s JTP` → `fieldtest8.log` — phases, fixes, corrections, referee
- Stored profile blob before/after — referee samples and drift flag
- Notification dumpsys during 5/6

## Known preconditions

- `pm clear` earlier today wiped Spotify auth. App Remote may re-show its
  consent sheet on the first connect — needs one human tap on Phone B.
- The end-of-track pause and same-track resume (from INT-06's wave) get
  their field verification for free in test 3.
