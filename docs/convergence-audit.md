# Convergence Algorithm Audit · 2026-07-26

For PM review. Every number below is from on-device field traces, not simulation. No changes made in this pass — recommendations at the end await your call.

---

## 1. The question you asked, answered first

**"Manual adjustment should be an offset the engine uses — don't kill the engine."** Agreed, and that is the implemented semantic as of `a132517`:

- The wheel trim enters the *measurement equation*: `error = (local_position − room_position) − offset`. The engine keeps estimating, keeps correcting drift, keeps following track changes — it just converges to **your** zero.
- A commit does three things atomically: seeks by your delta (instant audible response), rebases the engine setpoint by its currently-measured error (your alignment *is* zero — this is what ended the fighting), and persists the wheel value per route.
- The engine is never paused, bypassed, or reset by a manual adjustment.

**One real gap in this design (finding #2 below):** the *rebase* is session-local. We persist your wheel value, but the absorbed measurement bias evaporates on restart — so the first minute of the next session will pull off by the bias again until you touch the wheel once. Fix is trivial (persist the rebased setpoint, not the raw trim); flagged for your decision.

## 2. The pipeline, stage by stage

```
mic ──► capture ring ──► PCM tee ──► ACRCloud ──► fix(offset, t_end, conf)
                                                        │
player states ──► projection ──► Kalman [error, drift] ◄┘
                                       │
                              policy: deadband → seek target
                                       │
                     App Remote seek ──► echo (settle + latency learning)
```

| Stage | Mechanism | Field-measured reality |
|---|---|---|
| **Coarse aim** | `seek(match_offset + elapsed + learned_latency)`, verify landed via reported position, re-issue ≤4× (seeks during track-load are silently dropped — learned on device) | Landed at **+95 ms** initial error |
| **Measurement** | `z = projected_local(t_end) − acr_offset − setpoint`; ACR `play_offset_ms` paired with the sample-END timestamp | ±100–150 ms noise; **plus an unresolved ~600 ms constant bias** (finding #1) |
| **Filter** | 2-state Kalman `[error, drift]`; drift clamped ±800 ppm; seek-aware state shift at expected landing time + variance inflation | Clean-data convergence in 3 fixes; drift estimate once hit 1738 ppm before the clamp existed |
| **Policy** | 350 ms deadband (was 25 — under ACR noise that meant a seek every fix); drift-pre-emptive corrections; 2 s → track-lost; 3 s settle after each seek; fix cadence 8→30 s (locked) | Corrections land at the commanded ms within ~90 ms |
| **Latency learner** | Post-settle innovation → EMA on seek lead | Learned 297 ms vs true 300 in sim; **corrupted in the field when the shell silently damped seeks** (fixed: damping banned, deadband moved into engine) |
| **Execution** | Main-thread App Remote seek + echo | ~90 ms, highly consistent |

## 3. Defects found in the field, all fixed

Ten in three evenings, each traced on hardware: no pre-first-fix retry → stuck MATCHING · off-main-thread connect → eternal AIMING · missing coarse aim → −17 s → lost-loop · aim dropped during track load → aim-verify loop · shell damping corrupted latency learning → restart-from-0:00 loop · fast-switch fired on ACR's alternate-release URIs (same song, 3 different Spotify IDs) → false restarts · phantom 1738 ppm drift → correction every fix · track-lost restart never re-armed recognition → deaf forever · wheel routed only through the setpoint → inert when recognition degraded · wheel/engine fight → ear-rebase.

## 4. Open problems, ranked

1. **The ~600 ms measurement bias — the root of everything remaining.** Post-correction fixes consistently read ≈ −600 ms even when landings are verified exact, and hand-recomputation from logged player states disagrees with the engine by ~450–600 ms. Candidates: (a) App Remote's reported position lags its true audio clock; (b) ACR's `play_offset_ms` references a point earlier than our sample-end pairing; (c) the 10 s window's content-vs-timestamp skew. The `fixdbg` shell-side replica now logs both sides per fix; **one clean 2-minute run with no song changes settles which it is.** Until then the rebase absorbs it — correctly, but per session.
2. **Setpoint persistence gap** (§1) — persist `engineNudgeMs` per route, not just the wheel value. ~5 lines.
3. **No skew observations from ACR** — its documented `frequency_skew` hasn't appeared in live responses, so drift is identified only from error growth: slow, and vulnerable to bias steps. ShazamKit's `frequencySkew` (the planned migration) restores the fast path.
4. **Rebase quality depends on estimate freshness** — committing the wheel while confidence has decayed rebases on a stale error. Guard: skip rebase when confidence < ~0.2, or weight it by confidence.
5. **10 s sample window staleness** — a fix summarizes audio up to 10 s old; during convergence this smears fast dynamics. A 6 s window post-lock (or dual-length: long for discovery, short for tracking) would sharpen measurements at ACR's cost per pass.
6. **No outlier rejection** — a single wild ACR offset (wrong-song match on a quiet passage) passes straight into the filter; post-seek inflated variance makes the filter especially credulous. A gated innovation test (reject > 3σ unless repeated) is standard and cheap.
7. **Bluetooth output latency unmodeled** — chirp calibration is physically impossible on BT (mic can't hear your ear cups), so `output_latency` stays 0 and the wheel carries the whole constant. That's acceptable *because* of offset semantics, but a per-device BT latency table (or OS-reported `audioTrack.latency`) could pre-seed it.

## 5. Current constants (single view)

| Constant | Value | Where |
|---|---|---|
| Deadband | 350 ms (shell-configured; engine default 25) | `sc_config_t.deadband_ms` |
| Track-lost threshold | 2 s | policy |
| Settle window / ack timeout | 3 s / 5 s | policy |
| Fix cadence | 8 s (erroring) · 10 s (base) · 30 s (locked) | policy |
| Latency learn gain / clamp | 0.7 / ±500 ms | policy |
| Drift clamp | ±800 ppm | estimator |
| Seek execution variance | (50 ms)² per seek | estimator |
| Recognition window / min / retry / cap | 10 s / 3 s / 6 s / 20 passes | shell |
| Aim verify delay / tolerance / attempts | 900 ms / 3 s / 4 | shell |
| Wheel range / engine setpoint clamp | ±1500 ms / ±4000 ms | tokens / core |

## 6. Recommended order of work (your call)

1. The bias-isolation run (§4.1) — one clean trace, then fix the constant at its source; the wheel returns to being taste, not compensation.
2. Persist the rebased setpoint (§4.2) — sessions start already-aligned.
3. Innovation gating (§4.6) — cheap insurance against wild fixes.
4. Confidence-guarded rebase (§4.4).
5. ShazamKit migration (restores skew; also the scale-cost answer from the recognition audit).
6. Dual-length sampling windows (§4.5) — only if post-lock wander is still audible after 1–5.
