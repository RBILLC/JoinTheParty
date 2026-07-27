# Acoustic sync test results — Field Test 4–5

**Date:** 2026-07-26
**Verdict:** goal met. Three consecutive songs held in sync with the room,
1168 ms → 62–88 ms, verified acoustically and confirmed by ear.

This is the evidence record. The narrative of how each cause was found is in
[field-test-4-findings.md](field-test-4-findings.md); how to reproduce the rig
is in [field-test-protocol.md](field-test-protocol.md).

---

## Rule zero: the app's telemetry cannot grade the app

Twice during these tests the engine reported near-perfect sync while badly out:

| engine claimed | actual (microphone) |
|---|---|
| −705 ms | 1168 ms out |
| −3 ms | 1700 ms out |

Both times the cause was that it was measuring against its **own** audio, or
against a stored offset it had been told to hold. **Never accept `sync err` as
evidence of sync.** Use the microphone, or at minimum the two cross-checks in
the protocol doc (offset-vs-wall-clock continuity, and `e=` vs `zEnd=`).

## Method

A Beosound A1 microphone sits between the two phones and hears both. Each
8-second window is autocorrelated; the secondary peak is the acoustic lag
between the two copies of the song — what a listener actually hears.
`core/tools/lag_analyzer.cpp --stream` does this live from raw PCM on stdin.

- **Noise floor ≈ 85 ms** (room reverb with nothing playing). Results at
  62–88 ms are at the floor, not merely "better".
- **Search range must stay `--max-lag-ms 2500`.** At 4000 the analyzer locks
  onto harmonics of the music's own periodicity (spurious 3166 ms readings).
- **A low reading with only ONE source playing is meaningless** — that is
  reverb. Always confirm both phones are audible (`rms_db` ≈ −35 with two
  sources) before believing a good number.

---

## Runs, in order

Each run's failure motivated the next fix, so the sequence carries the argument.

| Run | Mic (median) | Engine claimed | Corrections | Verdict |
|---|---|---|---|---|
| Baseline (no fixes) | 1168 ms | −705 ms | continuous | **FAIL** — never converged in 6 min |
| + self-match guard | 64–106 ms, 2.5 s excursions | oscillating ±1800 ms | every ~29 s | **PARTIAL** — limit cycle |
| Song 1 "My Life" (+3 more fixes) | 72 ms (n=25, 24 under 200) | LOCKED −141 ms | 0 | **PASS** — held a full 4:45 track |
| Song 2 "Zanzibar", pre-anchor-fix | 1687 ms (n=88, 71 over 200) | −3 ms | 2 forward | **FAIL** — confidently wrong by 1.7 s |
| Song 2 repeat, post-anchor-fix | 71 ms (n=36, 3 over 200) | LOCKED −174 ms | 0 | **PASS** |
| Song 3 (room changed on its own) | 62–88 ms | LOCKED | 0 | **PASS** — followed autonomously |
| Final build (6 s window) | not measured | LOCKED 44 ms | 0 | **TELEMETRY ONLY** — mic had disconnected |

### The strongest single result

Same track, same room, same phones, rejoined mid-play — only the code changed,
which isolates the anchor fix from every other variable:

| Zanzibar | median mic lag | windows > 200 ms | n |
|---|---|---|---|
| before anchor fix | 1687 ms | 71 | 88 |
| after anchor fix | **71 ms** | 3 | 36 |

---

## Root causes

Five separate defects, all presenting as "it's about a second behind". Each
masked the next, which is why they had to be found in this order.

### 1. The phone was recognizing its own audio
The speaker reaches its own microphone, so ACRCloud locked onto our own
playback on ~40% of fixes. A self-match reports near-zero error by
construction, so each one told the filter it was synced while the room ran
1.2 s ahead.

*Evidence:* offset continuity — 22 of 55 intervals short by 1.2–1.8 s,
periodically; `zEnd` alternated between ≈ +100 ms (us) and ≈ −1200 ms (room).
*Fixed:* `cc59ce4` — `core/src/synccore.cpp`, room-continuity + self-position
double condition. The old guard compared against `last_commanded_position_ms`,
a frozen seek target that never advanced with the clock, so it never fired.

### 2. Corrections were computed for the wrong instant
A fix is 0.8–1.9 s old by the time the recognizer answers, but the seek target
was built from the fix's **capture** time. Every correction landed that far
behind — and every correction recreated it. This is the "playing about a second
behind" reported since the first field test.

*Fixed:* `ab4bfb4` — decision moves to `wk.now_ns` (real session time, advanced
by continuous capture pushes); the observation stays at capture time.

### 3. A poisoned setpoint (−2007 ms), read off the device
Written by the wheel-rebase runaway before that bug was fixed, restored every
session, faithfully obeyed: the engine drove **its own** error to ~0 while
sitting two seconds behind the room. Zeroing the wheel trim never cleared it —
it lives under a separate key.

