# Field Test 9 — the acoustic suite (CTL-01/02/03) · 2026-08-04

Rig: Pixel 8 "shiba" = room (YouTube, `adb -t 43`), Pixel 10 Pro "blazer" = JoinTheParty
(`adb -t 36`), Epic 9 build (versionName 0.1.0, installed 11:06, not reinstalled today).
Beosound A1 between them → `ffmpeg | lag_analyzer --stream --tempo` (orchestrator-owned,
never restarted), appending to `docs/live_lag_ft9.csv`
(`t_s,lag_ms,peak_ratio,confident,rms_db,comb_ratio,beat_period_ms`). STREAM_MUSIC volume
checked mid-suite via `dumpsys audio`: both phones at index 21/25 (speaker) — equal, though
not confirmed equal at session start (a protocol gap; standardize explicitly before the
next suite).

**Correction to the briefing:** the task brief glossed CTL-02 as "NTP persistence gate."
There is no NTP involved anywhere in this code. Per `core/src/policy/policy.h` and
`backlog-tickets.md`, CTL-02 is `CorrectionPolicy`'s **persistence gate** (tech-req §2.7):
a cluster of ≥3 converged recognition fixes, agreeing within 60 ms over a 20 s span, above
a 125 ms floor, earns ONE correction even inside the widened 350 ms Android deadband. CTL-03
is the **large-correction corroboration hold** (§2.8 Part B): a single fix reading
≥1000 ms is held, not fired, until a second fix corroborates it within 150 ms, else it
expires unfired after 30 s. CTL-01 is the **referee-starvation / Wittenmark sentinel**
(§2.9): agreement starvation for ≥45 s (≥4 windows) or an unconfident-but-valid estimate
persisting ≥20 s with no accepted fix requests a pause-probe (200 ms), rate-limited to one
per 120 s. Also separate from all three: `onLatencyResidual`/CAL-04's `referee: committed`
line is a **calibration-profile refinement** sample (stored trim/latency prior for
*future* sessions) — it does not drive a live correction this session. The task brief
conflated this with CTL-02; they are different code paths and I've kept them distinct
below.

**What the product actually does about self-hearing** (asked live): self-match rejection
(`fix rejected: SELF_HEARING`, the original FT4 continuity guard) + the CTL-01 active
pause-probe + the CAL-04 referee residual sample. There is no reference-PCM echo
cancellation of our own waveform anywhere in this stack — self-hearing is handled entirely
by continuity/agreement heuristics on the recognizer's output, not by subtracting a known
reference signal from the capture.

## Noise floor

- Silent room (pre-existing, at handoff): rms −51…−63 dB, incoherent lag scatter — no
  coherent peak, as expected with zero sources.
- Single-source floor (Vienna alone, Phone A only, CSV lines 164→178, ~20 s): lag_ms
  40–56 ms, median **49 ms**, confident=1, peak_ratio 12–24, rms −18…−20 dB. This session's
  floor for every "at floor" judgment below.

## CSV line markers (chronological)

| Marker | CSV line | Event |
|---|---|---|
| Handoff | 14 | Baseline silent room (orchestrator) |
| First check | 132 | Pipeline still running while docs/code were read |
| Single-source floor window | 164→178 | Vienna alone, floor = 49 ms |
| Test 1 join | 205 | Phone B Join tapped, session starts |
| Test 1 90 s window end | 260 | Nominal monitor window closed |
| — | — | **Phone B `logcat` stream silently died ~11:47:29** (adb did not drop from `adb devices -l`; the tail pipe just stopped writing). Recovered via `logcat -d` dump; ring buffer only reached back to 11:50:19, so **~11:47:29–11:50:19 (~2m50s) of Phone-B log is lost** for Test 1's tail. The mic CSV was never interrupted — that segment is salvaged acoustically, not textually. |
| Dead-air 1 boundary | 389 | Vienna ended, YouTube parked on "Suggested video" interstitial, room silent |
| Test 2 start (Billie Jean confirmed playing) | 414 | |
| Test 2 wrap / dead-air 2 boundary | 596–617 | Billie Jean ended, same interstitial pattern |
| Test 3 attempt A start (My Life) | 617 | Aborted — see below |
| Test 3 attempt A end (My Life ended naturally) | ~836 | End-of-track guardian fired, not a discontinuity result |
| Test 3 attempt B start (Dreams [Extended]) | 836 | |
| Test 3 pre-seek LOCKED steady state | 896 | |
| Test 3 seek executed (confirmed via on-screen "+10" overlay) | 946 | |
| Discontinuity acoustic transition | t_s≈1920 (~row 1921) | Confident ~505 ms plateau collapses to ~45 ms within one 2 s sample |
| Teardown | 1122 | Final marker at write-up time |

