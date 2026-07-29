# CTL-01 review — referee sentinel & active probe · 2026-07-29

**Status: landed in commit `7d0cc28`** (spec `technical-requirements.md` §2.9,
tickets CTL-01a/01b in backlog Epic 8). Desktop and JVM suites fully green;
the one open item is the device pass CTL-01b's acceptance criteria
explicitly reserve for the field rig (an active probe is an audible action
no JVM test can grade).

## The problem this closes

FT8's headline defect: after any room discontinuity, recognition tracks our
own speaker, continuity looks perfect, and every telemetry layer
self-confirms — the FT4 guard structurally cannot see it because our own
audio *is* the continuous timeline. Only the listener caught it. CTL-01
gives the engine two instruments of its own plus a forcing move.

## How the C++ engine and the Kotlin shell interact

The whole decision loop lives in core (`CorrectionPolicy`); the shell is
purely an actuator. The sequence:

1. **Suspicion (core).** Two independent triggers:
   - *Referee sentinel* — `policy.on_referee_window(...)`, fed by the worker
     right after each `SC_EVT_LATENCY_RESIDUAL` dispatch. It rings the last
     8 referee windows and tracks the last time any 3 valid lags mutually
     agreed within 50 ms. While LOCKED, 45 s without agreement across ≥4
     windows = starvation. Deliberately **not** `peak_ratio < 4`: that is an
     extreme-value statistic a single source passes at ≈6 (standing warning
     3); the self-match signature is the *absence of reproducible
     agreement*, exactly what FT8 logged ("the referee's windows lose the
     second copy").
   - *Wittenmark turn-off* — `policy.on_tick(...)`, called every worker
     tick (time-driven, because a starving filter never reaches
     `on_estimate` through an accepted fix): a valid estimate below the FT4
     confidence floor for 20 s with no accepted fix = passive learning has
     turned off (research-closed-loop-control.md §5 item 4).
2. **Probe request (core → shell).** Both triggers funnel through one gate
   (120 s cooldown; never while settling, paused, or already outstanding)
   into `SC_EVT_ACTIVE_PROBE { pause_ms = 200 }`, dispatched from the
   worker's `tick()`.
3. **Execution (shell).** The JNI trampoline (`synccore_jni.cpp`) maps the
   event to `SyncCore.Event.ActiveProbe(pauseMs)`; `SessionViewModel.
   onActiveProbe` executes `pause() → delay(pauseMs) → resume() →
   engine.notifyProbeExecuted()` on the session scope — but only if
   playback is live and no calibration is running. Otherwise it does
   nothing and never echoes: an unexecutable probe is *inconclusive by
   design*, and the core's request simply expires (cooldown still applies,
   so a declined probe cannot retry in a tight loop).
4. **Verdict (core).** The echo (`sc_notify_probe_executed`, mirroring
   `sc_notify_seek_issued`) stamps the probe epoch — at the echo, not at
   emission, because App Remote command latency is 100–500 ms — and
   snapshots the pre-probe filtered error. The pause froze *our* content
   position for 200 ms while the room kept advancing, so:
   - genuinely room-tracking fixes read the error shifted ≈ −200 ms →
     **genuine**; probe state clears, and the deliberately-introduced
     200 ms offset is cleaned by the existing machinery (it exceeds §2.7's
     125 ms persistence floor, so the gate corrects it within ~3
     corroborated fixes — a designed composition);
   - fixes that *didn't move* (they report our own audible position back to
     us) → **self-match confirmed** → `kTrackLost` through the existing
     lost flow, which forces the pause → fresh re-listen FT8 proved prints
     the truth (`sync err=-5396ms` the moment a re-listen ran).
   Seeks are suppressed while a probe is outstanding so a correction can't
   contaminate the shift measurement; the §2.7 ring and §2.8 hold keep
   accumulating underneath.

## Files touched

Core: `synccore.h` (event + payload + echo ABI, appended at enum end),
`policy.h/.cpp` (8 config fields, sentinel ring, dwell, request/verdict
state machine, seek suppression, epoch-clean `reset()`), `synccore.cpp`
(worker wiring, `kProbeExecuted` command, worker-local paused mirror),
`test_policy.cpp` (13 new tests), `test_synccore.cpp` (echo contract),
`tests/abi_c_check.c` (new — the ABI check is now a checked-in C99 file
with an exhaustive event-enum switch instead of a CMake-generated
one-liner; `CMakeLists.txt` points at it).
Android: `synccore_jni.cpp`, `SyncCore.kt` (`Event.ActiveProbe`,
`notifyProbeExecuted`), `SyncEngine.kt`, `SessionViewModel.kt`
(`onActiveProbe`), test fakes + 3 new JVM tests.

## Verification (first-hand)

- Core: 8/8 ctest suites; all five closed-loop sims unchanged (sawtooth /
  vienna / stability / phantom-fix / genuine-jump).
- Android: 129/129 JVM tests on `--rerun-tasks` (~13 s — no virtual-time
  hang; the probe delay runs on the test dispatcher) plus `assembleDebug`
  proving the JNI compiles against the new header.
- Existing tests byte-identical (zero removed lines in any test file);
  the sentinel/probe machinery is provably inert unless referee windows or
  the turn-off dwell feed it.

## Pending: the device pass

Per CTL-01b's AC and the field protocol: one real probe on the rig
(audible ~200 ms gap, echo in the trace), and a forced self-match — room
scrubber-seek while LOCKED — ending in track-lost → re-listen within
~30 s. Marginality note to watch live: 200 ms probe vs ±100–150 ms fix
noise gives a workable-but-tight verdict boundary; if verdicts flap,
`probe_pause_ms` is the knob to widen (300–400 ms), per §2.9.

## Mission state after this commit

CTL-02 ✅ (`5f03d08`) · CTL-03 ✅ (`9237e3a`) · CTL-01 🟡 code-complete
(`7d0cc28`, device pass pending) · MHT deferred — needs the beat-period
seeding decision (`second_lag_ms` from CTL-03a is a candidate source).
