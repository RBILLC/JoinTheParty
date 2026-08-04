# DSP-03b review — Kotlin volume-duck actuator & echo · 2026-08-03

**Status: implemented and verified (this session).** Spec
`technical-requirements.md` §2.12, ticket DSP-03b in backlog Epic 9. Lands
the shell-side half of the volume-duck mechanism DSP-03a's core ABI already
supports (`1ec8b56`): the JNI event/echo plumbing, `SessionViewModel`'s
bounded duck episode, and a new `StreamVolumeController` seam onto
`AudioManager`. No existing test's body/assertions changed; every existing
call site (`SessionGraph`, both `SyncEngine` fakes) still compiles unchanged
because the new dependency is nullable and defaults to null, mirroring how
`spotify`/`chirp`/`tonePlayer` are already injected.

## Plumbing map

```
core (already landed, 1ec8b56)
  SC_EVT_ACTIVE_DUCK (ordinal 8, appended after SC_EVT_ACTIVE_PROBE = 7)
    │ payload: sc_evt_active_duck_t { duck_ms }
    ▼
synccore_jni.cpp: event_trampoline()
  case SC_EVT_ACTIVE_DUCK: i0 = payload->duck_ms;   // mirrors ACTIVE_PROBE's case exactly
    │ onNativeEvent(type=8, ..., i0=duck_ms, ...)
    ▼
SyncCore.kt: onNativeEvent's `when (type)`
  8 -> Event.ActiveDuck(i0)
    │ (SharedFlow, events)
    ▼
SessionViewModel.onEngineEvent
  is SyncCore.Event.ActiveDuck -> onActiveDuck(event)
    │ gates, target selection, bounded coroutine (see below)
    ▼
engine.notifyDuckExecuted(achievedDeciDb)
    │
SyncCore.notifyDuckExecuted -> nativeNotifyDuckExecuted -> sc_notify_duck_executed
    ▼
core: worker arms the deferred matched-filter dip detector (DSP-03a)
```

## D1 — `StreamVolumeController` and why

`android/app/src/main/java/com/jointheparty/app/audio/StreamVolumeController.kt`
(new file) holds both pieces:

```kotlin
interface StreamVolumeController {
    fun getStreamVolume(): Int
    fun setStreamVolume(index: Int)   // flags = 0 always, NEVER FLAG_SHOW_UI
    fun getStreamVolumeDb(index: Int): Float
}

class AudioManagerStreamVolumeController(context: Context) : StreamVolumeController
```

**Why a seam at all.** JVM unit tests cannot construct a real
`android.media.AudioManager` — the `android.jar` unit-test stubs throw
`UnsupportedOperationException` on every call — so `SessionViewModel` never
touches `AudioManager` directly. This is the exact same shape `SyncEngine`
already provides over the native `SyncCore` bridge (`SyncCore` implements
`SyncEngine`; JVM tests substitute a fake): `SyncCore` implements
`StreamVolumeController`... no — `AudioManagerStreamVolumeController`
implements it, `SessionViewModel` depends on the interface, and
`FakeStreamVolumeController` (test-only) is the substitute.

**Real implementation is deliberately logic-free.** All three methods are
one-line pass-throughs to `AudioManager`. All duck decision logic (gates,
target-index search, deci-dB math) lives in `SessionViewModel.onActiveDuck`,
which IS JVM-tested — keeping the untested class trivial is what makes that
split defensible.

**Device-type fallback for `getStreamVolumeDb`.** The real
`AudioManager.getStreamVolumeDb(stream, index, deviceType)` needs a device
type. Per §2.12's authorized fallback, `AudioManagerStreamVolumeController`
always passes `AudioDeviceInfo.TYPE_BUILTIN_SPEAKER` rather than reaching
into `AudioRouteObserver`'s route-classification plumbing: the duck's
target-index search only ever reads a DELTA off this curve (original dB
minus target dB), so a device-type mismatch shifts the absolute curve but
not its relative shape enough to matter at a ~6 dB nominal duck, and it
keeps this class independent of route observation (stays logic-free, one
job: talk to `AudioManager`).