## Test 1 — Vienna (CTL-02 / persistence-gate validation)

Driven: Phone A → YouTube search → "Billy Joel - Vienna (Official Audio)", screenshot-confirmed
playing. Phone B: force-stop, launch, Join tapped at 11:46:38. Log non-empty within seconds
(`join → startCapture=ok; recognizer=ACRCloud`). Session used a **cached calibration trim of
+585 ms** (device-detail trim wheel, restored from prior testing), visible on the debug
overlay at Join.

Sequence: MATCH → AIMING → CONVERGING → **LOCKED at 11:47:09** (31 s to lock), `sync
err=-161ms`, held essentially flat through 11:47:19.

**Unplanned mid-test perturbation (human-initiated, not the engine):** at 11:47:22–29 the
trim wheel was manually dragged from +585 ms to 0 ms in three commits (`nudge Δ-160ms`,
`nudge Δ-85ms`, `nudge Δ-340ms`, each followed by a real `seekTo`). This forced
`LOCKED → DRIFTING` and drove `sync err` from −161 ms to −1475 ms as the removed trim
exposed the raw (uncalibrated) offset. **This is not a CTL-02 finding** — it's the
trim's own acoustic effect being demonstrated live. Sub-segment medians:

- **Trim = +585 ms** (CSV t_s 432–454, clean plateau): lag_ms flat at **43 ms** — at floor.
- **Trim = 0 ms** (post-reset): no clean plateau was captured — the room's rms fell from
  −15 dB to −63 dB in the following ~90 s (Vienna ending), so the post-reset segment is
  itself confounded by the room fading out. The CSV alternates between a low mode
  (43–52 ms) and large harmonic-multiple values (1257, 1639, 2361 ms — close to 2×/3×/4× the
  beat_period_ms of ~500–560 ms visible in the same rows), the exact comb-aliasing failure
  mode `field-test-protocol.md` warns about. **I cannot report a clean trim=0 steady-state
  number for Test 1** — the segment never stabilized before the room went quiet.

**CTL-02 (persistence gate) — direct evidence, found later in the same session (Test 3
prep, same LOCKED stretch, trim=0, "My Life"):**

```
12:03:52.719  CORRECTION → seek 164432ms (jump 277ms) e=-172 conf=0.83
```

`|e|=172` is below the 350 ms Android instantaneous deadband — this correction could only
have come from the persistence gate (confirm_floor_ms=125 < 172, corroborated cluster).
**This is a genuine, positive CTL-02 firing.** Two more sub-deadband corrections were
observed later in Test 2 (`e=150` at 11:58:14, `e=87` at 11:59:19) — CTL-02 fired at least
three times across the session. **Verdict: CTL-02 is not inert — it demonstrably closes
small persistent residuals below the instantaneous deadband.**

**The "still ~200 ms behind at steady state" question (live human ear estimate, revised
100→200 ms):** at 12:03:52 (right after the CTL-02 correction above), the referee
(CAL-04, a *different* mechanism — see correction note above) committed:

```
12:03:52.728  referee: committed 41ms residual on speaker
12:04:53.029  referee: committed 47ms residual on speaker
12:05:53.120  referee: committed 41ms residual on speaker
```

