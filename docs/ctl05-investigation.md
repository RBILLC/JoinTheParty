# CTL-05 investigation — FT10 Anomaly A (GitHub issue #36)

**Scope.** Root-cause of Anomaly A: during FT10 Scenario 1 (Dreams, clean
2m49s LOCKED), the live `sync err` flipped to a stable −314..−316 ms band
one second after a −287 ms corrective seek, held ~15 s, then drifted
210→228 ms, while the mic (`docs/live_lag_ft10.csv`) and the referee both
independently read ~40–58 ms throughout. This document is read-only
analysis; no source was modified. All line refs are against git HEAD
`a43efdb`.

**Verdict (high confidence, fully source-traced): a self-match-guard
mis-anchoring cascade, not a seek-execution or units/sign bug.** A layered
second finding (medium confidence, evidenced but not source-traced to a
line) is a standing ~350–520 ms bias in ACRCloud's returned `play_offset_ms`
relative to acoustic ground truth, independent of the cascade and present
even in clean, no-seek fixes (Scenario 2). H1 (seek-execution accounting)
and H4 (display/sign artifact) are refuted by direct evidence below. H3
(player-state "staleness") is not a bug — it is documented, intended
Spotify App Remote behavior — but its *interaction* with the self-match
guard's room-continuity bookkeeping is the actual mechanism (a refinement
of H1/H3, not H2).

---

## 1. The two populations in the recognizer's own data

Reconstructing capture time as `(log print time) − capAge` (fixdbg already
logs `capAge`; the log's wall-clock print time is *not* the fix's capture
time — this distinction matters and is easy to get wrong by ~700 ms, which
is what makes this analysis nontrivial) shows the fixes around the second
CTL-02 correction fall into two mutually-consistent, ~500–560 ms-apart
"timelines," both individually tracking real time at ~1:1:

| Fix | printed | capAge | **captured** | offset | zEnd |
|---|---|---|---|---|---|
| A (pre-seek) | 10:48:44.178 | 710ms | **10:48:43.468** | 151980 | 499 |
| B (post-seek) | 10:48:48.372 | 685ms | **10:48:47.687** | 156300 | −147 |
| C | 10:48:58.303 | 616ms | **10:48:57.687** | 165740 | 413 |
| D | 10:49:03.426 | 739ms | **10:49:02.687** | 170780 | 373 |
| E | 10:49:08.237 | 550ms | **10:49:07.687** | 175780 | 373 |
| F (accepted) | 10:49:13.455 | 768ms | **10:49:12.687** | 180800 | 353 |

(`docs/../scratchpad/ft10/jtp_ft10.log:157-158,169-170,182-183,190-191,199-200,208-209`)

- A→B: elapsed 4.219 s, offset delta 4320 ms → predicted 156199 vs actual
  156300, **101 ms off** — well inside `kRoomContinuityGateMs` (500 ms,
  `core/src/synccore.cpp:72`). B tracks A's timeline.
- C→D: elapsed 5.0 s, offset delta 5040 ms → **40 ms off**. D→E is equally
  tight. C/D/E form their *own* mutually-consistent timeline.
- B→C: elapsed 10.0 s, offset delta 9440 ms → predicted 166300 vs actual
  165740, **560 ms off** — just *outside* the 500 ms gate.

So timeline {A, B} and timeline {C, D, E, F, …} are each internally
coherent (real-time-paced) but sit ~500–560 ms apart from each other. This
is the same shape as an ACRCloud fingerprint-database near-duplicate
match (e.g. two catalog entries for the same release with slightly
different lead-in padding) — the recognizer isn't drifting or glitching,
it is alternating between two self-consistent readings of "where in the
track this is," net of the seek. This is the mechanism behind the
"zEnd pinned 350–520 ms" observation in the issue: `zEnd` is dominated by
*which* of the two populations the current fix belongs to, not by
player-state staleness (ruled out directly: `zEnd` sits at 417/521/401 in
the *pre-seek* fixes at `ps` ages of 3.8 s / 13.7 s / 23.7 s respectively —
constant despite staleness varying by 6×, `jtp_ft10.log:121,133,146`).

## 2. Why fix B (the outlier) got in, and why C/D/E got rejected

CORE-06's self-match guard (`core/src/synccore.cpp:738-785`, architecture
spec §7.3) runs two independent tests before a fix ever reaches the
estimator:

