# Field Test 7 — INT-06 foreground service · 2026-07-27

**Verdict:** the foreground service works. The session survives screen-off,
backgrounding, and even total loss of the adb connection. Two previously
unverified fixes were also seen firing for the first time.

**But:** a **205 ms acoustic bias** appeared that the engine cannot see
(it reported 3 ms). That is the uncalibrated output chain — INT-03, a
pre-existing open item, not an INT-06 regression. It is the reason the
sync sounded "slightly off" by ear.

Rig and method: [field-test-protocol.md](field-test-protocol.md). Prior
results: [sync-test-results.md](sync-test-results.md).

---

## Setup

| | Device | Role |
|---|---|---|
| Phone A | Pixel 8 | room source — YouTube, "Billy Joel — My Life (Official Audio)" |
| Phone B | Pixel 10 Pro | JoinTheParty, INT-06 build |

Beosound A1 between them, live-streamed to `lag_analyzer --stream`
(`--max-lag-ms 2500`, 48 kHz mono). Quiet-room floor this session: **41–51 ms**
(lower than the 85 ms recorded in field test 4-5 — different room placement).

---

## INT-06 acceptance criteria

| Criterion | Result | Evidence |
|---|---|---|
| Mic-type FGS starts on Join | ✅ | `isForeground=true foregroundId=1 types=0x00000080` (`FOREGROUND_SERVICE_TYPE_MICROPHONE`) |
| Notification shows phase + track | ✅ | `Syncing — Fresh Start · Matz` → `Synced — Fresh Start · Matz`; matches the §2.5 mapping exactly |
| Notification has a working Stop | ✅ | Tapped in the shade → `phase: MATCHING → IDLE`, `dumpsys activity services` → `(nothing)`, 0 notification records |
| Session survives screen-off | ✅ | Screen off 10:54:32; continuous recognition and stable lag for the whole time music played |
| Session survives loss of adb | ✅ (bonus) | Phone B's wireless debugging dropped mid-run; session kept matching and playing |
| Session reaches LOCKED *while* screen is off | ✅ | Screen off 10:46:21 → `CONVERGING → LOCKED` 10:46:50 |
| 10-minute screen-off soak | ⬜ **incomplete** | Only ~104 s of music before both sources went quiet (see below) |
| Task-swipe does not kill session | ⬜ **not run** | Ran out of session time |

**Why the soak is incomplete.** ~104 s after screen-off both sources went to
−54 dB (silence). The room track was near its end and our own end-of-track
pause fired, so this is most likely a genuine end-of-song, not a service
death — but it was not confirmed, so the criterion stays open. Next run must
use a long source (a mix/playlist) so the soak is not bounded by track length.

## Also verified, first time in the field

- **End-of-track pause** (`cbcdd7d`, never field-verified until now):
  `track ending — pausing before Spotify picks the next one`, followed by the
  re-listen sequence `LOCKED → LOST → LISTENING → MATCHING`.
- **Room-continuity invariant held.** Reported offsets advanced 1:1 with the
  wall clock — `36140 → 41300` in 5.1 s, `71360 → 81360` in 10 s,
  `121360 → 151240 → 181400 → 211400` at 30 s intervals. No matcher jumps, so
  no self-hearing on this run.

---

## The 205 ms bias

| source | says |
|---|---|
| engine | `sync err=3ms drift=-202ppm conf=0.69 LOCKED` |
| microphone | **205–209 ms**, median **207 ms** (n=51 loud windows) |

Rule zero again: the engine's own number was wrong by 200 ms.

What makes this diagnosable rather than mysterious is that the bias is
**stable** — every window between 205 and 209 ms, no drift, no excursions.
Tracking is healthy; the whole system is simply shifted late by a constant.

**Cause: the output chain is not calibrated.** `dumpsys audio` shows Spotify's
track on the deep-buffer path:

```
AudioPlaybackConfiguration piid:16951 type:android.media.AudioTrack state:started
  flags=0xA00(FLAG_DEEP_BUFFER+FLAG_MUTE_HAPTIC) sampleRate=44100
```

Android's deep-buffer output carries exactly this order of latency. The engine
subtracts `output_latency_ms` when computing where we are *audible*, but that
prior is only ever set from a chirp calibration (INT-03) — and INT-03b (playing
the chirp through the output route) is still a `TODO`, so no calibration has
ever run on any route. The prior is therefore 0, and we sound late by the true
output latency.

Note this is **not** an acoustic path difference: 205 ms is ~70 m of air travel.
It is electronic.

**Why earlier tests got 62–88 ms on this hardware is unexplained.** The most
likely difference is which output path Spotify selected that day (the
low-latency path instead of deep-buffer), but that was not recorded at the
time, so it is a hypothesis, not a finding.

---

## Bug found and fixed during the test

**The session could sit in AIMING forever when the first aim landed cleanly.**

`playerStateWatcher` subscribes to App Remote's player states only *after* the
aim settles. Those states are event-driven on a no-replay `SharedFlow`: if the
first aim lands clean and no correction fires, the state announcing playback
was emitted **before** the collector existed, and steady playback emits nothing
for tens of seconds. So `AIMING → CONVERGING` never fired, the phase never
reached LOCKED, and — worse — `scheduleEndOfTrackPause` was never armed, which
would have let Spotify auto-advance past the room at the end of the song.

Observed directly: run 1 stayed in AIMING for a full track while the engine
happily reported `LOCKED`-quality error values underneath.

*Fixed:* `1161065` — seed the collector with `lastKnownPlayerState` before
collecting. Double-handling one state is harmless: the transition is
phase-guarded and the pause timer re-arms on every state.

> This is the same shape of defect as the field-test-4 lesson. There it was a
> guard that silently disarmed; here it is a subscription that silently misses
> its trigger. **Both fail by doing nothing, which looks identical to working.**

---

## Rig lessons for next time

- **Phone B's wireless debugging turned itself off three times**, each time
  needing a manual toggle and a new port. Transport IDs and ports change every
  reconnect — always re-read `adb devices -l` rather than reusing an ID.
- **A phone with JoinTheParty installed still needs Spotify installed and
  logged in.** The Pixel 8 has the app but no Spotify, so it cannot serve as
  Phone B without a sign-in.
- **`lag_analyzer.exe` needs the llvm-mingw runtime DLLs.** Copying
  `libunwind.dll` and `libc++.dll` next to the binary is more reliable than
  relying on `PATH` (a piped background job does not inherit an interactive
  `PATH` edit). Done — they now sit in `build/core/`.
- **Set the screen timeout on the ROOM phone too.** Only Phone B was
  configured; Phone A's state was never pinned.
- **Use a long source for soak tests** (a YouTube mix, not a 4:46 single) so
  the soak is not bounded by the track.
- The service is not exported, so `am start-service` cannot drive the Stop
  action — it must be tapped. Expand the notification (chevron), then scroll
  the shade; the action is below the fold on a Pixel 10 Pro.

---

## Next run — ordered plan

1. **Re-run the soak properly**: long room source, both phones' screen
   timeouts pinned, 10+ minutes screen-off, mic verdict at the end.
2. **Task-swipe test**: swipe JoinTheParty from recents while LOCKED; session
   must keep playing (`stopWithTask="false"`).
3. **Multi-song transition with the service**: confirm the notification text
   follows the phases across a song change while backgrounded.
4. **Attack the 205 ms** — this is now the headline sync problem, and it is
   INT-03, not INT-06. Options, cheapest first:
   - Measure the constant with the mic (done: 207 ms), dial it in on the trim
     wheel, and confirm the mic drops to the noise floor. Proves the diagnosis
     in one run.
   - Then implement INT-03b (render the chirp through the output route) so the
     number is *measured per route* rather than dialled by hand — this is also
     the foundation of the per-device calibration work that is parked.