Cadence is ~60 s, not the ~20 s the FT8 suite doc describes — worth a ticket note. None
carry the `— DRIFTED` suffix (all read healthy). In the same window the mic CSV alternates
between a **41–45 ms low mode** (agreeing with the referee and with `sync err` ≈ −50 ms) and
a **429–430 ms high mode** whose value is ≈ the segment's own beat_period_ms (~512–516 ms) —
the comb-aliasing signature again. **Two-numbers-rule verdict for this window: engine
(−50 ms) and referee (41–47 ms) and the mic's low mode (41–45 ms) all agree at floor.** The
human's ear and the raw mic high-mode reading disagree with all three. I cannot rule out a
genuine unclosed residual near the human's estimate, but the weight of corroborating
evidence (three independent low-noise readings) says this segment was healthy, and the
200 ms-class perception is most likely explained by comb-harmonic aliasing landing near
one beat period. **This needs a clean re-test on non-repetitive material without a
mid-test trim change to fully resolve — the evidence here is suggestive, not conclusive.**

## Test 2 — Billie Jean (CTL-03 validation)

Driven: Phone A → search → "Michael Jackson Billie Jean official audio", confirmed playing.
Session continued from Test 1 (no fresh Join). ~5.5 minutes of coverage (11:53:39–12:00:23,
room and JTP both audible, rms −18…−25 dB).

**Reproduces FT8's "harmonic churn" finding exactly, at much higher resolution.** Over the
session: **~134 phase transitions** (deduplicated; 148 raw log lines — the same transition
is often logged by 2–3 threads at the same millisecond), **on the order of two dozen
LOST→LISTENING cycles** (18 distinct seconds contained one; 32 raw lines), ACRCloud matched
Billie Jean to **at least 6 distinct Spotify catalog editions** (`5dMuRtYktKL5Bkv5qph75v`,
`1euuAfFtkRzJy489azxfLC` "Long Version", `5ChkMS8OtdzJeqyybCc9R5`,
`0fHbLv7QZDpD2tHqzxOg1e`, `5W23Jb8IrP0CnLs5o9dlFY`, `6vR5u5b8JeRESx5nZaIWx6`), each
edition-flip legitimately forcing `play(uri)` per the code's own trackUri-equality guard.
**Zero true `CorrectionPolicy` `CORRECTION` lines fired during the churn** — every seek in
this window came from the AIMING-phase re-acquisition path (`aim #N → seek…`), a
structurally different, ungated code path. **CTL-03's hold never had an opportunity to
act here**: it gates `SC_EVT_CORRECTION` proposals inside `CorrectionPolicy`, not AIMING
re-acquisition seeks — Billie Jean's failure mode is entirely upstream of CTL-03's
territory. Stable LOCKED periods DID occur (12:56:38–11:59:00-ish, `sync err` 320→138 ms,
drift settling, zero corrections needed — residual stayed inside the widened deadband and
decayed on its own).

**Mic ground truth for Billie Jean is itself compromised.** rms was healthy (−18…−25 dB,
both sources audible) but `lag_ms` alternates between a low mode (40–64 ms) and values at
almost exact integer multiples of `beat_period_ms` (~512–516 ms): 1025/1026 (≈2×),
1637–1661 (≈3.2×), 2046–2133 (≈4×), with `comb_ratio` mostly 1.0–1.7 (near-flat/ambiguous
by the metric CTL-03a added specifically to catch this). Billie Jean's repetitive
bassline defeats both ACR's fingerprint offset recognition *and* the mic's autocorrelation
lag estimate — the same root cause driving two independent symptoms.