```
tracks_room = anchor_usable && |off − predicted_room| <= kRoomContinuityGateMs   // 500ms
self_hearing_reject = anchor_usable && room_anchor_confirmed && !tracks_room
                       && |off − estimator.local_audible_ms(t)| <= kSelfMatchWindowMs  // 400ms
```

Walking the six fixes above through this exact logic (constants at
`core/src/synccore.cpp:72-84`; player-state anchor is `ps=119298` through
fix A, reseeded to `ps=152925@mono≈44.458` by the seek's own player-state
callback):

- **Fix B** (offset 156300): `tracks_room` vs. the pre-seek anchor (A) is
  **true** (101 ms) → accepted as a genuine room fix, fed straight into
  `SyncEstimator::on_fix` (`core/src/estimator/estimator.cpp:59`), and
  re-anchors `room_anchor_offset_ms = 156300` (`synccore.cpp:817-821`).
  Its `z = local_audible − off = zEnd − output_latency_ms = −147 − 173 =
  −320`, which matches the observed `sync err=-316ms` at 10:48:48.716
  (`jtp_ft10.log:171`) to within a few ms — the estimator's own arithmetic
  is internally consistent; nothing is being mis-emitted or sign-flipped
  (**refutes H4**).
- **Fix C** (offset 165740, captured 57.687s): predicted-room (now anchored
  on B) = 156300 + 10000 = 166300; actual 165740 is 560 ms off →
  `tracks_room = false`. `local_audible_ms(57.687)` (dead-reckoned from the
  single post-seek player state 152925@44.458s) = 152925 + 13229 − 173 =
  165981; `|165740 − 165981| = 241 ≤ 400` → **SELF_HEARING**
  (`jtp_ft10.log:184`).
- **Fix D, E**: same shape (`jtp_ft10.log:192,201`), each also
  `!tracks_room` against B's anchor and within 400 ms of the growing
  dead-reckoned `local_audible_ms`.
- Because the self-match guard branch **returns early on rejection**
  (`synccore.cpp:782-784`, before the "maintain room timeline"
  `tracks_cand`/`cand_offset_ms` bookkeeping at `synccore.cpp:809-839`
  ever runs), C/D/E never get a chance to establish themselves as a new
  candidate timeline the way "Field Test 5"'s design intends — three
  consecutive fixes from the *same, mutually-consistent* timeline get
  discarded outright.
- Only at the **third** consecutive reject does
  `kMaxConsecutiveSelfRejects` (= 3, `synccore.cpp:84`) trip
  (`synccore.cpp:774-781`), dropping the (wrong) anchor
  (`room_anchor_confirmed = false`). Fix **F** (offset 180800) then finds
  `anchor_usable = false`, skips the guard entirely, and is accepted
  (`jtp_ft10.log:208-210`, no "fix rejected" line precedes it) —
  `sync err` jumps from −312 to −6 at 10:49:13.951 and `drift` pegs at
  `800ppm` = `EstimatorConfig::drift_clamp_ms_per_s` (0.8 ms/s,
  `core/src/estimator/estimator.h:35`), a hard clamp hit. The clamp stays
  pinned through the rest of the scenario (`jtp_ft10.log:210-292`), which
  is the mechanistic source of the "drifted up through 210→228 ms" tail —
  `predict_to` (`estimator.cpp:46-57`) applies `e_ += d_*dt` every tick
  with `d_` stuck at its ceiling, independent of what any individual fix
  says, until the next accepted fix partially corrects it. **The exact
  `frequency_skew` value that pinned the clamp is not logged** — flagged
  below as a required instrumentation addition; without it this one step
  could not be reproduced in an offline harness (see §5).

Net: from 10:48:48.716 to 10:49:13.951 (~25 s, overlapping the issue's
"held ~15 s"), the engine is not "wrong about physics" — it is trusting a
single fix from one recognizer-side timeline as ground truth, then
systematically discarding three consecutive, mutually-agreeing fixes from
the *other* timeline as self-hearing, because CORE-06's guard compares
them against a `local_audible_ms` that (correctly, by design — see §3) has
had no fresh player state to re-anchor against since the seek.

## 3. Player-state "staleness" is not a stall — it's documented behavior

The issue's second lead ("Spotify player-state age grew unbounded... the
stream appears to have stalled") is real in effect but not a defect:

```kotlin
// FIELD TEST 7: player states are EVENT-driven and this watcher
// starts only after the aim settles...
```
(`android/app/src/main/java/com/jointheparty/app/ui/session/SessionViewModel.kt:723-732`)