**API-level gate.** `getStreamVolumeDb(int,int,int)` is API 28+ (Android P);
the app's `minSdk` is 24. `AudioManagerStreamVolumeController` is annotated
`@RequiresApi(P)`; `SessionGraph` only constructs one when
`Build.VERSION.SDK_INT >= Build.VERSION_CODES.P`, passing `null` below
that — which lands on `SessionViewModel`'s existing "no controller" duck
gate for free, no separate code path needed for pre-P devices.

## D2 — the duck episode (`SessionViewModel.onActiveDuck`)

Gates, mirroring `onActiveProbe` exactly, plus two duck-specific ones:

1. `spotify?.lastKnownPlayerState?.isPaused == false` (playback must be live).
2. Calibration must not be `Running`/`ByEarRunning`.
3. **New:** `volumeController` must be non-null.
4. **New:** `original == 0` (already muted) → no-op, no echo — per §2.12
   and DSP-03a's contract (docs/dsp03a-review.md), a shell that cannot
   execute the duck must simply stay silent; the core's 20 s expiry / 60 s
   cooldown handle a duck request that never echoes.

**Target selection** (§2.12, verbatim): the largest index whose dB is
`<= originalDb − 6`, found via
`(original downTo 0).firstOrNull { ... } ?: 0` — the `?: 0` fallback covers
volume ranges too shallow to reach a full 6 dB dip at any index (test
`activeDuckFallsBackToDeepestIndexWhenMinus6IsUnreachable`), landing on
index 0, the deepest duck the device can produce, rather than skipping the
duck entirely. `achievedDb = originalDb − targetDb`;
`achievedDeciDb = (achievedDb * 10).roundToInt()` — always the ACTUALLY
commanded depth, never a hardcoded 60, because volume-index quantization
means −6.0 dB exactly is rarely reachable.

**Bounded episode:**

```kotlin
scope.launch(dispatcher) {
    controller.setStreamVolume(targetIdx)
    try {
        delay(event.duckMs.toLong())
    } finally {
        withContext(NonCancellable) {
            controller.setStreamVolume(original)
        }
    }
    engine.notifyDuckExecuted(achievedDeciDb)
}
```

No loops, no timers — the `maybeSampleReferee` hang precedent (an earlier
`while (true) { delay(...) }` hung every JVM test against
`StandardTestDispatcher`) does not apply here; this coroutine runs a fixed
sequence once and completes.

**Cancellation safety — the one structural difference from
`onActiveProbe`.** If the session `scope` dies mid-`delay` (teardown), the
user's volume must not stay ducked. The restore is the ONLY statement in
the `finally`, wrapped in `withContext(NonCancellable)` — without that
wrapper, a suspending call inside the `finally` of an already-cancelled
coroutine would itself be cancelled immediately and never actually run
`setStreamVolume(original)`. This guarantees the restore fires exactly once
on every path:

- **Normal completion:** `delay` returns, `finally` restores, execution
  continues past the `try` block, the echo fires.
- **Cancellation:** `delay` throws `CancellationException`, `finally`
  restores (under `NonCancellable`), then the original
  `CancellationException` continues propagating — the statement after the
  `try` block (the echo) is never reached. This is deliberate, not an
  oversight: the episode never completed, so DSP-03a's `on_duck_result`
  must never see a verdict for it, and the core's own duck-request expiry
  already covers a duck that never echoes.

Proven by `activeDuckRestoresVolumeOnCancellationWithoutEchoing`: emits
`ActiveDuck(150)`, advances virtual time 50 ms in, cancels a dedicated
`vmScope`, and asserts the volume index is back at `original` with zero
`notifyDuckExecuted` calls.

## Quantization / achieved-dB semantics

