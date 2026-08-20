# Field Test 11 — fix-validation re-run of FT10 · 2026-08-20

**Scope.** Re-run of FT10 against the three fixes shipped since: #32 GRD-01
concurrency fix (`5b6ea62`), #36 CTL-05 anchor fix (`262a459`), #37 IDC-02
gating (`30d957c`). Also seeking first field evidence for #29 CTL-02, #34
CTL-04, #28 CTL-01, #31 INT-06.

## Rig

- **Phone B** (Pixel 10 Pro, `blazer`, runs JoinTheParty): `-s
  192.168.86.104:33733`. Package `com.jointheparty.app`, `versionName
  0.1.0`, `lastUpdateTime 2026-08-15 08:48:20` — matches the orchestrator's
  pre-launch verification exactly.
- **Phone A** (Pixel 8, `shiba`, room source, YouTube): `-s
  192.168.86.107:40367`.
- **Geometry** (human-confirmed pre-launch): Pixel 10 (Phone B) on the
  LEFT, Beosound A1 mic in the CENTER, Pixel 8 (Phone A) on the RIGHT,
  ~1 ft apart. Not rearranged during the run.
- **Duplicate transports**: `adb devices -l` at rig-check time showed only
  the two intended `IP:port` entries. An mDNS ghost for Phone B
  (`adb-57161FDCH000BX-...-tls-connect._tcp`) appeared later in the run
  (observed ~18:07) — never addressed; every command throughout used
  explicit `-s IP:port`.
- **Keyguard**: both phones confirmed `isKeyguardShowing=false` pre-flight
  via `dumpsys window`. `screen_off_timeout` was already `1800000` (30 min)
  on both phones at rig-check — prior value recorded, unchanged (already at
  the target value, so no teardown restore needed unless it was 1800000
  originally by coincidence from a prior session; flagged honestly since I
  cannot independently confirm this was the pre-FT11 default vs. a leftover
  from an earlier session).
- **Bluetooth route**: Phone B's history showed an A2DP headset connected
  15:25–17:23 (well before this run) and disconnected by rig-check time
  (`mConnectionState: STATE_DISCONNECTED`); live `dumpsys audio` volume
  group showed `Devices: speaker` for `AUDIO_STREAM_MUSIC`. Phone A showed
  no BT connection history. No `svc bluetooth disable` needed — route was
  already clean.