**No `large_correction_threshold_ms` (1000 ms) trigger occurred anywhere in the whole
session** (max observed |e| on any CORRECTION line: **901 ms** — `12:15:28.539 CORRECTION →
seek 149842ms (jump 894ms) e=-901 conf=0.78`, which is the correction fired toward the
wrong-song "Everywhere" timeline during the out-of-catalog episode below — 99 ms short of
the hold threshold on the session's single worst fix). **CTL-03's
corroboration-hold path was never exercised by this suite at all — field validation of
CTL-03 specifically is still outstanding**, independent of the churn story above.

### Correction churn at convergence (human-reported "3–4 corrections then an interfering 5th")

Full CORRECTION table, Test 2 stabilization window:

| Time | seek | jump | e | conf | Below 350 ms deadband? |
|---|---|---|---|---|---|
| 11:57:26.885 | 230921ms | −269ms | 633 | 0.80 | No — instantaneous |
| 11:58:14.260 | 278269ms | +79ms | 150 | 0.83 | **Yes — CTL-02 persistence gate** |
| 11:58:18.343 | 282000ms | −287ms | 542 | 0.57 | No — instantaneous |
| 11:58:22.419 | 285589ms | −291ms | 547 | 0.74 | No — instantaneous |
| 11:59:19.523 | 342670ms | +64ms | 87 | 0.82 | **Yes — CTL-02 persistence gate** |

Given ~10 s median-lag windows around these are dominated by Billie Jean's comb ambiguity
(see above), I can't cleanly certify mic-lag before/after each one individually. What the
log rules out cleanly: **none of these five are CTL-03 holds** (all `|e|<1000`), and two
are unambiguously CTL-02 (sub-deadband `e`). The human's perceived "interfering 5th
correction" most likely corresponds to one of the three instantaneous ones (633/542/547 ms)
landing while the *audible* impression was already close — plausible given Billie Jean's
beat period (~513 ms) is close to these jump sizes, i.e. a correction can be numerically
"correct" by the estimator's error term while *sounding* like it jumped a whole beat.
**Recommendation (future ticket, not made tonight): a convergence deadband/settling
hysteresis — once |error| is floor-class, require corroboration or a larger threshold
before firing, mirroring CTL-03's own logic but at a lower threshold.**

### Audible restart on re-acquire

Counted 12 real `play(uri)` restarts vs. 6 correctly-guarded `already loaded; resume+aim
(no restart)` resumes in Test 2's window. Verbatim example of the guard bypass mechanism (not a null
`lastKnownPlayerState` bug — genuine URI churn):

```
11:55:30.463  Spotify connected → play spotify:track:0fHbLv7QZDpD2tHqzxOg1e
11:55:31.898  Spotify connected → play spotify:track:6vR5u5b8JeRESx5nZaIWx6
11:55:33.302  Spotify connected → play spotify:track:0fHbLv7QZDpD2tHqzxOg1e
```

Three restarts in 2.8 s, oscillating between two URIs — each restart legitimately fired
because `trackUri` really did differ from what was loaded (ACR kept re-resolving to
different editions). **A second, compounding mechanism found in the raw log, not
hypothesized in the brief:** each of our own `play(uri)` calls is picked up by the
player-state watcher as if it were unprompted:

```
11:55:31.673  Spotify auto-advanced to spotify:track:0fHbLv7QZDpD2tHqzxOg1e — pausing to hear the room
11:55:31.673  pause()
11:55:31.673  phase: CONVERGING → LOST
11:55:31.673  phase: LOST → LISTENING
11:55:31.673  phase: LISTENING → MATCHING
```

The "Spotify auto-advanced" guardian (built for genuine end-of-track auto-advance) cannot
distinguish "Spotify moved on its own" from "we just told it to play something else" —
every self-caused `play(uri)` during churn re-triggers this guardian, stacking an *extra*
pause+re-listen cycle on top of the re-acquisition already in progress. This is the
concrete mechanism behind the audible restarts the human heard, on top of the genuine
edition-flip restarts. **Recommendation (future ticket): the auto-advance detector needs
to suppress itself around a URI change we just initiated**, not just get the play-vs-resume
branch right.

## Test 3 — Discontinuity (CTL-01 validation)

**Attempt A (My Life) aborted.** First seek attempt via double-tap could not be
screenshot-confirmed (the player UI collapsed to the non-interactive description view
before/after the tap, so no "+10" overlay or before/after position was captured — an honest
instrumentation miss, not a claimed seek). By the time logs were checked, both the room
video and the Spotify track had reached natural end-of-track around the same real time
(`track ending — pausing before Spotify picks the next one`), confounding any
discontinuity-specific read. This attempt is excluded from the CTL-01 verdict.

