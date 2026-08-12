# Field Test 10 — CTL-01/02/03/INT-06 device pass + FT9 fixes field validation · 2026-08-12

**Scope.** Device-pass validation of GitHub issues #28 (CTL-01), #29 (CTL-02), #30
(CTL-03), #31 (INT-06), plus field validation of the already-closed FT9 fixes #32
(GRD-01), #33 (IDC-01), #34 (CTL-04). Two of four planned scenarios ran to a
usable conclusion; two were blocked by rig failures documented below. **This
run also found a reproducible app crash inside GRD-01's own fix (#32), which
is the headline result.**

## Rig

- **Phone B** (Pixel 10 Pro, `blazer`, runs JoinTheParty): `-s 100.107.161.95:39881`.
  Package `com.jointheparty.app`, `versionName 0.1.0`, `lastUpdateTime
  2026-08-05 19:56:20` (commit `40f4cd4` — includes all FT9 fixes; MHT bank
  present but `mht_enabled=false`, inert).
- **Phone A** (Pixel 8, `shiba`, room source, YouTube): `-s 100.72.59.78:45705`.
- **Geometry** (per live orchestrator correction): Phone B (Pixel 10 Pro) sits
  on the **left** of the Beosound A1 microphone, Phone A (Pixel 8) on the
  **right**, each **1 ft away**. All three — both phones and the mic — are in
  the same room, correctly placed; acoustic silence observed later in this
  run is a playback/capture condition, not a positioning one (see Scenario 3/4
  blockers).
- **Mic**: dshow device confirmed live, exact name `Headset Microphone
  (Beosound A1 2nd Gen Hands-Free)` (note the "Hands-Free"/HFP suffix — same
  caution as the brief: HFP is narrowband; no degradation vs FT9 was actually
  observed in the captured data, but flagging per the standing instruction).
- **adb caution confirmed live**: `adb devices -l` listed four transports,
  each phone twice (IP:port + mDNS `adb-tls` entry); only the two `-s
  IP:port` serials above were used throughout.
- **Bluetooth found ON and connected on Phone B** at rig verification (an
  A2DP headset, `STATE_CONNECTED`) — this was silently routing Phone B's
  media stream away from the physical speaker (`dumpsys audio` showed `2
  (speaker): 0` while `80 (bt_a2dp)` carried the live index). Disabled via
  `svc bluetooth disable` before any join. Phone A had no active Bluetooth
  connection. **This is a new rig-hygiene item for the standing protocol
  doc**: check for an active BT audio route on both phones, not just BT
  power state, before assuming speaker output.
- **Volumes standardized** post-BT-fix: Phone A speaker index 21/25
  (pre-existing), Phone B speaker index nudged to 20/25 (was 0/25 while
  routed to BT). `media volume` shell command is not present on these
  images; volume was set via `settings put system volume_music_speaker` +
  `input keyevent` VOLUME_UP/DOWN against `dumpsys audio`'s reported index.
- **Persisted calibration read** (`nudge_store.preferences_pb`, read-only,
  not modified): `calibration_profile:speaker` — `latencyMs=173`,
  `method=MEASURED`, `drifted=false`, 24 `refereeSamples` spanning two
  collection dates, residuals **41–58 ms** throughout (healthy, at-floor).
  `trim:speaker=0`, `setpoint2:speaker=0`, `trim_commits:speaker` = ten
  zeros (no stale trim — unlike FT9's Test 1, this session did NOT start
  from a stale-trim confound). Decision: kept as-is (testing product
  behavior, not trim mechanics), consistent with the protocol's own
  guidance.
- **Protocol gap I own**: I did not re-run per-route calibration before
  Scenario 1 (the protocol's "testing product behaviour" branch calls for
  this). The stored profile looked healthy by the numbers above, so I
  proceeded — but see Anomaly A below, which raises a real question about
  whether it actually was.

## Noise floor