The happy-path test's dB table (`0 → 0.0, 1 → -2.5, 2 → -5.0, 3 → -7.5,
4 → -10.0`, indices renumbered here for exposition — see
`duckDbTable()` in the test file) deliberately has no index at exactly
−6 dB: the deepest index at or below −6 dB is −7.5 dB, so the test asserts
`achievedDeciDb == 75`, never a hardcoded 60. This is the direct JVM
analogue of §2.12's own caveat text ("−6.0 dB exactly is rarely
reachable").

## D3 — JNI + engine plumbing

- `synccore_jni.cpp`: `case SC_EVT_ACTIVE_DUCK:` packs `duck_ms` into the
  same `i0` slot `SC_EVT_ACTIVE_PROBE` uses — byte-identical shape, just a
  different payload struct. `nativeNotifyDuckExecuted(handle,
  achieved_deci_db)` calls `sc_notify_duck_executed`, mirroring
  `nativeNotifyProbeExecuted` exactly (same null-handle guard, same
  `SC_ERR_INVALID_ARG` fallback).
- `SyncCore.kt`: `Event.ActiveDuck(val duckMs: Int)`, mapped at `when (type)`
  ordinal `8` — the value directly after `ActiveProbe`'s `7`, confirmed
  against `core/include/synccore/synccore.h`'s `sc_event_type_t`
  (`SC_EVT_ACTIVE_DUCK` appended at the end, after `SC_EVT_ACTIVE_PROBE`).
  `notifyDuckExecuted(achievedDeciDb: Int): Boolean` calls
  `nativeNotifyDuckExecuted(handle, achievedDeciDb) == 0`.
- `SyncEngine.kt`: `fun notifyDuckExecuted(achievedDeciDb: Int): Boolean`
  added to the interface.
- Both `SyncEngine` fakes updated: `SessionViewModelTest.FakeSyncEngine`
  gains a counting/logging override (`notifyDuckExecutedCount`,
  `duckAchievedDeciDbLog`, and a shared `duckCallLog` — same pattern as
  `probeCallLog`); `AppRemoteControllerTest.FakeSyncEngine` gains an inert
  `override fun notifyDuckExecuted(achievedDeciDb: Int): Boolean = true`,
  matching what CTL-01b did there for `notifyProbeExecuted`.

## Test inventory

**`SessionViewModelTest.kt`** (additive, under a new
`// ---- DSP-03b: Event.ActiveDuck ----` section, mirroring the
`ActiveProbe` block's style):

1. `activeDuckExecutesSetDelayRestoreThenEchoesActualAchievedDepth` — happy
   path: exact call order `setVolume(1), setVolume(4), notifyDuckExecuted`,
   `achievedDeciDb == 75` (not hardcoded 60), `currentTime == 150L`.
2. `activeDuckDoesNothingWhenAlreadyPaused` — zero volume calls, zero
   echoes.
3. `activeDuckDoesNothingDuringCalibration` — `Running` calibration → zero
   volume calls, zero echoes.
4. `activeDuckDoesNothingWhenMuted` — `getStreamVolume() == 0` → zero
   calls, zero echoes.
5. `activeDuckDoesNothingWhenNoVolumeController` — `volumeController = null`
   → zero echoes, no crash.
6. `activeDuckRestoresVolumeOnCancellationWithoutEchoing` — cancel a
   dedicated scope 50 ms into a 150 ms duck → volume restored to original,
   zero echoes.
7. `activeDuckFallsBackToDeepestIndexWhenMinus6IsUnreachable` — a shallow
   3-index dB table with nothing reaching −6 dB → ducks to index 0,
   echoes the actual achieved depth (40 deci-dB).

New helper classes (test-only, same file): `FakeStreamVolumeController`
(scripted index→dB table + shared call log) and `duckDbTable()`.