**Attempt B (Fleetwood Mac – "Dreams [Extended] [Seamless]", 8:47, tapped from the
autoplay-suggestion "Play now" card — driven by me, not YouTube autoplay, not the human).**
Room source recorded as **Dreams (extended version)** — materially longer than the ~4:17
studio track, which matters for the ~6:40 event below.

Session re-locked cleanly (`LOCKED` at 12:09:46, `sync err` 67→114 ms, drift 800 ppm).
Pre-seek steady state (CSV lines 896, ~60 rows): **lag_ms flat at 503–507 ms**, with
`comb_ratio` climbing to 3–4.3 (high confidence, not the ambiguous ~1.0 seen with Billie
Jean) — a real, confidently-measured constant, matching the human's live "still slightly
behind" call in direction if not exact magnitude, and roughly matching FT8's own
Dreams/Vienna-class "constant echo inside the deadband" finding (FT8 measured ~285 ms on a
different floor; floor moves per session, per protocol).

**The seek:** double-tap on the right side of the expanded player at video position
2:56, confirmed via the on-screen **"+10" overlay** — screenshot-verified, not assumed.
CSV marker 946.

**CTL-01-relevant sequence observed:**

```
12:12:52.652  phase: MATCHING → LOST
12:12:52.652  phase: LOST → LISTENING
12:12:52.652  phase: LISTENING → MATCHING
12:12:53.919  phase: MATCHING → AIMING
12:12:53.925  fix rejected: LOW_CONFIDENCE
12:12:54.195  phase: AIMING → CONVERGING
12:13:04.163  fix rejected: SELF_HEARING   (offset=141020, zEnd=482)
12:13:09.098  fix rejected: SELF_HEARING   (offset=146020, zEnd=491)
12:13:14.213  fix rejected: SELF_HEARING   (offset=151040, zEnd=462)
12:13:19.220  MATCH ✓ 'Dreams (2004 Remaster)' offset=155880ms  ← accepted, not rejected
12:13:49.257  CORRECTION → seek 186917ms (jump -139ms) e=382 conf=0.84
```

**Verdict: this discontinuity was absorbed entirely by the original FT4 self-match guard
(three consecutive `SELF_HEARING` rejections over ~15 s), not by CTL-01's referee sentinel
or active probe.** No `player: … paused=true` blip attributable to a pause-probe appears
in this window (only the ordinary AIMING-phase seeks), and no `kTrackLost`-class event
fired. This is consistent with the configured constants: the self-match rejection window
here (12:13:04→12:13:19, ~15 s) never reached `probe_turnoff_dwell_ns` (20 s) or
`referee_starve_ns` (45 s) — **the base guard resolved it before CTL-01's dwell timers
could arm.** I did not observe CTL-01's `SC_EVT_ACTIVE_PROBE`/pause-resume/verdict
sequence firing anywhere in today's suite. This is a genuine gap in today's evidence, not
a negative result — the layered defense worked, but the layer specifically under test
(CTL-01) was never the one that had to act.

**Acoustic signature of the discontinuity:** the mic's confident 503–507 ms plateau
collapsed to a 40–55 ms plateau within a single 2 s sample (CSV t_s≈1919→1921), coincident
with the self-match rejection episode, and **stayed near floor for the following ~80 s**
even before the small 12:13:49 CORRECTION (jump only −139 ms — nowhere near enough to
explain a ~460 ms mic-side improvement on its own). The most likely explanation: the
AIMING-phase re-acquisition (which recomputes an absolute room-position estimate rather
than incrementally trimming) accidentally re-anchored the session much closer to true
sync than the pre-seek "healthy-looking but 505 ms off" LOCKED state had been. If true,
this is a notable, non-obvious finding: **forcing a discontinuity may have fixed a
persistent calibration-constant problem that the passive correction path (CTL-02) was not
closing on its own** — offered as a plausible read of the data, not a certainty; I don't
have independent instrumentation to confirm the mechanism.

## By-ear event log (Dreams segment, human calls vs. log evidence)

