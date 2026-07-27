# Field Test 4 — the phone was syncing to itself

**Date:** 2026-07-26
**Rig:** Phone A (Pixel 8) playing Billy Joel "My Life" on YouTube = the room.
Phone B (Pixel 10 Pro) running JoinTheParty against Spotify. A Beosound A1
microphone on the PC sits between them, hearing both, and feeds
`lag_analyzer --stream` for acoustic ground truth that owes nothing to
anything the app believes about itself.

## The headline

The app was not failing to correct. It was correcting toward a measurement
that was, roughly half the time, **its own audio**.

Phone B plays through its own speaker, so its microphone hears both the room
and itself. ACRCloud has no way to know which copy is which and locked onto
Phone B's own output on ~40% of fixes. A self-match reports near-zero sync
error by construction — we always match ourselves perfectly — so every one of
them told the Kalman filter "you are in sync" while the room was actually
1.2 s ahead. The filter oscillated between the two populations for six
minutes and never converged.

### How we proved it rather than guessed it

The room plays continuously, so a recognizer's reported offset **must** advance
1:1 with the wall clock. Testing that invariant across 55 fix intervals:

| | |
|---|---|
| intervals where offset tracked wall clock | 33 |
| intervals short by 1.2–1.8 s | 22 |

The short intervals were periodic, roughly every third fix, and `zEnd` (the
raw error observation) alternated between ≈ +100 ms and ≈ −1200 ms in a strict
repeating cycle. Two populations, not noise: +100 is us hearing ourselves,
−1200 is the real room.

The Beosound then confirmed it independently: a rock-steady **1168 ms**
acoustic lag between the two phones, peak ratio 13–32×, while the engine was
reporting −705 ms and declaring itself nearly converged.

## Why the existing guard never fired

`CORE-06` compared each fix against `last_commanded_position_ms` — a *frozen*
seek target. It never advanced with the wall clock, so within a second of any
seek it was comparing a live fix against a stale position and matched nothing.
The field never got read again after the rewrite; it is now diagnostics only.

## The fix

The room's continuity is the discriminator. A fix is rejected as self-hearing
only when it **both**:

1. breaks the room prediction (`last accepted offset + elapsed wall time`) by
   more than 500 ms, **and**
2. lands within 400 ms of our own audible position.

Both conditions are required, and that is what makes it safe:

- **At true lock** the room and our own position coincide — but the fix still
  tracks the room prediction, so condition 1 is false and it is accepted.
- **On a room perturbation** (someone skips the source) condition 1 is true but
  the fix is nowhere near our position, so it is accepted.
- **On a song change** the offset jumps discontinuously and misses our position
  entirely — accepted.

### Hardening added after review and after the live run

Two independent reviews and the live run itself each found a way this could go
wrong. All three are fixed:

- **A self-match bootstrap could poison the reference.** The first fix of a
  session is accepted without arbitration, so it may itself be a self-match.
  The reference now must be corroborated by two consecutive fixes agreeing on
  one continuous timeline before it may reject anything.
- **The guard locked itself out.** Observed live: once its reference disagreed
  with reality it rejected *every* fix for a minute while the mic confirmed the
  session was in sync. Three consecutive rejections now drop the reference
  instead of defending it, and the next fix re-seeds.
- **The guard switched itself off exactly at convergence.** The staleness cap
  (30 s) exactly equalled the converged fix cadence (`fix_interval_max_ns`,
  30 s), so in steady state every fix bypassed the guard. Now 90 s.

## Result

| | acoustic lag (Beosound) |
|---|---|
| before | 1168 ms, steady, never converging |
| after | 64–106 ms |

For reference, the same rig measures ~85 ms of room reverb with nothing
playing, so the residual is at the noise floor of the measurement.

## Still open

- **Output-latency bias.** The engine reported −705 ms when the mic measured
  1168 ms — a ~460 ms gap. That is our own output-chain latency (Spotify's
  reported position leads what is actually audible) and it is not modelled, so
  the engine converges to a biased setpoint. The chirp calibration (INT-03)
  exists to measure exactly this; it has never been run on this route. This is
  the remaining source of the "about a second behind" the ear reports.
- **Spotify autoplays past the room.** When the room's song ends, Spotify
  continues to its own next track, which the room is not playing. Deferred by
  PM decision.
- **`play(uri)` has no same-track guard** (`SessionViewModel.kt:716`). Every
  `onTrackLost` recovery restarts the track audibly from 0:00 before the aim
  re-seeks, even when the resolved track is the one already loaded.
- **`consecutiveLosses` is not song-change aware.** Three track-losses without
  an intervening LOCKED land the session in terminal ERROR, which only a user
  tap can clear — a real risk across a multi-song session.
- **`local_audible_ms` does not reflect a seek** until Spotify's next player
  state arrives, so the guard is briefly blind right after each correction.