```kotlin
// Player states are event-driven (play/pause/seek), not
// periodic — logging each is cheap and shows exactly
// where corrections land.
```
(`android/app/src/main/java/com/jointheparty/app/spotify/AppRemoteSpotifyController.kt:213-215`, the `subscribeToPlayerState` callback at `:195-223`)

Spotify's App Remote SDK only pushes a `PlayerState` on play/pause/seek/
track-change — not on a timer. During an uninterrupted LOCKED window with
no further seeks, **zero** further player states are expected, by design.
`SyncEstimator::projected_local_ms` (`estimator.h:87`,
`estimator.cpp:38-44`) exists specifically to dead-reckon through exactly
this gap, and the mic/referee data confirms real Spotify playback stayed
steady throughout (CSV `t_s` 278–420, i.e. 10:48:45–10:50:47, spans almost
the entire −316 episode plus the climb: `lag_ms` 42–55 with occasional
harmonic/echo outliers, `confident=1` throughout —
`docs/live_lag_ft10.csv:278-292` onward) — so the dead-reckoning itself was
not corrupted. What was wrong is what it was being *compared against*
(§2), not the extrapolation mechanism itself. **This narrows H3**: FT10's
own field-test doc treats staleness as a suspect; source reading shows it
is expected, and the CSV shows the extrapolation held up fine — the real
fault is CORE-06's guard using that (correctly stale-tolerant) reference
to arbitrate between two disagreeing recognizer timelines and picking the
wrong one to keep.

## 4. H1 (seek-execution accounting) — refuted directly

`SyncEstimator::on_local_seek` (`estimator.cpp:137-153`) shifts `e_` by
`target_ms − projected_local_ms(landing_ns)` and widens `p00_` by
`seek_exec_var_ms2` (2500 ms², `estimator.h:29`) — it does **not** touch
`player_position_ms_`/`player_mono_ns_`, so `projected_local_ms` keeps
extrapolating the pre-seek trajectory until a fresh `on_player_state`
call lands. In this run that fresh state arrived almost immediately:

```
10:48:44.181  CORRECTION → seek 152906ms (jump -287ms) e=298 conf=0.86
10:48:44.182  seekTo 152906ms (player was 119298ms)
10:48:44.308  player: 152906ms paused=false
10:48:44.458  player: 152925ms paused=false
```
(`jtp_ft10.log:159-163`)

Commanded target 152906 vs. confirmed landing 152925 is **19 ms off** —
the seek executed almost exactly as modeled. The −314..−316 ms magnitude
has no arithmetic relationship to the seek's own 287 ms jump or its ~19 ms
execution error; it is fully explained by fix B's `z` value (§2). **H1 is
refuted as the cause of the flip's magnitude or timing** (the *timing*
coincidence — "one second after the seek" — is explained instead by fix B
simply being the next fix the recognizer happened to return, at whatever
cadence CTL-02's post-correction fix-interval override drives).

## 5. What could not be confirmed

- **The exact drift-clamp trigger.** Fix F's `frequency_skew` field isn't
  logged anywhere in `fixdbg` or `MATCH`, so the offline-harness
  reproduction encouraged in this investigation's brief could rebuild
  every fix's `z` (matches log to within Kalman-gain rounding, as shown
  above) but could **not** reproduce the `drift=800ppm` clamp hit without
  fabricating a skew value — which the anti-fabrication rule for this
  investigation forbids. This is called out explicitly rather than
  guessed at; §6 recommends logging it.
