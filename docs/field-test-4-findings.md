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

## Two more root causes behind the same symptom

Fixing the self-match guard exposed a residual ~1–2 s lag that persisted, and
which turned out to be two entirely separate bugs wearing the same costume.

### The correction was computed for the wrong instant

A recognition fix is 0.8–1.9 s old by the time ACRCloud answers. The engine
computed its seek target from `projected_local_ms(t)` where `t` is the fix's
**capture** timestamp, so every correction landed that far behind the room —
and because every correction re-established it, no amount of correcting could
remove it. The policy already led by the command latency; it also has to lead
by the recognition round trip. `wk.now_ns` is real session time (continuous
capture pushes advance it), so the decision now happens at now while the
observation stays at capture time, where it belongs.

This is the "playing about a second behind" reported since the very first
field test.

### A poisoned setpoint, read straight off the phone

The decisive measurement. The CORRECTION log line was extended to carry the
engine state it was computed from, which immediately showed `e=1892` against a
raw observation of `zEnd=+139` — a constant ~1750 ms gap, not the noisy
divergence a broken filter produces. Dumping the app's DataStore:

```
setpoint:speaker  →  −2007 ms
```

Written by the wheel-rebase runaway before that bug was fixed, restored on
every session since, and faithfully obeyed: the engine drove **its own** error
to ~0 while sitting two seconds behind the room. Setting the wheel trim to 0
never touched it — it lives under a separate key.

Quarantined three ways: the key was renamed (`setpoint2:`) so known-garbage
values are unreachable, the value is clamped to the wheel's own ±1500 range on
both save and restore, and committing the wheel at 0 now clears the setpoint
outright, since the absorbed bias was otherwise invisible and unclearable from
the UI.

**Lesson for future sessions:** when the engine reports converged but the room
sounds wrong, read the persisted state before suspecting the filter. A
constant offset between `e=` and `zEnd=` is stored state, not a filter bug.
The recipe is in `field-test-protocol.md`.

## Result

| | acoustic lag (Beosound) | engine |
|---|---|---|
| before | 1168 ms, steady, never converging | oscillating ±1800 ms |
| after self-match guard | 64–106 ms, with ~2.5 s excursions | limit cycle every ~29 s |
| after all three fixes | **62–76 ms, steady** | **LOCKED at −141 ms, zero corrections** |

The same rig measures ~85 ms of room reverb with nothing playing, so the
residual is at the noise floor. Engine and microphone now agree, which is the
real result: the app's own telemetry finally means what it says.

## Field Test 5 — the guard was armed only in the easy case

Song 1 locked at 72 ms and held for a full track. Song 2 ("Zanzibar", reached
by letting the room's video end and start the next) locked too — and settled
**1.7 s ahead of the room while reporting sync err = −3 ms**. The listener
heard it before the instruments did; the microphone then confirmed a median
lag of 1687 ms across 88 windows.

Reporting ≈0 error while badly out of sync is the self-match signature again,
so the question was why the guard let it through. The anchor was re-seeded on
*every* accepted fix, with `room_anchor_confirmed = tracks_room`. Song 2's
recognition data alternated between the room and our own playback — 5 of 16
intervals slipped — so every other fix broke continuity, the anchor was never
confirmed two fixes running, and the guard therefore never rejected anything.
It was armed exactly when the data was clean and disarmed exactly when it
wasn't.

Now a fix that breaks the room timeline *without* matching our own position is
held aside as a candidate instead of overwriting the anchor. Only a second fix
continuing that candidate's timeline adopts it — that is a real room change.
An isolated recognizer error can no longer destroy an established timeline.

Verified by re-joining against the same track mid-play:

| Zanzibar, same room | median mic lag | windows > 200 ms |
|---|---|---|
| before | 1687 ms | 71 of 88 |
| after | **71 ms** | 3 of 36 |

Zero corrections and zero rejections after the fix.

**The generalisable lesson:** a guard that needs clean data to stay armed is
not a guard. Both times this one failed, it failed by disarming — first at
convergence (the 30 s staleness cap), then under noisy data (the confirmation
rule). Check what disarms a safety mechanism, not just what triggers it.

## Still open

- **Output-latency bias.** Now small enough to be inside the measurement noise
  floor, but still unmodelled: the chirp calibration (INT-03) exists to measure
  the output chain per route and has never been run on this one. Worth doing
  before claiming sub-50 ms.
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