*Evidence:* `setpoint:speaker` → `18 a9 f0 ff ff ff ff ff ff ff 01` → −2007.
*Fixed:* `7a073cc` — key renamed `setpoint2:`, clamped ±1500 on save and
restore, and committing the wheel at 0 now clears it outright.

### 4. Spotify auto-advanced past the room
When the room's track ended, Spotify moved on by itself to a song the room
wasn't playing. From then on the recognizer only ever heard us. The guard
correctly refused every fix — leaving the session nominally LOCKED, confidence
decayed to 0.00, playing the wrong song and unable to discover the right one.

*Fixed:* `1836b30` — a player state naming a track we did not command means
Spotify auto-advanced; pause, drop the track, re-listen after a quiet window.

**Now prevented, not just detected** (`cbcdd7d`). App Remote has no way to turn
autoplay off, but `PlayerState.track.duration` is Spotify's own exact length for
the track *we* are playing, so we schedule a pause 400 ms before our track ends
and Spotify never gets to choose.

Two things made this look impossible at first, both wrong:

- *"The room's version may be a different length."* Irrelevant — we are timing
  OUR track's end. If the room is playing a longer master we have no audio left
  for those extra seconds anyway, so pausing at our own end costs nothing we
  could have played.
- *"There is no event near the end to react to."* True, and that is why it must
  be a **timer**, not a check on incoming player states: App Remote emits those
  only on events, so during steady playback nothing arrives for tens of seconds.
  Every fresh state re-arms the timer, which absorbs drift and our corrections.

The reactive path remains as a backstop for when the timer is missed (no
duration reported, or the user hits next in Spotify). Recovery is also quiet
now, because the same-track guard resumes rather than restarting.

### 5. The guard disarmed itself under noisy data
The room-timeline anchor re-seeded on **every** accepted fix
(`room_anchor_confirmed = tracks_room`). When recognition alternated between
the room and our own audio, every other fix broke continuity, so the anchor was
never confirmed twice running and the guard never rejected anything.

*Evidence:* song 2 — 5 of 16 intervals slipped, 0 rejections fired, engine
settled 1.7 s ahead reporting −3 ms.
*Fixed:* `3ad35c1` — a fix that breaks the timeline without matching our own
position is held aside as a candidate; only a second fix continuing that
candidate's timeline adopts it.

> **Generalisable lesson.** Both times this guard failed, it failed by silently
> switching off — first at convergence (a staleness cap that exactly equalled
> `fix_interval_max_ns`, 30 s), then under noisy data. **Check what *disarms* a
> safety mechanism, not just what triggers it.**

---

## Weaknesses in this evidence

Read this section before quoting the headline number.

- **The final build was never acoustically verified.** The Beosound
  disconnected before the 6-second-window build could be measured. Its
  "LOCKED at 44 ms" is engine telemetry only — exactly the kind of claim this
  exercise proved can be wrong by seconds.
- **A "filter divergence" was reported that did not exist.** It was the stored
  setpoint. The `e=` / `conf=` fields added to the CORRECTION log line
  (`99974d4`) are what disproved that earlier diagnosis.
- **Song 3 was measured on the pre-final build**, and the song-change pickup
  time (~15 s, since halved by `5d2f007`) was not re-timed afterwards.
- **One run was silently invalidated** by Phone B's screen locking: the Join
  tap hit the keyguard, the log came back empty, and Spotify kept
  auto-advancing in the background. Set `screen_off_timeout` first.

## Still open

- **No foreground service (INT-06).** Pocketing the phone or locking the screen
  kills the session. Biggest demo-vs-product gap.
- ~~`play(uri)` has no same-track guard~~ — **fixed, not yet field-verified.**
  `startPlayback` now compares the resolved URI against what Spotify already
  has loaded; if they match it resumes and aims instead of calling `play(uri)`,
  which restarts at 0:00. Needs an ear on a real recovery to confirm the
  restart is gone.
- **Output-chain latency never calibrated.** The chirp calibration (INT-03)
  exists for exactly this and has not been run on this route. Do it before
  claiming sub-50 ms.
- **`consecutiveLosses` is not song-change aware** — three losses without an
  intervening LOCKED land the session in terminal ERROR, which only a user tap
  clears.
- **`local_audible_ms` does not reflect a seek** until Spotify's next player
  state arrives, so the guard is briefly blind right after each correction.

## Commits

`2b17003` wheel rebase runaway · `cc59ce4` self-match guard ·
`99974d4` confidence floor + correction diagnostics · `ab4bfb4` recognition-age
lead · `7a073cc` setpoint quarantine · `1836b30` auto-advance pause ·
`3ad35c1` anchor survives isolated bad offset · `5d2f007` re-acquire speedup
(plus `44ad4ae`, `1188714`, `e16417c` docs)