- **Why the two timelines exist at ~500–560 ms apart in ACRCloud's own
  data**, and why *both* still sit well above the mic/referee's ~45 ms
  floor even after subtracting the calibrated 173 ms output latency
  (timeline {A,B}: `zEnd≈-147..499` is wildly split; timeline
  {C,D,E,F,…}: `zEnd≈350-520` consistently implies `z≈180-350`, still 3-7×
  the mic's floor). This chronic bias persists into Scenario 2's clean,
  no-seek, no-guard-drama fixes (`zEnd` 504/544/514 at 10:53:07–12, per
  `docs/field-test-10-results.md` §Anomaly A) — it is evidence of a
  standing bias somewhere between ACRCloud's `play_offset_ms` and the
  actual playing position, upstream of both the estimator and the shell
  (their arithmetic agrees with each other; §2's `zEnd − 173` match
  confirms that). Ranked candidates, **none confirmable from available
  evidence**: (a) an ACRCloud catalog/fingerprint-database duplicate entry
  for this specific master with different lead-in padding (consistent with
  §1's two-population finding — the *chronic* band could just be "whichever
  of N near-duplicate catalog entries ACRCloud's matcher picks this pass");
  (b) an ACRCloud-side processing/analysis latency not captured by pairing
  `play_offset_ms` with `endMonoNs` as the code comment assumes
  (`android/app/src/main/java/com/jointheparty/app/recognition/ACRCloudProvider.kt:151-155`
  documents the pairing as exact per ACRCloud's schema, but this
  investigation cannot independently verify ACRCloud's server-side
  semantics). Ranked **unconfirmed** — flagged, not resolved, exactly as
  the field-test doc's own honesty standard requires.

## 6. Proposed fix (design only — not implemented)

1. **Let a self-hearing-rejected fix compete for the candidate timeline.**
   Currently `synccore.cpp:782-784` returns before the `tracks_cand`
   bookkeeping at `:809-839` ever runs for a rejected fix, so three fixes
   from a real, mutually-consistent second timeline can never promote
   themselves out of "self-hearing" purgatory except by brute-forcing
   `kMaxConsecutiveSelfRejects`. Proposed: run the `tracks_cand` check
   (against `cand_offset_ms`/`cand_ns`, i.e. among *rejected* fixes too)
   before the early return, and short-circuit the self-hearing verdict if
   two consecutive rejected fixes agree with each other within
   `kRoomContinuityGateMs` — mirroring the "two fixes now agree on a
   different continuous timeline" logic already trusted for *accepted*
   fixes at `:822-828`, just applied one guard-check earlier. This directly
   targets the mechanism in §2: C and D agreed with each other (40 ms)
   far tighter than either agreed with B's anchor.
2. **Do not let a single fresh fix fully re-anchor `room_anchor_*`
   post-seek.** Fix B's `tracks_room` check succeeded by 101 ms almost by
   chance (against a 500 ms gate) immediately after a correction whose
   entire purpose was to change the local/room relationship. Consider
   requiring the room-continuity anchor to be corroborated by **two**
   agreeing fixes before it's trusted to arbitrate self-match rejections
   in the `settle_ns`-adjacent window after any seek (the settle window
   already suppresses fixes for 3 s; extending an "anchor needs
   re-corroboration" flag through one additional post-settle fix would
   cost nothing else the policy doesn't already track).
3. **Log `frequency_skew` in `fixdbg`.** Needed both to close the gap in
   §5 and because it is architecturally significant: it is the only input
   that can move `d_` to its hard clamp (`estimator.cpp:108-122`), and a
   clamped, wrong-signed drift is what turned one bad fix into 40+ seconds
   of monotonic climb (§2, fix F onward).
4. **Chronic bias (§5, unconfirmed):** before any estimator/policy change,
   get a direct read on ACRCloud's per-fix bias against the mic's
   acoustic ground truth on a *held-still, no-seek* control run (no
   corrections, log every `zEnd` against the CSV's simultaneous `lag_ms`)
   to determine whether it's constant-per-track (catalog edition) or
   session-varying (processing latency) before attempting a fix — the
   two candidates in §5 call for different remedies (edition
   disambiguation vs. a capAge correction term) and guessing wrong here
   risks masking a real acoustic problem behind a numeric offset.

## 7. Instrumentation for the next field run

- `identCorrob`/`fixdbg` line: add `skew=<frequency_skew>` (see §6.3).
- `fixdbg` line: add `trackR=<tracks_room> trackC=<tracks_cand>
  anchor=<room_anchor_offset_ms>@<age>` so a future analyst does not have
  to hand-reconstruct capture times and re-derive the room-continuity
  arithmetic from `capAge` subtraction the way this investigation had to
  (§1's table) — that reconstruction step is exactly what made this
  anomaly hard to read the first time.
- `fix rejected: SELF_HEARING` line: add the two comparison values it
  computed (`off`, `predicted_room`, `local_audible_ms`) rather than just
  the verdict — every number in §2's walkthrough had to be independently
  recomputed from timestamps because the guard's own inputs aren't
  logged.
- **`settled_` visibility (tech-req §2.15 / CTL-04).** `settled_` is a
  private `CorrectionPolicy` member (`core/src/policy/policy.h:417`) with
  no existing event path to the shell — unlike `converged`, which rides
  along in `sc_evt_sync_estimate_t` (`core/include/synccore/synccore.h:280`)
  because it's a field of the *estimator's* `Estimate` struct that the
  worker already copies into that event on every emission
  (`kEstimateEmitPeriodNs`, `core/src/synccore.cpp:45`). `settled_` belongs
  to the *policy*, which today only communicates outward through `Action`
  (fire/no-fire) — there is no existing per-tick policy-state event at
  all. Two design options, **neither implemented here**:
  - **(a) Piggyback on `sc_evt_sync_estimate_t`.** Add a `bool settled`
    field appended at the end of the struct (the enum's own comment at
    `synccore.h:270-272` already documents "append at the end" as this
    codebase's ABI-stability convention for exactly this situation). This
    is the lower-effort option: the worker already calls
    `policy.on_estimate(...)` right where it builds this event, so passing
    the policy's `settled_` (would need a `bool settled() const` public
    accessor added to `CorrectionPolicy`, mirroring none of the existing
    public API but structurally trivial) through in the same emission is a
    same-call change, not a new event path. **C ABI implication**: this is
    an append-only struct change, source- and binary-compatible with any
    Kotlin/JNI struct-mapping code reading it by field name (fine); it is
    **not** binary-compatible with any code doing a raw `sizeof`/`memcpy`
    of the old, shorter struct layout — worth an explicit search of the
    JNI bridge (`android/app/src/main/java/com/jointheparty/app/core/SyncCore.kt`)
    for such a pattern before landing.
  - **(b) A new `SC_EVT_SETTLED_CHANGED` event, fired only on transition.**
    Cheaper on the log (one line per entry/exit rather than one per
    estimate at up to 15 Hz), but requires the worker to track "was
    settled last tick" itself to detect the edge, and adds a whole new
    enum value + payload struct (the codebase's own precedent for new
    events, e.g. `SC_EVT_ACTIVE_DUCK` at `synccore.h:273`, appended at the
    end of the enum) rather than reusing plumbing that already exists.
  Recommendation for the next field run specifically: **(a)** — it needs
  the least new surface area, and Anomaly A's own finding (§2/§4) is that
  `settled_` structurally can't be reached while `e` sits at −315 ms
  regardless of the room's real state, so what's needed most urgently is
  confirmation of *that*, which a per-estimate boolean answers directly
  without waiting for a transition to fire.

## 8. Claims ledger (every claim traces to a quoted line above)

| Claim | Evidence | Confidence |
|---|---|---|
| Seek executed within 19 ms of commanded target | `jtp_ft10.log:159-163` | Confirmed |
| H1 (seek-accounting) does not explain the −316 flip | same, + §4 arithmetic | Confirmed |
| H4 (sign/display artifact) refuted — shell/core arithmetic agree | `zEnd−173` matches `sync err` to a few ms, §2 | Confirmed |
| H3 staleness is designed SDK behavior, not a stall | `SessionViewModel.kt:723-732`, `AppRemoteSpotifyController.kt:213-215` | Confirmed |
| Two ~500–560ms-apart, internally-consistent recognizer timelines exist around the seek | §1 table, `jtp_ft10.log:157-209` | Confirmed |
| Fix B accepted via `tracks_room`, re-anchors, produces the −316 read | `synccore.cpp:738-821`, `estimator.cpp:59-135`, `jtp_ft10.log:169-171` | Confirmed |
| Fixes C/D/E rejected SELF_HEARING because they don't track B's anchor but land near stale `local_audible_ms` | `synccore.cpp:750-784`, §2 arithmetic, `jtp_ft10.log:182-201` | Confirmed |
| 3rd reject drops the anchor; fix F then accepted unchallenged, pegs drift at clamp | `synccore.cpp:774-781`, `estimator.h:35`, `jtp_ft10.log:208-210` | Confirmed |
| Exact skew value that pinned the clamp | not logged anywhere in this run | **Unconfirmed — instrumentation gap** |
| Chronic ~350-520ms zEnd bias vs. mic/referee floor, independent of seeks | `docs/field-test-10-results.md` Scenario 2 zEnd 504/544/514; `jtp_ft10.log:121,133,146` (zEnd stable across ps-age) | Evidenced, **root cause unconfirmed** (ranked candidates in §5) |

**Root cause of the −316 ms flip/hold (the acute half of Anomaly A):
CONFIRMED** — a CORE-06 self-match-guard mis-anchoring cascade triggered
by two recognizer-side timelines disagreeing across a seek boundary, not a
seek-execution, staleness, or sign/display bug.

**Root cause of the chronic ~350-520ms zEnd band (the standing half):
UNCONFIRMED**, evidenced to be upstream of the estimator/policy/shell
(their mutual arithmetic is internally consistent) and most likely in
ACRCloud's own matching, ranked candidates and a proposed control-run
methodology in §5/§6.4.