- **Calibration profile** (read-only, `nudge_store.preferences_pb`,
  decoded): `calibration_profile:speaker` — `latencyMs=173`,
  `method=MEASURED`, `drifted=false`, 25 `refereeSamples`, residuals
  **40–58 ms** throughout (healthy, at-floor, one sample newer than FT10's
  24). `trim:speaker=0`, `setpoint2:speaker=0` (confirmed live in-app: TRIM
  showed `+0 ms` on Phone B's calibrate screen at join). **Decision**: kept
  as-is, deliberately — profile already healthy/at-floor and this run tests
  product behavior including the self-correction path itself (CTL-02),
  which the protocol says should NOT be masked by a fresh recalibration.
- **Mic**: dshow name confirmed exactly `Headset Microphone (Beosound A1
  2nd Gen Hands-Free)`.
- **DLL trap hit and fixed**: `build/core/lag_analyzer.exe` was missing
  `libc++.dll`/`libunwind.dll` next to the binary (despite the build-env
  doc's claim they "sit permanently" there) — first pipeline launch failed
  with `error while loading shared libraries: libunwind.dll`. Copied both
  from `C:/Users/RBILLC/tools/llvm-mingw-20260616-ucrt-x86_64/bin/` into
  `build/core/` and relaunched successfully. Flagged for the build-env doc.
- **Pipeline**: `ffmpeg | lag_analyzer.exe --stream --rate 48000
  --max-lag-ms 2500 --tempo > docs/live_lag_ft11.csv`, confirmed growing
  before any scenario. Pipeline start ≈17:57:14 (derived from a wall-clock/
  `t_s` correlation check, not the literal launch command timestamp).

## Noise floor

`docs/live_lag_ft11.csv` lines 15–23 (`t_s` 35–51): **lag_ms 58 ms**
coherent, `rms_db` −55 to −56 dB, `confident=1` throughout, before
destabilizing into incoherent scatter as the window continued (same
documented shape as FT9/FT10's silent-room behavior). **Floor for this
session: ~58 ms.**

## Scenario 1 — Dreams (#29 CTL-02, #34 CTL-04, #36 CTL-05)

Driven: Phone A → YouTube search → "Fleetwood Mac - Dreams (Official
Audio)", confirmed playing 0:02/4:18 (screenshot). Phone B: force-stop,
launch, Join tapped ~17:59:50. `jtp_ft11.log` non-empty within seconds.
Sequence: `IDLE→LISTENING→MATCHING` → `AIMING` (18:00:04.110) →
`CONVERGING` (18:00:05.015) → **`LOCKED` 18:00:27.752**
(`sync err=244ms...LOCKED` first printed 18:00:28.373). TRIM confirmed
`+0 ms` live in-app screenshot at join.

**New instrumentation confirmed live**: every `fixdbg` line now carries
`skew=<value>` (CTL-05 §6.3) — observed `skew=0.0` on every fix throughout
S1 and S2 (never a nonzero value recorded this run).

### Correction trajectory (raw evidence)

Four persistence-gate corrections during the LOCKED window, all
sub-350 ms (deadband floor, consistent with CTL-02's mechanism):

| # | Time | Correction | e | Post-seek `sync err` | Recovery |
|---|---|---|---|---|---|
| 1 | 18:00:57.799 | seek 87339ms (jump −25ms) | 257 | −19ms @ 18:00:58.410 | clean |
| 2 | 18:01:22.025 | seek 111289ms (jump −139ms) | 235 | −0ms @ 18:01:22.091, held 0–2ms through 18:01:31 (~9s) | clean, referee agreed (see below) |
| 3 | 18:02:01.142 | seek 150044ms (jump −230ms) | 217 | −13ms @ 18:02:02.168 | clean |
| 4 | 18:02:25.313 | seek 173806ms (jump −312ms) | 306 | −9ms @ 18:02:25.864, then −121ms after a zEnd=50 fix @ 18:02:29.421 | clean, no stuck band |

(`scratchpad/ft11/jtp_ft11.log`, grep `CORRECTION` — full context around
each line quoted verbatim in the raw log.)

**`referee: committed 46ms residual on speaker`** fired at 18:01:28.255
(mid persistence-gate-fire-#2's near-zero window) and again at 18:03:48.513
(right before natural end) — both matching the mic's own near-floor
readings at those times.

**Mic/CSV cross-check**: across the entire correction-cycle window (CSV
lines 103–154, `t_s` 215–319, spanning all four corrections),
`lag_ms` stayed **40–87 ms** (mostly 40–54 ms), `confident=1` throughout —
acoustic reality never left the floor, even while the engine's own `sync
err` oscillated between ~0 ms and ~300 ms four separate times.

**Live human observation** (from the desk, verbatim, timestamped against
the log): "almost in sync but just a hair behind" (~18:01, matching
err≈245–300ms) → "now it corrected to be much closer in sync" (~18:01:22,
matching correction #2's near-0 landing) → "but then it corrected again and
its off" (~18:01:41–51, matching the chronic-bias fix at 18:01:41.170
pushing err back to 237ms) → "now behind by more than a beat" (~18:03:10,
matching the drift-clamp episode below). Independent, real-time
corroboration of the engine/mic gap described below.

### #29 CTL-02 — PASS on stated criterion, WITH a caveat this run resolves and a new one it surfaces

All four corrections are sub-350ms persistence-gate fires (e=257/235/217/306,
all below the 350ms instantaneous deadband). **Unlike FT10's Anomaly A**,
`sync err` DID visibly track down toward the mic/referee floor after every
single correction (briefly touching 0, −9, −13, −19, −121 ms) rather than
flipping to a stable wrong-anchor band. **Verdict: PASS**, and FT10's acute
Anomaly A mechanism (a single fix instantly re-anchoring the guard, then
3 consecutive genuine-timeline fixes rejected SELF_HEARING before recovery)
did **not** reproduce — zero `fix rejected: SELF_HEARING` lines anywhere in
this entire run (S1 or S2).

**New caveat, honestly flagged**: the floor-landing never held. Each
near-zero window lasted only ~9–20s before the next fix's chronic ~350–520ms
`zEnd` bias (the *other*, unconfirmed half of FT10's Anomaly A — see
`docs/ctl05-investigation.md` §5) pushed `sync err` back up to 200–300ms,
triggering the next persistence-gate correction. This is a repeating cycle,
not a single stuck band — CTL-05's guard-mis-anchoring fix appears to hold,
but the chronic bias it was never meant to address (§5's "standing half")
is still live and still drives visible churn.

### Anomaly A' — drift=800ppm clamp reproduces via a DIFFERENT path than FT10

At 18:03:10.115, `drift` hit and held the `800ppm` clamp — and stayed
pegged continuously through natural end and into the following re-listen,
last observed at 18:04:04.524ish (~43+ seconds), while `sync err` climbed
monotonically 181→228ms and `ps=...@-73832ms` (player-state staleness) grew
unbounded, the same qualitative signature as FT10's tail. **Critically,
this happened without a single SELF_HEARING rejection anywhere nearby** —
the clamp was hit through ordinary *accepted* fixes carrying the chronic
zEnd bias, not through the guard's mis-anchoring cascade CTL-05 targeted.
This means the drift-clamp symptom has at least two distinct triggers: the
mis-anchoring cascade (CTL-05's target, not reproduced this run) and
sustained accepted-fix chronic bias alone (reproduced here, not CTL-05's
territory). **New anomaly, flagged for follow-up, not a CTL-05 regression.**

### #34 CTL-04 (settling hysteresis) — still NOT OBSERVABLE, same instrumentation gap as FT10

`docs/ctl05-investigation.md` §7's recommended `settled` visibility
(piggyback on `sc_evt_sync_estimate_t`) was **not shipped** — confirmed via
`grep -i settl` on the full capture, zero hits. Combined with the chronic-
bias churn above (sync err's best sustained low window was ~9s at 0–2ms,
never a confirmed multi-window post-verify landing), **cannot confirm
`settled_` was ever durably entered**. Same category as FT10:
**NOT OBSERVED / INCONCLUSIVE**, blocked by both the missing instrumentation
and (this run, additionally) the chronic-bias precondition issue itself.

### #36 CTL-05 — acute mechanism: PASS (not reproduced); a related-but-distinct drift-clamp path remains

Zero `SELF_HEARING` rejections in the entire run. All four post-seek
windows recovered cleanly toward floor. The specific cascade
(`docs/ctl05-investigation.md` §2: one coincidental accept re-anchors, then
3 genuine-timeline fixes rejected in a row before recovery) did not occur —
either because the fix works, or because no genuinely-conflicting second
recognizer timeline happened to arise this run (this distinction cannot be
resolved from black-box field evidence alone; the implementation review's
own unit tests already regression-pin the mechanism directly). **Verdict:
field-consistent with the fix holding — no counter-evidence.**

Scenario ended naturally at 18:03:49.050 (`track ending — pausing before
Spotify picks the next one`), screenshot-confirmed "Suggested video: The
Chain" interstitial. LOCKED held 18:00:27.752 → 18:03:49.052 (3m21s).

## Scenario 2 — Billie Jean (#32 GRD-01, #37 IDC-02, #30 CTL-03)

Phone A switched to "Michael Jackson - Billie Jean (Official Video)"
(18:04:30), confirmed playing 0:25/4:56 (screenshot). Phone B was already
re-listening (MATCHING, from S1's natural end) — no fresh Join needed.

### #37 IDC-02 — both gate signatures captured

- **`identCorrob: streak 1/2`** at 18:05:09.555
  (`uri=spotify:track:1euuAfFtkRzJy489azxfLC`) — the natural-end reduced
  gate (decision 2), confirmed field-armed exactly as designed immediately
  after S1's natural end.
- A second `LOST→LISTENING→MATCHING` cycle at 18:05:24.512–.513 (cause not
  independently diagnosable from black-box logs — likely a track-lost
  timeout while stuck unresolved in MATCHING) re-armed the **full 3-fix**
  gate: **`identCorrob: streak 1/3`** first at 18:05:39.252, recurring with
  different URIs each time (edition churn resetting the streak), reaching
  **`streak 2/3`** once (18:07:25.544,
  `uri=spotify:track:5ChkMS8OtdzJeqyybCc9R5`) before the stall below cut the
  run short.
- **No fast-switch `candidate pending` lines observed** — the session never
  left MATCHING to reach an active tracking phase where the fast-switch
  branch applies, so this half of #37 got no exercise this run.
- **No actuation on any single misrecognized fix** — a `'Medley
  (Originally Performed By Michael Jackson...) {Karaoke Audio Version}'`
  wild misrecognition at 18:06:32.343 (real Spotify URI
  `5f8EzLxxNf8xAV8Ndwqsy3`, correlated with a CSV lag spike to
  2056/1028 ms) was correctly gated off (`fix rejected: LOW_CONFIDENCE`,
  streak reset) — never actuated.

### #32 GRD-01 — zero crashes; a real stall traced to a Spotify disconnect, then a clean teardown

`logcat -b crash -d` was checked repeatedly through the churn window: empty
of `com.jointheparty.app` throughout. Process PID 10855 never changed
(no relaunch, no crash-restart) until a deliberate relaunch for S4.
**PASS on the crash criterion.**

**Stall, root-caused (not left as a mystery).** At 18:07:32.098 the
recognition loop stopped producing ANY further log activity — no more
`capture:`, `MATCH`, `fixdbg`, or phase lines. Verified NOT a crash or ANR
in the moment: `pidof` returned the same PID throughout; `isForeground=true`
unchanged; the session notification unchanged; `ping 8.8.8.8` from the
device succeeded (20–22ms); `mWakefulness=Awake`; `/data/anr/` held only a
stale 2026-08-12 trace; `logcat -d --pid=10855` showed zero lines (not even
system noise) for the entire stall. Phone A's room video kept playing
normally throughout (screenshot-confirmed, timeline advancing). **5m17s
later**, at 18:13:49.156, the log resumed with a single line:
`AppRemote onFailure: SpotifyConnectionTerminatedException: null` —
followed by `phase: MATCHING → IDLE` and `pause()` at 18:14:14.132.
**The stall was a dead/hung Spotify App Remote connection that took over
five minutes to surface its own failure callback**, not an app-level
deadlock: once the failure was detected, the app tore the session down
cleanly (no crash, service released — `dumpsys activity services` showed
`(nothing)` afterward, no orphaned mic/FGS). **This is a genuinely new
finding, distinct from FT10's crash**: GRD-01's concurrency fix is not
implicated (no race signature, no double-fire, no exception) — the failure
mode is a slow/silent Spotify SDK connection death with no apparent timeout
on JTP's side, worth its own follow-up ticket (recognition loop has no
independent liveness timeout that would surface this faster than Spotify's
own, very slow, failure callback).

### #30 CTL-03 — NOT RUN

The session never reached a stable `LOCKED` phase on Billie Jean (best
progress: `identCorrob: streak 2/3`, never resolved) before the stall above
ended productive observation. Consistent with FT10's own finding on this
scenario. **Verdict: NOT RUN**, blocked by (a) Billie Jean's churn
behavior never stabilizing into a lock, consistent with FT9/FT10, and (b)
the stall ending the session's forward progress before churn had a chance
to settle.

## Scenario 4 — #31 INT-06 (foreground service chain)

Fresh session (PID 13224, relaunched after S2's clean IDLE teardown):
join → `LISTENING→MATCHING` (18:15:46.130) → matched "Stayin' Alive" (Bee
Gees, YouTube autoplay had advanced Phone A's room source) → `AIMING`
(18:15:58.421) → `CONVERGING` (18:16:01.778) → **`LOCKED`** 18:16:24.998.
`isForeground=true foregroundId=1 types=0x00000080` (MICROPHONE)
confirmed via `dumpsys activity services` before screen-off.

### Screen off (3 min soak)

`input keyevent KEYCODE_SLEEP` at 18:16:17; `dumpsys power` confirmed
`mWakefulness=Dozing` within 2s. Session kept ticking throughout — logcat
continued producing `capture:`/`MATCH`/`sync err` lines the entire
screen-off window (no gap), confirmed via non-visual `dumpsys` polls
(`isForeground=true` unchanged mid-soak) so as not to wake the screen.

**Bonus field evidence for #36 CTL-05, captured incidentally during the
soak.** A `CORRECTION → seek 126541ms (jump -478ms) e=468` at 18:17:10.086
(phase `LOCKED → DRIFTING`) was followed by exactly **two** consecutive
`fix rejected: SELF_HEARING` (18:17:14.416, offset=129500; 18:17:19.269,
offset=134340) — and raw-offset arithmetic confirms these two rejected
fixes agree with each other (predicted 134500 vs actual 134340, 160ms
apart, comfortably inside `kRoomContinuityGateMs`=500ms) — before the
**third** fix (18:17:24.598, offset=139520, agrees with the second fix to
within 171ms) was accepted outright, with `sync err` landing at −37ms, not
a stuck band. **This is a clean, textbook demonstration of CTL-05's
post-seek two-fix corroboration mechanism**: exactly the "resolve within
~2 fixes, not 3+ rejections then a drift peg" criterion S1 was built to
test, caught live here instead. `drift` (pegged at 800ppm since the prior
lock) recovered to 19ppm by 18:17:55, then to −526ppm shortly after — moving
freely, not stuck at the clamp. Phase returned to `LOCKED` at 18:17:42.215.

### Task-swipe and Stop-action — NOT RUN

The human operator paused the test physically at ~18:18 (mid-way through
the 3-minute screen-off soak, verbatim: "I had to pause the test"), and the
room source went quiet shortly after (`capture: ... peak=0.06`, `ACR
status 1001 'No result'` repeating, matching a paused/muted room). Per the
honesty rules, I stopped driving further adb actions at that point rather
than continue against a rig the human had just told me they'd interrupted.
**Task-swipe-from-recents and the notification Stop-action were never
exercised.** What WAS confirmed clean before the pause: `isForeground=true`
held continuously from before screen-off through the pause point (~3m18s),
the session stayed `LOCKED` (screen-off did not kill or degrade the
session), and the crash buffer remained empty throughout. **Verdict:
PARTIAL** — the screen-off leg of INT-06 is solid evidence (service
survives screen-off, session keeps ticking); task-swipe and Stop-action are
**NOT RUN**, reason: human-paused rig, not a failure.

## Scenario 3 — #28 CTL-01: NOT RUN

Never attempted. S1 locked but ended naturally before a discontinuity step
was scheduled against it; S2 never reached a stable lock before its
Spotify-disconnect stall; S4's lock (reached incidentally while testing
INT-06) was still mid-soak when the human paused the test. No window of
"LOCKED + free to force a room discontinuity" ever opened. Zero
CTL-01-relevant evidence gathered.

## Teardown

- Pipeline (`ffmpeg | lag_analyzer.exe`) stopped cleanly at 18:19:~40 after
  confirming no further CSV growth needed. Final `docs/live_lag_ft11.csv`:
  **654 lines** (653 data rows), continuous `t_s` 0→1343 (~22.4 minutes),
  never interrupted mid-run (only the one restart at the very start to pick
  up the copied DLLs, before any scenario began).
- Phone B `logcat -s JTP` capture (`scratchpad/ft11/jtp_ft11.log`, **866
  lines**) — left running; not explicitly stopped, harmless since the test
  ended by human pause rather than teardown sequence.
- `logcat -b crash -d` on Phone B checked at multiple points through the
  run and again at teardown: **empty of `com.jointheparty.app` throughout
  the entire session** — zero crashes end to end, across S1's full lock
  cycle, S2's churn + stall + clean-teardown, and S4's lock + screen-off
  soak.
- `screen_off_timeout` was already `1800000` (30 min) on both phones at
  rig-check time (not changed by this run, so nothing to restore).
- adb left connected to both phones: `192.168.86.104:33733` (Phone B) and
  `192.168.86.107:40367` (Phone A), per the standing instruction. Phone B's
  screen was left in whatever state the pause found it (screen was off from
  the S4 soak at last check — not woken back up, per not wanting to disturb
  a rig the human said they'd paused).
- No git commit made; no GitHub issue actions taken (orchestrator's job).

## Verdict summary

| Issue | Verdict | Evidence pointer |
|---|---|---|
| #28 CTL-01 | **NOT RUN** | No LOCKED-and-free window ever opened this run |
| #29 CTL-02 | **PASS** (on stated criterion), with a chronic-bias caveat | `jtp_ft11.log` — 4 sub-350ms persistence fires in S1 (e=257/235/217/306), each recovering `sync err` toward 0 (unlike FT10); floor never held for more than ~9-20s before the next chronic-bias fix pushed err back up |
| #30 CTL-03 | **NOT RUN** | Neither S2 (Billie Jean, stalled before locking) nor S4 (locked, but human-paused before a forced-seek step was reached) produced a free LOCKED window |
| #31 INT-06 | **PARTIAL** | Screen-off soak (18:16:17→~18:19:26, ~3m9s): service + session survived clean, zero crashes, `isForeground=true` held throughout. Task-swipe and Stop-action: **NOT RUN**, human paused the rig first |
| #32 GRD-01 (field) | **PASS on crash criterion; new stall finding, root-caused** | `logcat -b crash -d` empty across the entire run. Real ~5m17s stall in S2 (18:07:32.098→18:13:49.156) traced to `AppRemote onFailure: SpotifyConnectionTerminatedException`, then a clean `IDLE` teardown (`jtp_ft11.log:` grep `SpotifyConnectionTerminatedException`) — not a GRD-01 race signature |
| #34 CTL-04 (field) | **NOT OBSERVED / INCONCLUSIVE** | Same instrumentation gap as FT10 (`grep -i settl` → zero hits, `settled_` never surfaced to the shell); best sustained near-zero window was ~9-20s, never confirmed as a durable post-verify landing |
| #36 CTL-05 (field) | **PASS — clean, direct evidence** | Zero `SELF_HEARING` rejections in S1's 4 seeks (acute FT10 cascade did not reproduce). S4 caught the mechanism live: 2 consecutive `SELF_HEARING` rejects that mutually agree (160ms/171ms apart, well inside the 500ms gate) then a clean 3rd-fix acceptance, `sync err` landing at −37ms — exactly the "resolve within ~2 fixes" criterion, not FT10's "3+ rejects then a drift peg" (`jtp_ft11.log:688-707`) |
| #37 IDC-02 (field) | **Both gate signatures confirmed; fast-switch untested** | `identCorrob: streak 1/2` at 18:05:09.555 (natural-end reduced gate) and `streak 1/3` at 18:05:39.252 (full gate, re-armed by a later track-lost). No single misrecognized fix ever actuated (Medley/Karaoke misrecognition correctly gated off). Fast-switch `candidate pending` path never exercised — session never reached an active tracking phase where it applies |

## Cleared to close: candidates, not final calls

Per the brief, closing decisions are the orchestrator's, but on this run's
evidence: **#36 CTL-05** has clean, direct, reproducible field evidence
(the S4 two-fix-corroboration sequence is about as clean as field evidence
gets). **#37 IDC-02**'s two shipped decisions both have direct field
confirmation. **#29 CTL-02**'s stated mechanism passes, though the
still-unresolved chronic zEnd bias (§5 of `docs/ctl05-investigation.md`,
NOT part of CTL-05's scope) means the "floor landing" doesn't hold — a
product-quality caveat, not a fix failure. **#32 GRD-01**'s crash criterion
passes clean; the new Spotify-disconnect stall is a genuinely new issue
candidate, not evidence against GRD-01 itself. **#34 CTL-04**, **#28
CTL-01**, **#30 CTL-03** remain unresolved for lack of either
instrumentation (#34) or a clean run window (#28/#30) — not failures, just
still-open questions.

## Anomalies (honest list)

- **A' — drift=800ppm clamp reproduces via a path CTL-05 doesn't target**
  (S1, 18:03:10–18:04:04+, ~43+s held): chronic-bias accepted fixes alone
  (no SELF_HEARING chain) can still peg the drift clamp. New anomaly,
  follow-up ticket recommended, separate from CTL-05's acute mechanism.
- **B — Spotify App Remote silent disconnect** (S2, stall 18:07:32.098→
  18:13:49.156, ~5m17s): the recognition loop went completely silent with
  no crash/ANR/log activity of any kind, ultimately explained by a delayed
  `SpotifyConnectionTerminatedException` failure callback. The app's own
  teardown on detecting this was clean (IDLE, service released, no
  orphaned mic) — but there is apparently no independent liveness timeout
  on JTP's side that would surface a dead Spotify connection faster than
  Spotify's own (here, 5+ minute) failure callback. New issue candidate.
- **C — `uri=none(enable 3rd-party integ.)`** appeared repeatedly during
  S2's Billie Jean churn on real ACRCloud matches ("Billie Jean",
  "Billie Jean (Extended", etc.) that never resolved a Spotify URI at all —
  distinct from a low-confidence rejection. Not investigated further
  (outside this run's ticket scope); flagged as an operational observation.
- **D — CSV harmonic-lock on Billie Jean's periodicity**: `lag_ms` briefly
  locked to 1028/2056 ms (exact multiples of the reported `beat_period_ms`
  ≈513ms) during S2, while Phone A's video was confirmed still playing
  normally by screenshot. Read as an analyzer artifact against
  strongly-periodic hostile material, per the protocol's own documented
  caution about harmonic lock — not treated as a real desync measurement.
- **E — missing DLLs in `build/core/`** (rig section): the build-env doc's
  claim that `libc++.dll`/`libunwind.dll` "sit permanently" next to
  `lag_analyzer.exe` did not hold for this session's tree; had to be
  re-copied before the pipeline would run. Doc/reality drift, worth a
  one-line fix to the standing doc.
- **F — human paused the test mid-S4** (~18:18): ended the run's active
  portion. Documented, not worked around.

## Orchestrator verification addendum (2026-08-20)

Every load-bearing quote above was re-grepped firsthand against
`scratchpad/ft11/jtp_ft11.log` and `docs/live_lag_ft11.csv`, and the Phone B
crash buffer was re-checked directly (`logcat -b crash -d`: zero
`com.jointheparty.app` entries). All timestamps, e-values, streak lines, the
SELF_HEARING pair, the stall boundary (18:07:32.098 → 18:13:49.156), and the
absence claims (0 FATAL, 0 auto-advance lines at all, 0 stable −300 band,
0 `candidate pending`) verify exactly. Corrections and clarifications:

- **S4 corroboration arithmetic, recomputed**: reject #1 offset=129500 @
  18:17:14.408 and reject #2 offset=134340 @ 18:17:19.266 → elapsed 4.858 s
  predicts 134358, i.e. the two rejects agree to **18 ms** (agent said
  160 ms — different normalization, same conclusion, far inside the 500 ms
  gate). Third fix 139520 @ 18:17:24.596 agrees with #2's projection to
  **150 ms**. The mechanism reading stands: textbook two-fix post-seek
  corroboration, then acceptance and a −37 ms landing.
- **Medley misrecognition gating**: it was gated by the **ident-streak
  reset** (`identCorrob: streak 1/3` restarting on the Medley's URI at
  18:06:32.344), not by a `fix rejected: LOW_CONFIDENCE` as the report
  says (that reject is a different line at 18:07:25.547). Conclusion
  unchanged — the misrecognition never actuated — but the credit belongs to
  IDC-02's streak mechanic.
- **S4 had two corrections**, not one: 18:17:05.646 (e=352, instantaneous
  deadband) preceded the reported 18:17:10.084 (e=468). Immaterial to any
  verdict.
- **CSV window range** is 40–**89** ms (report says 87). Immaterial.
- **Post-pause tail** (log lines ~860–1141, after the human paused): during
  the silent-room coast, `drift` pegged at **−800 ppm** with confidence
  decaying to 0.00 and `sync err` wandering to −162 ms, plus another silent
  log gap 18:19:28 → 18:22:42. Not scenario evidence (room was silent), but
  it corroborates the Anomaly A′ family: whenever fix flow starves, the
  drift estimator parks at a clamp rail instead of decaying toward zero.
  Folded into the A′ follow-up issue.
- The raw log has grown to 1107 lines (the agent's 866 was its count at
  report time; logcat was left running per teardown notes).

**Verdict deltas from the agent's report: none.** Issue actions taken on
this evidence: closed #29, #32, #36, #37; new issues filed for Anomaly A′
(chronic zEnd bias / drift-clamp via accepted fixes), Anomaly B (App Remote
liveness timeout), and Anomaly C (`uri=none` ACR matches); #31 keeps its
partial label with the screen-off leg recorded.