Two windows, both with nothing intentionally playing:

- **t≈0–15s** (CSV lines 2–4, `t_s` 8–12): rms **−30 to −31 dB**, lag_ms
  coherent at 50–51 ms — almost certainly residual setup noise (talking,
  device handling) during rig prep, not a true floor.
- **t≈49–57s, settled** (CSV lines 22–26, `t_s` 49–57): rms **−56.6 to
  −56.9 dB**, lag_ms coherent 50–51 ms, then destabilizing into incoherent
  scatter (308/634/325 ms) as the window continued — this matches FT9's
  documented "silent room → incoherent lag scatter, no coherent peak" shape
  exactly. **Floor for this session: ~50 ms**, close to FT9's own 49 ms.

## Timeline (CSV `live_lag_ft10.csv` line markers, chronological)

| Marker | CSV line | Wall time | Event |
|---|---|---|---|
| Pipeline start | 1 (header) | 10:44:07 | ffmpeg\|lag_analyzer started |
| Settled floor window | 22–26 | 10:45:01–10:45:21 | ~50 ms floor, rms −56.6…−56.9 dB |
| Scenario 1 join | — | 10:46:58 | Phone B Join tapped (Dreams playing on Phone A) |
| Scenario 1 LOCKED | ~line 91 (`t_s`≈211) | 10:47:39.867 | `phase: CONVERGING → LOCKED` |
| CTL-02 fire #1 | ~line 116 (`t_s`≈241) | 10:48:09.971 | `CORRECTION → seek 119120ms (jump -267ms) e=318 conf=0.84` |
| CTL-02 fire #2 | — | 10:48:44.181 | `CORRECTION → seek 152906ms (jump -287ms) e=298 conf=0.86` |
| Scenario 1 natural end | — | 10:50:28.967 | `track ending — pausing before Spotify picks the next one` |
| Scenario 2 room source switched | — | 10:51:19–10:51:48 | Phone A → Billie Jean, confirmed playing 0:05/4:54 |
| Scenario 2 first (mis)match | line 232–233 (`t_s`≈479–481) | 10:52:08.973 | `MATCH ✓ 'Tubo Tubo' ... uri=...2pWr84...` (misrecognition, see below) |
| **App crash** | — | 10:54:08.767 | `FATAL EXCEPTION` in `consumeSelfPlayLatch` — full trace below |
| App relaunched | — | 10:56:33 | `am start`, confirmed clean return to "Join the party" screen |
| Phone A found locked (fingerprint) | — | ~10:57:18–10:58 | discovered while switching room source to Vienna for Scenario 3 |
| Phone A airplane-mode fixed via adb | — | 10:58:xx | `settings put global airplane_mode_on 0` (adb shell writes still work through a locked screen; UI taps do not) |
| Phone B found locked, then dropped from adb | — | ~11:00–11:02 | `device offline` → `cannot connect ... actively refused it (10061)` |
| Pipeline stopped | line 533 (last, `t_s`≈1096) | 11:02:27 | `ffmpeg`/`lag_analyzer` killed cleanly |

## Scenario 1 — Dreams (#29 CTL-02, #34 CTL-04 field validation)

Driven: Phone A → YouTube search → "Fleetwood Mac - Dreams (Official Audio)",
screenshot-confirmed playing at 0:18/4:18. Phone B: force-stop, launch, Join
tapped 10:46:58. Log non-empty within 1s (`join → startCapture=ok;
recognizer=ACRCloud`). Sequence: `IDLE→LISTENING→MATCHING` (10:46:59) →
`AIMING` (10:47:11.710) → `CONVERGING` (10:47:14.436) → **`LOCKED` at
10:47:39.867** (41s from Join).

### #29 CTL-02 — PASS on its own stated criterion

Two clean sub-deadband persistence-gate fires, both while continuously
LOCKED on the same track (`uri=spotify:track:6O9va8lMJZfBfw9YPCmACi`
throughout — no edition churn on this non-repetitive material):

