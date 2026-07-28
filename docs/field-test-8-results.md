# Field Test 8 — the calibrated sync suite · 2026-07-28

**Verdict:** calibration works and measurably transforms first-aim accuracy.
The day's discovery is bigger than the pass, though: **any room
discontinuity hands recognition to our own speaker**, every telemetry layer
self-confirms, and only the listener knows. Rule zero held; the user's ear
out-performed every instrument, three separate times.

Suite plan: [field-test-8-suite.md](field-test-8-suite.md). Rig per
[field-test-protocol.md](field-test-protocol.md); floor this session 42 ms.

## Results

| # | Test | Result |
|---|---|---|
| 1 | Calibrated first aim | **PASS** — mic 63 ms at first LOCKED (uncalibrated FT7: 207 ms), zero corrections, engine −30 ms agrees within the floor |
| 2 | Referee live | **PASS** — first real committed sample (43 ms residual) through the ≥3-window agreement chain; drift semantics found wrong and fixed same-day |
| 3 | Multi-song boundary | **PASS** — end-of-track pause fired at 284.6 s/286 s, Spotify never auto-advanced (user-verified); next song matched 3.5 s after audible |
| 4 | Perturbation | **MUTATED** — became the self-match discovery (below) |
| 5 | Screen-off soak | deferred (needs a long stable source; self-match dominates until fixed) |
| 6 | Task-swipe | **PASS** — same PID, `isForeground=true`, session ticking after swipe |
| 7 | Trim promotion | not reached |

## The discovery: self-match after any room discontinuity

Observed from four independent triggers — room song-change, room stall,
forced room seek, operator taps:

- Recognition keeps matching OUR audio; offsets advance a perfect 1:1 with
  an eerily *constant* zEnd (~121–145 ms) — real room fixes jitter
  (song 2: 217–562 ms). The flat line is the signature.
- The engine self-confirms (−36 ms "LOCKED"); the analyzer shows a fake
  floor once the true offset exceeds its 2.5 s window; the referee's
  windows lose the second copy.
- Proof by forcing: with the session "LOCKED", a room seek changed nothing —
  the next fix (offset 131620, zEnd 81) equalled our own projected position
  (ps 8696 @ −123564 → 132.3 s), matching neither room timeline.
- The system printed the truth the moment the end-of-track pause forced a
  fresh listen: `sync err=-5396ms conf=0.01` — matching the user's live
  "8 beats off" call (~5.2 s) almost exactly.

The FT4 self-match guard cannot catch this: it rejects fixes that break
room continuity while landing on our own position, but after a
discontinuity our own audio *is* the continuous timeline — continuity looks
perfect. Fix directions (ticketed):

- **CTL-01 — referee validity as a self-match sentinel.** While LOCKED, the
  referee losing its second copy (validity collapse / agreement starvation)
  is the one observable that distinguishes "synced" from "hearing
  ourselves". On sentinel firing: treat as track-lost → pause → re-listen.
- **CTL-02 — correction corroboration.** Song 2's 1259 ms overshoot from a
  single conf-0.74 fix (user: "overcorrecting") stood uncorrected because
  follow-up errors hid near the deadband. Large corrections need a second
  agreeing fix (the guard's own two-fix pattern) or post-seek verification.

## Also fixed during the suite

- **Drift semantics** (spec + code): the residual is the error; a healthy
  route reads the reverb floor. Was compared against `latencyMs`, flagging
  a perfect 43 ms reading as drift. Now `drifted = |residual| > 50`, set
  and cleared per committed sample. Promoted trims no longer enter the
  referee ring (latency values, not errors).
- **"Leave the party" now pauses Spotify.** reset() cancelled the
  end-of-track guardian but left music playing — the exit path recreated
  the exact auto-advance the guardian prevents (observed live after a
  force-stop).

## Process lesson

Most conditions force in ~15 seconds (scrubber seek, leave/rejoin) — the
suite initially waited out full tracks; the user called it, and the
protocol should codify the fast loop. The user's ear remains the only
instrument that catches an out-of-range self-match: keep a human in every
sync field test.