**No `SyncCoreBridgeTest.kt` (androidTest) addition.** This ticket's file
scope is JVM sources only (`synccore_jni.cpp`, `SyncCore.kt`, `SyncEngine.kt`,
`SessionViewModel.kt`, `SessionGraph.kt` wiring, one new audio file, JVM
test files, this doc) — `SyncCoreBridgeTest.kt` lives under
`src/androidTest`, outside that list, so no edit was made there even though
the backlog's acceptance criterion 5 ("JNI round-trip test... notifyDuckExecuted
calls through to nativeNotifyDuckExecuted/sc_notify_duck_executed") reads
most naturally as an instrumented test. What IS in scope and verifiable via
`testDebugUnitTest` is the Kotlin-layer half of that round trip:
`SessionViewModelTest`'s seven tests above all construct
`Event.ActiveDuck(...)` directly (the exact value `onNativeEvent`'s
`when (type)` produces for native ordinal 8) and assert
`SessionViewModel`'s handling of it end-to-end, including
`engine.notifyDuckExecuted(achievedDeciDb)` reaching the `SyncEngine`
interface — the same coverage strategy CTL-01b already established for
`Event.ActiveProbe` (which also has no androidTest coverage for its own
ordinal mapping, for the identical reason: `sc_config_t` exposes no hook to
organically arm a probe/duck request from Kotlin without minutes of
simulated starvation/turn-off dwell). If the orchestrator wants the
instrumented half added, it's a small, low-risk, test-only change to
`SyncCoreBridgeTest.kt`: a session with no duck outstanding calling
`core.notifyDuckExecuted(60)` and asserting it returns `true` (mirrors
`docs/dsp03a-review.md`'s `test_duck_executed_echo_contract` — a stray echo
is harmless, `SC_OK`, no event); confirmed to compile cleanly against the
edits in this ticket via a scratch build of
`:app:compileDebugAndroidTestKotlin` before this file scope decision was
made, then reverted to stay inside the ticket's file allowlist.

## Build & verify (first-hand)

```
cd android && ./gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

`:app:testDebugUnitTest`:

```
BUILD SUCCESSFUL in 18s
24 actionable tasks: 5 executed, 19 up-to-date
```

136 JVM tests total (129 pre-existing + 7 new `SessionViewModel` tests; the
`SessionViewModelTest` suite itself: `tests="75" skipped="0" failures="0"
errors="0"`), all green.

`:app:assembleDebug`:

```
BUILD SUCCESSFUL in 8s
43 actionable tasks: 11 executed, 32 up-to-date
```

Includes `configureCMakeDebug`/`buildCMakeDebug` for `arm64-v8a`,
`armeabi-v7a`, and `x86_64` — proves `synccore_jni.cpp`'s new
`SC_EVT_ACTIVE_DUCK` case and `nativeNotifyDuckExecuted` compile and link
against the DSP-03a core ABI landed at `1ec8b56`.

## Deviations / latitude taken

- **`StreamVolumeController` naming and split**: the ticket offered
  `StreamVolumeController` as an example name and left the real
  implementation's structure open ("thin implementation... logic-free").
  Both interface and real implementation landed in one new file,
  `audio/StreamVolumeController.kt`, matching this ticket's "ONE new file"
  hard rule.
- **Device-type fallback**: chose the always-`TYPE_BUILTIN_SPEAKER` path
  explicitly authorized by the ticket ("else... as documented fallback")
  over reaching into `AudioRouteObserver`'s classification, to keep the
  untested real implementation logic-free — see D1 above for the full
  reasoning.
- **API-level gate**: the ticket didn't call out `getStreamVolumeDb`'s
  API 28 floor against this app's `minSdk = 24` explicitly, but the
  method does not exist on the framework before P — `SessionGraph` gates
  construction on `Build.VERSION.SDK_INT`, landing pre-P devices on the
  existing "no controller" no-op gate for free.
- **androidTest scope**: NOT touched — see the "No `SyncCoreBridgeTest.kt`"
  paragraph in the Test inventory section above for the reasoning and what
  a follow-up instrumented test would look like if wanted.
- **`onActiveDuck` early-return shape**: unlike `onActiveProbe` (which
  returns after computing `controller`/`playbackLive`/`calibrating` but
  before any async work, then launches unconditionally), `onActiveDuck`
  also does its synchronous target-index/achieved-dB computation
  ("original == 0" check, dB table reads, `achievedDeciDb`) before
  `scope.launch` — this is a straight-line dependency (target selection
  needs `original`/`originalDb` from the controller before the coroutine
  can act), not a deviation from the gating structure itself.

## What's next

Per §2.12's own sequencing note (restated verbatim in the ticket): the
CTL-01 pause-probe device pass runs first with the pause probe as shipped.
The duck becomes the default probe tier on-device only after those triggers
are field-proven there — `PolicyConfig::duck_tier_first` stays `false` in
production config (DSP-03a's default) until that separate, future
promotion ticket. This ticket lands the mechanism only, on both the core
(DSP-03a) and shell (DSP-03b) sides; nothing here changes what ships to a
device by default.