```
10:48:09.971  CORRECTION → seek 119120ms (jump -267ms) e=318 conf=0.84   (30s after LOCKED)
10:48:44.181  CORRECTION → seek 152906ms (jump -287ms) e=298 conf=0.86   (35s later)
```

Both `|e|` (318, 298) are below the 350 ms Android instantaneous deadband —
these can only be persistence-gate fires (§2.7's 125 ms floor < both). CSV
around the first fire (lines 116–118, `t_s` 241/244/246): lag_ms **40/41/41
ms** — at floor. **Verdict: PASS.** The mechanism fires as designed: waits
out corroboration, fires one clean sub-deadband correction, residual lands
at floor.

### Anomaly A — engine `e` never converges to the mic/referee's agreed floor

This is the important caveat on the PASS above. Through the whole LOCKED
window, the **mic** read at-or-near floor almost continuously (CSV: 40–57 ms
dominant mode, occasional harmonic outliers), and the **referee**
(CAL-04, a separate mechanism) independently agreed:

```
10:49:20.675  referee: committed 45ms residual on speaker
10:50:20.740  referee: committed 43ms residual on speaker
```

But the **live `sync err`** never tracked either of them down to floor. Immediately
after the second CTL-02 fire it briefly touched 10–11 ms, then within one
second flipped to a stable **−314 to −316 ms** band and held there for
~15 s, then drifted back up through 210→228 ms as the track ended:

```
10:48:44.606  sync err=10ms drift=187ppm conf=0.32 LOCKED
10:48:48.716  sync err=-316ms drift=171ppm conf=0.81 LOCKED
10:48:58.037  sync err=-315ms drift=171ppm conf=0.64 LOCKED
10:49:33.227  fixdbg: offset=200800 zEnd=357 zResp=893 capAge=536ms (ps=152925@-48769ms)
```

Two things stand out in the raw fix data: (1) `zEnd` (the recognizer's raw
error observation) stayed pinned in the **350–520 ms** band on essentially
every fix after the second correction, never trending toward the mic's
40–57 ms; (2) `ps=152925@-Xms` (last known Spotify player-state age) grew
**unbounded** across the same window — `-3914ms` at 10:48:48 to `-78871ms`
by 10:50:03 — meaning Spotify's player-state stream appears to have gone
stale for well over a minute while corrections kept firing off recognizer
fixes alone. **I cannot rule out that this staleness is feeding a phantom
offset into the correction math** — the persisted-profile numbers say the
route is well-calibrated (referee samples 41–58 ms), the mic agrees, but the
live filter's own `e` doesn't, for a reason this run's evidence doesn't
fully explain. Flagged for a follow-up ticket, not resolved here.

### #34 CTL-04 (settling hysteresis) — NOT OBSERVED / INCONCLUSIVE

Per `docs/ft9-fixes-review.md`, `settled_` only enters `true` when `|e| <=
settle_enter_threshold_ms` (150 ms) at a post-verify checkpoint. Across this
entire scenario, live `e` never crossed below ~210 ms (closest approach:
the single-sample 10–11 ms blip at 10:48:44.606, immediately followed by the
−314 ms flip one second later, which is not the sustained post-verify
landing the mechanism checks). **I cannot confirm `settled_` was ever
entered**, so the settling-hysteresis field validation this scenario was
built to give #34 did not get exercised — not a fail, but an honest gap.
Given Anomaly A above, this may be the same root cause: if the engine's own
`e` structurally can't land under 150 ms on this route/session, CTL-04 can
never engage regardless of how healthy the acoustic reality is.

Scenario ended naturally at 10:50:28.967 (`track ending — pausing before
Spotify picks the next one`, matching FT9's documented end-of-track
guardian exactly), confirmed by screenshot showing YouTube's "Suggested
video" interstitial (Billy Joel - My Life). LOCKED held 10:47:39.867 →
10:50:28.977 (2m49s), track never desynced audibly per the mic (floor-class
throughout modulo Anomaly A's engine-side puzzle).

## Scenario 2 — Billie Jean (#30 CTL-03, #32 GRD-01, #33 IDC-01)

Driven: Phone A → YouTube search → "Billie Jean" (Michael Jackson, 418M
views), screenshot-confirmed playing 0:05/4:54 at 10:51:48. Phone B was
still in `MATCHING` (re-listening after Dreams' natural end) — no fresh
Join needed.

**This scenario reproduces FT9's Billie Jean churn almost exactly**, down to
several of the same catalog-edition URIs (`5dMuRtYktKL5Bkv5qph75v`,
`0fHbLv7QZDpD2tHqzxOg1e`, `5ChkMS8OtdzJeqyybCc9R5`), plus one outright
misrecognition (`'Tubo Tubo'`, `uri=spotify:track:2pWr84B7vpVuEs34DssH5P`) that
ACRCloud returned repeatedly and self-consistently before the room's true
identity (`Billie Jean`) got through. In the ~2-minute window before the
crash: **63 phase transitions, 14 `→ LOST` cycles**, 4 distinct
`play(uri)`-eligible identities (1 misrecognition + 3 Billie Jean editions).

### The crash — headline finding

```
08-12 10:54:08.767 13554 14498 E AndroidRuntime: FATAL EXCEPTION: DefaultDispatcher-worker-8
08-12 10:54:08.767 13554 14498 E AndroidRuntime: Process: com.jointheparty.app, PID: 13554
08-12 10:54:08.767 13554 14498 E AndroidRuntime: java.lang.IndexOutOfBoundsException: Index 0 out of bounds for length 0
08-12 10:54:08.767 13554 14498 E AndroidRuntime: 	at java.util.ArrayList.remove(ArrayList.java:559)
08-12 10:54:08.767 13554 14498 E AndroidRuntime: 	at com.jointheparty.app.ui.session.SessionViewModel.consumeSelfPlayLatch(SessionViewModel.kt:795)
08-12 10:54:08.767 13554 14498 E AndroidRuntime: 	at com.jointheparty.app.ui.session.SessionViewModel.handlePlayerState(SessionViewModel.kt:753)
08-12 10:54:08.767 13554 14498 E AndroidRuntime: 	at com.jointheparty.app.ui.session.SessionViewModel$playerStateWatcher$1$2.emit(SessionViewModel.kt:735)
08-12 10:54:08.767 13554 14498 E AndroidRuntime: 	at kotlinx.coroutines.flow.SharedFlowImpl.collect$suspendImpl(SharedFlow.kt:397)
```
(full trace: `scratchpad/ft10/crash_buffer.log`)

This is **GRD-01's own self-play latch** (#32, closed post-FT9) crashing the
whole app under real hostile-repetitive-material load. Reading the shipped
code (`SessionViewModel.kt:792-797`):

```kotlin
private fun consumeSelfPlayLatch(uri: String): Boolean {
    val idx = selfPlayLatch.indexOfFirst { it.first == uri }
    if (idx < 0) return false
    selfPlayLatch.removeAt(idx).second.cancel()
    return true
}
```

against `latchSelfPlay`'s own per-entry expiry job (`scope.launch(dispatcher)
{ delay(SELF_PLAY_LATCH_WINDOW_MS); selfPlayLatch.removeAll { it.second ===
job } }`, `SessionViewModel.kt:773-783`): `selfPlayLatch` is a plain
`mutableListOf`, unsynchronized, mutated from multiple coroutines that can
land on different `Dispatchers.Default` worker threads. Under the churn
rate this scenario produced, the window between `indexOfFirst` finding
index 0 and `removeAt(0)` executing was wide enough for a concurrent
`removeAll` (the entry's own expiry) or another `consumeSelfPlayLatch` call
to empty the list first — a classic TOCTOU race. The JVM test suite
(`SessionViewModelTest.kt`'s 5 GRD-01 tests, all passing per
`docs/ft9-fixes-review.md`) uses a controlled/virtual-time single dispatcher
and would not surface this; it took FT10's real multi-threaded, high-churn
field load to hit it.

**This means #32 (GRD-01) cannot be considered field-cleared.** It needs a
concurrency fix (a `Mutex`/`synchronized` guard, or a thread-safe
collection) and a re-test under the same Billie Jean load before it can be
closed. App recovered cleanly on relaunch (10:56:33, confirmed clean return
to the "Join the party" screen, no crash loop, no corrupted persisted
state) — this is not a data-corruption finding, just an availability one.

**Secondary GRD-01 observation (separate from the crash):** at least one
"Spotify auto-advanced to X — pausing to hear the room" guardian firing was
traced to a **stale confirmation of our own earlier `play()` call arriving
outside the 5s latch window** — `10:52:59.909` reported
`spotify:track:2pWr84B7vpVuEs34DssH5P` (the Tubo Tubo misrecognition) as an
"auto-advance," ~50 s after we ourselves called `play()` on it at
10:52:09.312 — 10x longer than `SELF_PLAY_LATCH_WINDOW_MS` (5000L). The
latch is working as designed (a 50s-late confirmation should not still be
in a 5s window), but it's a real edge case the fix doesn't cover, worth
noting for the same follow-up ticket.

### #33 IDC-01 — NOT CONFIRMED (field evidence is hard to reconcile with the spec)

Per `docs/ft9-fixes-review.md`, resolution should require 3 same-URI fixes
agreeing within 500 ms before `resolvedWithAim` proceeds. In every
re-bootstrap cycle observed in this scenario, actuation followed the
triggering `MATCH` line within **under one second**, with no visible
multi-fix accumulation delay — repeated at least 6 times:

```
10:52:08.973  MATCH ✓ 'Tubo Tubo' offset=266760ms uri=...2pWr84...
10:52:08.976  phase: MATCHING → AIMING
10:52:09.312  Spotify connected → play spotify:track:2pWr84...        (0.34s later)

10:52:59.356  MATCH ✓ 'Billie Jean' offset=76120ms uri=...5ChkMS8...
10:52:59.358  phase: CONVERGING → LOST → LISTENING → MATCHING → AIMING   (same ms)
10:52:59.860  Spotify connected → play spotify:track:5ChkMS8...        (0.5s later)

10:53:12.694  MATCH ✓ 'Billie Jean' offset=89300ms uri=...0fHbLv7...
10:53:12.694  phase: MATCHING → AIMING                                  (same ms)
10:53:12.973  Spotify connected → play spotify:track:0fHbLv7...        (0.28s later)
```

I could not find a single instance across the whole scenario of a visible
delay consistent with waiting for a 3rd corroborating fix before acting. I
also could not find any `aim gave up` line (the MAX_AIM_ATTEMPTS give-up
path that IDC-01(a) hooks) anywhere in this run — only the natural
track-lost/auto-advance re-bootstrap path (`onTrackLost()`) was exercised,
which per the review IS one of the two arming call sites. Given
`armIdentCorroboration()` has no dedicated log line, I cannot rule out that
each of these single-visible-fix actuations is really fix #3 of a streak
that accumulated across earlier, indistinguishable fixes — I don't have the
instrumentation to tell the two apart from the outside. **Verdict: NOT
CONFIRMED, not a certain FAIL.** Recommend a lightweight `identCorrob:
streak N/3` log line before the next field pass — this exact ambiguity is
what field-tested it into an inconclusive result rather than a clean one.

### #30 CTL-03 — NOT RUN

The session never reached a stable `LOCKED` phase on Billie Jean before the
10:54:08.767 crash cut Scenario 2 short — ~2 minutes of continuous
AIMING/CONVERGING/LOST churn, reproducing FT9's own finding almost exactly
("CTL-03's corroboration-hold path was never exercised... this path is
entirely upstream of CTL-03's territory"). No `CorrectionPolicy` `CORRECTION
→` line with `|e| ≥ 1000` appeared anywhere in the captured window. The
planned "force a +10s room seek once locked" step was never reached.
**Verdict: NOT RUN**, blocked by (a) Billie Jean's known churn behavior
(consistent with FT9, not a new finding) and (b) the crash ending the
session before churn had a chance to settle into a stable lock the way
FT9's own Test 2 eventually did.

## Scenario 3 — #28 CTL-01 (referee sentinel + active probe): NOT RUN

**Blocked by rig failure, not by app behavior.** After relaunching the app
post-crash and switching Phone A's room source to Vienna (to get a clean,
non-repetitive re-lock before forcing a discontinuity), Phone A's screen was
found locked behind a **fingerprint-secured keyguard** — cause unclear,
possibly a stray gesture during earlier screenshot/navigation steps.
`adb shell` commands still function through a secure lock screen (confirmed:
`settings put global airplane_mode_on 0` successfully cleared an
accompanying Airplane-mode toggle that had also appeared), but **touch/UI
automation does not** — `field-test-protocol.md` already documents this
exact limitation ("a secured lock screen cannot be dismissed over adb...
this needs a human"), and I did not attempt to guess a PIN or otherwise
work around it. Zero CTL-01-relevant evidence was gathered.

## Scenario 4 — #31 INT-06 (foreground service chain): NOT RUN

**Also blocked by rig failure.** Shortly after discovering Phone A's lock,
Phone B was found in the same state (fingerprint-locked, confirmed by
screenshot — its lock screen showed a live Spotify "Billie Jean" media
widget, consistent with the pre-crash session state, but not independently
useful as INT-06 evidence). Phone B then dropped off `adb` entirely:

```
$ adb connect 100.107.161.95:39881
cannot connect to 100.107.161.95:39881: No connection could be made because
the target machine actively refused it. (10061)
```

This is precisely the "wireless debugging switches itself off" trap
documented in `field-test-protocol.md` ("There is no adb-side fix... must be
toggled back on on the phone, and the new IP:port read off its settings
screen"). One inconclusive data point was captured moments before losing
adb entirely: `dumpsys notification --noredact` still showed a
`com.jointheparty.app` notification with the `FOREGROUND_SERVICE` flag set —
but I could not confirm via `pidof`/`dumpsys activity services` whether this
reflected a live rejoined session or a stale leftover before the connection
dropped, and no screen-off/task-swipe/Stop-action step was ever driven.
**Not usable as INT-06 evidence.**

## Teardown

- `ffmpeg`/`lag_analyzer` pipeline stopped cleanly at 11:02:27 (PIDs 1842/1843
  killed after confirming no further growth needed). Final
  `docs/live_lag_ft10.csv`: 533 lines (532 data rows), continuous `t_s`
  0→~1096 (~18.3 minutes), never interrupted.
- Phone B `logcat -s JTP` capture (`scratchpad/ft10/jtp_ft10.log`, 1163
  lines) stopped receiving new data when Phone B dropped off adb; left
  in place, not deleted. Full-buffer crash trace separately captured at
  `scratchpad/ft10/crash_buffer.log` (`logcat -d -b crash`).
- adb left connected to Phone A only (`100.72.59.78:45705`, screen
  Dozing, fingerprint-locked). Phone B fully disconnected — needs a human
  to physically re-enable wireless debugging and report the new IP:port
  per the standing protocol.
- No git commit made; no GitHub issue actions taken (orchestrator's job).

## Verdict summary

| Issue | Verdict | Evidence pointer |
|---|---|---|
| #28 CTL-01 | **NOT RUN** | Blocked — Phone A fingerprint-locked before the discontinuity step |
| #29 CTL-02 | **PASS** (with caveat) | `jtp_ft10.log:109,159` — two sub-deadband fires (e=318, e=298); see Anomaly A |
| #30 CTL-03 | **NOT RUN** | Billie Jean churn never reached LOCKED; crash at 10:54:08.767 ended the attempt |
| #31 INT-06 | **NOT RUN** | Blocked — Phone B fingerprint-locked, then dropped off adb entirely |
| #32 GRD-01 (field) | **FAIL — regression found** | `crash_buffer.log` — reproducible `IndexOutOfBoundsException` crash, real-world-only race |
| #33 IDC-01 (field) | **NOT CONFIRMED** | `jtp_ft10.log` — actuation observed within <1s of first visible fix, repeatedly; cannot rule out instrumentation blind spot |
| #34 CTL-04 (field) | **NOT OBSERVED / INCONCLUSIVE** | `sync err` never sustained ≤150ms; `settled_` precondition likely never met |

## Cleared to close: **none**

No issue in this batch should be closed on this run's evidence. #29 has a
genuine positive result on its own narrow criterion but surfaced an
unexplained engine/mic gap (Anomaly A) that touches its own territory
closely enough to warrant holding off. #32 should be **reopened**, not left
closed — this run found a reproducible crash in its shipped fix. #28, #30,
#31 are simply not run. #33/#34 need better field instrumentation
(a streak-progress log line; visibility into `settled_`) before their next
attempt can produce a clean verdict either way.

## Anomalies (honest list, FT9's own standard)

- **A — engine/mic residual gap in Scenario 1** (see above): `sync err`
  never converged toward the mic+referee's agreed ~45 ms floor; possibly
  linked to growing Spotify player-state staleness (`ps=X@-78871ms`
  observed). Unresolved.
- **B — GRD-01 crash** (headline finding, see above): reproducible
  `IndexOutOfBoundsException` in `consumeSelfPlayLatch`, real-world
  thread-safety race invisible to the JVM suite.
- **C — ACRCloud misrecognition**: Billie Jean was fingerprinted as
  `'Tubo Tubo'` on the very first fix of Scenario 2 and matched
  self-consistently three times before the correct identity got through —
  a third-party recognizer issue, not necessarily a JTP defect, but exactly
  the class of event IDC-01 exists to gate.
- **D — Bluetooth silently stole Phone B's speaker route** at rig
  verification (see Rig section) — new item for the standing protocol's
  pre-flight checklist.
- **E — both phones locked and Phone B lost wireless debugging** mid-run,
  ending the session's UI-driven scenarios. Both are documented, known
  classes of failure in `field-test-protocol.md`; neither was worked around
  per the honesty rules (no PIN-guessing, no fabricated evidence).

## Orchestrator verification addendum (2026-08-12, post-run)

Every quoted number above was re-checked firsthand against the raw captures:
CSV rows 22–26 and 116–118, `jtp_ft10.log` lines 109/159 (the two CTL-02
fires), the referee commits (lines 217/284), and the full crash trace — all
verified exact. `live_lag_ft10.csv` is 533 lines, `jtp_ft10.log` 1163 lines,
as documented. Raw logs preserved locally at `scratchpad/ft10/` (untracked,
per FT9 precedent of committing only the CSV).

### Crash root cause: CONFIRMED in source, plus a second same-family race

The race analysis holds. `dispatcher` is `Dispatchers.Default`
(`SessionViewModel.kt:422`) — a multi-threaded pool, corroborated by the
crash thread name `DefaultDispatcher-worker-8`. `consumeSelfPlayLatch`'s
`indexOfFirst`→`removeAt` window races the per-entry expiry `removeAll` and
concurrent consumers on other pool workers.

This run's own log shows a **second member of the same race family**: the
guardian double-fire at 10:52:59.909/.915 — two threads (13606, 13849) both
reported the same stale Tubo Tubo confirmation as an auto-advance and both
ran the full pause/LOST/re-listen sequence (duplicated phase-transition
chains visible at 10:52:59.917–.922). `onSpotifyAutoAdvanced`'s
`autoAdvanceHandled == actualUri` dedup check (`SessionViewModel.kt:2237`)
and its set (`:2251`) are an unsynchronized check-then-act; both threads
passed the check before either wrote. The reopened GRD-01 ticket should
therefore cover the ViewModel's shared mutable session state as a whole
(`selfPlayLatch`, `autoAdvanceHandled`, the ident-streak fields, the
`transition` legality check) rather than just the one list.

### #33 IDC-01: verdict upgraded — the armed gate demonstrably works

Source reading resolves the "hard to reconcile" evidence completely:

- `armIdentCorroboration()` has exactly **one call site** — `onTrackLost`'s
  re-bootstrap (`SessionViewModel.kt:2290`). The natural end-of-track
  re-listen and the auto-advance re-listen never arm the gate, so instant
  actuation there (e.g. Tubo Tubo at 10:52:08.973→.976, first fix after
  Dreams' natural end) is the shipped code's *intended* unarmed cold-start
  behavior, not a gate failure.
- **The armed path visibly worked once in this very run.** The 21 s
  sync-error track-lost at 10:53:04.772 ran `onTrackLost` (LOST→LISTENING,
  arming the gate). The next resolvable fixes were three same-URI
  `0fHbLv7` matches — 10:53:07.810, 10:53:11.369, 10:53:12.692 — and
  actuation happened **only on the third** (AIMING at 10:53:12.694).
  Offset-vs-wall-clock agreement checks pass exactly as coded: Δoffset
  3500 ms vs Δwall 3559 ms (59 ms ≤ 500), then 1560 ms vs 1323 ms (237 ms
  ≤ 500). A uri-less `BILLIE JEAN (Remix)` fix in between (10:53:06.305)
  correctly never reached the streak (`resolveTrackInfo` → null). This is
  a clean, in-the-wild demonstration of the 3-fix corroboration streak.
- **The remaining instant actuations are the fast-switch path**, `room
  changed songs → re-aim` (`SessionViewModel.kt:2066–2083`), which fired 4
  times (10:52:59.357, 10:53:29.467, 10:53:50.356, 10:54:06.254) — each a
  **single-fix** actuation. That path calls `resolveTrack` but nothing arms
  the gate first, so it always actuates immediately. The Billie Jean
  edition ping-pong that constitutes most of this scenario's churn flows
  through this ungated path — which sits squarely inside IDC-01's threat
  model (one wildly-off misrecognized fix can yank a tracking session).
  Filed as a follow-up design question rather than a regression: the fix
  as specified works; the spec's arming coverage is what's in question.
- Doc/code discrepancy: the KDoc (`:851–852`, `:2288–2289`) says the gate
  is armed by "an aim-failure (see aimUntilLanded)", but `aimUntilLanded`
  contains no arming call — aim-failure only arms indirectly if the engine
  subsequently declares track-lost. No `aim gave up` line appears in this
  run, so the path was not exercised either way.

Field verdict for #33 accordingly: **shipped mechanism confirmed working
where armed**; the instrumentation request stands (a `identCorrob: streak
N/3` log line would have made this analysis trivial), and the coverage gap
(fast-switch, natural-end re-listen) is a new PM-facing question, not a
failure of the FT9 fix.

### Anomaly A: stands, unresolved

Nothing in the source reading explains why live `sync err` sat at −315 ms
while the mic and referee independently agreed on ~45 ms; the stable
350–520 ms `zEnd` band and the unbounded player-state staleness
(`ps=…@-78871ms`) remain the two live leads. Note the same `zEnd` ≈ 500 ms
band persisted into Scenario 2's clean fixes (504/544/514 at
10:53:07–12), so it is not a Scenario-1-only artifact. Filed as a new
issue.