Mapped from one confirmed anchor (video 2:56 at the pre-seek screenshot, ~12:11:1x) plus
the confirmed +10 s seek; ±30–60 s uncertainty per the ±30s tolerance given.

| Human call | Est. wall time | Log evidence | Read |
|---|---|---|---|
| ~4:00: correction while on-beat, phase-offset ~1 beat | 12:13:49.257 (best candidate; only CORRECTION in range) | `CORRECTION → seek 186917ms (jump -139ms) e=382 conf=0.84` | `e=382` barely clears the 350 ms deadband (an ordinary instantaneous correction, not CTL-02 or CTL-03). Jump (−139 ms) is not a clean multiple of the ~500 ms beat period visible in the CSV at that time — the numbers don't confirm a "whole-beat" harmonic-lock mechanism; more likely an ordinary correction landing during residual post-seek instability, perceived as beat-offset. |
| ~4:40: audible restart from 0:00 | 12:12:54.159 | `Spotify connected → play spotify:track:3n3EF98mYNe6r3iATnzyTo` | Restart to an *alternate Dreams catalog edition* — legitimate URI-differs restart per the code's own guard, immediately downstream of the seek/self-hearing episode above. |
| ~6:40: restart + wrong song identified | 12:15:23.411 | `Spotify connected → play spotify:track:0CQ2EPgBXhJEnTaxbb4rWt` after `MATCH ✓ 'Everywhere (2002 Remaster)'` | See below — reclassified as out-of-catalog room-audio handling, not simple misrecognition. |

### Event 3, reclassified: out-of-catalog room audio, not misrecognition

Per the live correction: the room was playing the **extended/seamless** mix, which runs
past the ~4:17 studio track and — at the ~6:40 mark — is plausibly playing material (an
extended outro/bridge, a DJ-blended transition) that has **no valid position in any
canonical Spotify catalog track**. The full sequence around the event:

```
12:15:02.787  aim gave up after 4 attempts — estimator will report the error
12:15:03.232  sync err=763715ms drift=353ppm conf=0.12        ← garbage/stale value, not a real reading
12:15:06.829  MATCH ✓ 'Everywhere (2002 Remaster)' offset=127020ms   [fixdbg zEnd=233]
12:15:06.834  fix rejected: SELF_HEARING
12:15:11.158  MATCH ✓ 'Everywhere (2002 Remaster)' offset=132040ms   [fixdbg zEnd=213]
12:15:11.160  fix rejected: SELF_HEARING
12:15:16.394  MATCH ✓ 'Everywhere (2002 Remaster)' offset=137060ms   [fixdbg zEnd=203]
12:15:16.396  fix rejected: SELF_HEARING
12:15:21.269  MATCH ✓ 'Everywhere (2002 Remaster)' offset=142080ms   [fixdbg zEnd=54]  ← accepted
12:15:21.272  phase: CONVERGING → LOST → LISTENING → MATCHING
12:15:23.112  MATCH ✓ 'Everywhere (2002 Remaster)' offset=143800ms   [fixdbg zEnd=44]
12:15:23.115  fix rejected: LOW_CONFIDENCE
12:15:23.411  Spotify connected → play spotify:track:0CQ2EPgBXhJEnTaxbb4rWt   ← restart to the wrong song
```

(`zEnd` values are quoted from each MATCH's paired `fixdbg` line, not printed on the MATCH
line itself.)

Confidence/score is not printed on the `MATCH` line itself (only offset+URI); what IS
visible is `zEnd` (the recognizer-vs-local-timeline discrepancy) falling from 203–233 ms
on the three *rejected* attempts to 44–54 ms on the two that got through. **The self-match
guard correctly rejected the same wrong match three times in a row** — it only stopped
rejecting once the fix looked locally self-consistent (low `zEnd`) against JTP's own
already-uncertain position (note the `aim gave up` / garbage-763-second-error episode two
seconds earlier — JTP's own timeline was itself unreliable at that moment). This is exactly
the reclassification the human's correction points at: **the defect is not "picked the
wrong song," it's that a sustained run of low-margin matches, immediately following a
track-lost/aim-failure, was eventually accepted once it became self-consistent rather than
independently corroborated against the room.** The seven consecutive `Everywhere` matches
(12:15:06–12:15:37, seven matches over 31 s) never disagreed with each other, but nothing
in this path re-checks agreement *against room continuity* the way CTL-02's persistence
gate does for corrections — it only checks disagreement against our own prior estimate.
**Recommendation (future ticket, no code change made): after a track-lost/aim-failure,
widen the corroboration requirement for the next several fixes (a CTL-02-style N-of-M
agreement) before acting on ANY match — including a fresh `play(uri)` — rather than
accepting on the first fix that merely stops looking self-inconsistent.**

## CTL-01 duck-tier promotion verdict

**`duck_tier_first = true` promotion is NOT authorized by this evidence.** Today's suite
never observed CTL-01's own trigger-and-probe path fire at all — the one clean
discontinuity test (Test 3, attempt B) was resolved by the pre-existing FT4 self-match
guard before either of CTL-01's dwell timers (20 s Wittenmark, 45 s referee-starvation)
could arm. Zero `SC_EVT_ACTIVE_PROBE`-attributable pause/resume events were observed; zero
`kTrackLost`-driven re-listens attributable to the referee sentinel were observed. The
CTL-01a device-pass acceptance criteria (§2.12's field-sequencing note: "the CTL-01 device
pass runs first with the pause probe as shipped — the duck becomes the default tier only
after the triggers are field-proven on-device") are **not met** — the triggers were never
field-proven at all today, positively or negatively. **Recommendation: re-run Test 3 with
a scenario that outlasts the base guard** — e.g. force TWO discontinuities in quick
succession (so the base guard's window doesn't fully resolve before the second hits), or
force a longer room silence (>45 s) while LOCKED with no accepted fixes, to actually arm
CTL-01's dwell timers and observe the probe fire. Until then this is honestly "not yet
tested," not "tested and passed."

## Recommendations summary (future tickets, no code changed tonight)

1. Convergence deadband/settling hysteresis for near-floor corrections (churn-at-convergence finding).
2. Auto-advance detector should suppress itself around a URI change it just initiated (restart-on-reacquire finding).
3. Widen corroboration requirement for the fixes immediately following a track-lost/aim-failure, before accepting ANY match (out-of-catalog room-audio finding).
4. Standardize STREAM_MUSIC volume across both phones explicitly before a suite starts (not verified equal at Test 1's start today).
5. Recalibrate the phone-speaker route's stored profile AFTER this suite, not mid-suite (per live orchestrator guidance) — the Test 1 trim experiment intentionally left the profile at trim=0; the pre-existing +585 ms trim should be re-established or re-measured before the next test session.
6. Re-run Test 3 with a scenario long/frequent enough to actually arm CTL-01's dwell timers before drawing a pass/fail conclusion on duck-tier promotion.
7. Re-run Test 1's CTL-02 "is the mic still ~200ms off at steady state" question on non-repetitive material, without a mid-test manual trim change, to remove the comb-ambiguity confound.

## Known gaps / honesty notes

- ~2m50s of Phone-B logcat lost mid-Test-1 (streaming pipe silently died, not a device
  disconnect per `adb devices -l`); recovered via `logcat -d` dump but the ring buffer
  didn't reach all the way back. Mic CSV coverage was continuous throughout — no acoustic
  data was lost, only some engine-side text commentary.
- Test 3 attempt A (My Life) was aborted for a genuinely unconfirmed seek and a
  confounding natural track-end; excluded from all verdicts above.
- No persisted-trim-blob (`nudge_store.preferences_pb`) byte-level read was performed this
  session (time-boxed out); the trim story above relies on the on-screen TRIM readout and
  logcat `nudge Δ`/`engineSetpoint` lines instead, which were sufficient to reconstruct the
  585→0 sequence.
- Per the standing rule: zero code or config changes were made. This file and the
  scratchpad logs/screenshots under
  `scratchpad/ft9/` are the only artifacts produced. No git commit was made.
