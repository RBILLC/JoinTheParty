# CLN-01 implementation review — superseded pass-throughs and stale docs

**Ticket:** GitHub issue #55 (CLN-01), from the 2026-10-06 shallow-module
audit. **Archive tag:** `archive/pre-cleanup` (39d90c6) marks `main` before
the audit work. **Status:** all seven items implemented; core and Android
green. One item is not verified: the pill merge (item 6) has not been
looked at on a device or in previews.

The removal rule that scoped the ticket held throughout: nothing parked or
dark was touched (duck tier, hypothesis bank, AEC / `sc_push_reference`,
`HttpBackendClient`, PKCE, ShazamKit pieces, `NudgeStore` command latency,
`SpotifyController.disconnect`, `RecognitionProvider.close`,
`SessionGraph.close`, `OboeCapture` accessors, core route fields). Both
`FixSource` values are untouched.

---

## What changed

### 1. `sc_notify_local_playback` chain removed

- **Core:** the declaration in `synccore.h`, the definition in
  `synccore.cpp`, `Command::Kind::kLocalPlayback` and its handler, and
  `wk.last_commanded_position_ms` with both of its writes (the
  local-playback handler and the seek-issued handler). The CORE-06 guard
  comment that named the field now says "the last commanded position".
- **JNI:** `nativeNotifyLocalPlayback` in `synccore_jni.cpp`.
- **Kotlin:** `notifyLocalPlayback` in `SyncEngine` and `SyncCore` (plus the
  `external` declaration), and the call in
  `AppRemoteSpotifyController.play`. The `seekTo` comment that credited
  "notifyLocalPlayback-adjacent bookkeeping" for command-latency learning
  now just says the seek echo does it.
- **Spec:** the declaration and its "arms the self-hearing guard" comment
  at `technical-requirements.md:73-74`. No other prose in the spec claimed
  the call arms the guard.
- `core/tests/abi_c_check.c` had no reference.

Historical docs that mention the call (`docs/aec-implementation-review.md`,
`docs/android-spotify-review.md`, `docs/review-2026-07-21.md`,
`docs/field-test-4-findings.md`, `docs/sync-test-results.md`,
`backlog-tickets.md`) are records of their time and were left alone.

### 2. `onTrimChange` removed

Parameter and every call removed from `NudgeWheel` (double-tap, drag,
inertia), `rollInertia`, both `SessionScreen` layers (`SessionScreen`,
`ActiveContent`), `MainActivity`, and the eight `@Preview` call sites. The
`NudgeWheel` KDoc sentence now describes only `onTrimCommit`. The wheel's
optimistic display and debounced commit never depended on the callback.

### 3. `shouldOpenGuidedCalibrationPaneAfterRecalibrateRequest` removed

The call site is `showCalibration = onRequestRecalibrate()` with a one-line
comment naming the CFX-02 rule. Two doc comments in `SessionScreen.kt`
that pointed at the removed function were reworded.

### 4. `SessionViewModel.backToDeviceShelf()` removed

`CalibrationSheet.onBackToShelf` is wired to `SessionScreen`'s existing
`onOpenDeviceShelf` parameter (the raw view-model callback, which is
exactly what `backToDeviceShelf` delegated to). The `onBackToDeviceShelf`
parameter and the `MainActivity` line are gone. `dismissDriftBanner`'s KDoc
now links `openDeviceShelf`.

### 5. Two helpers inlined in `SessionScreen.kt`

- `openDeviceShelfAction` → a local lambda, `handleOpenDeviceShelf`.
- `CalibrationState.isInProgress()` → `state.calibration !=
  CalibrationState.Idle` at its one call site, carrying the 2026-07-28
  field-fix comment. The comment now says "any non-Idle state" rather than
  "in-flight", which is what the code always did.
- `shouldShowCalibrationSheet` kept.

### 6. Pills merged into `SheetPill`

`PrimaryPill`, `SecondaryPill` and both `JoinButton`s are gone; their four
call sites use `SheetPill`. `SheetPill` gained one parameter,
`horizontalPadding: Dp = 28.dp`; the four migrated call sites pass
`DT.Space.gutter` (24dp), so their padding is unchanged and the 13 existing
callers are unchanged.

Visual differences, by reading the code (**not yet looked at on a device or
in previews**):

| | before (migrated sites) | after | expected effect |
|---|---|---|---|
| Horizontal padding | `DT.Space.gutter` (24dp) | 24dp via parameter | none |
| Corner | `RoundedCornerShape(percent = 50)` | `RoundedCornerShape(44.dp)` | none while the pill is under 88dp tall (a single-line label is about half that); a label that wrapped to three or more lines would show flat sides |
| Structure | `Box` + centred `Text` | `Text` with the modifier chain | none for a wrap-content pill |
| Disabled alpha | n/a | `alpha(1f)` when enabled | none |

The onboarding `JoinButton` took a `modifier` parameter that no caller
passed; it went with the function.

### 7. Stale docs

- `synccore.h`: nudge clamp comment corrected to ±4000 ms (`kNudgeClampMs`
  in `synccore.cpp`); the `sc_copy_recent_capture` comment moved from above
  `sc_reset_capture_history` to above its own declaration.
- `SyncEngine.kt`: the "deliberately excludes" list now names only
  `pushCapture` and `pushReference`.
- `SessionViewModel.kt`: the three orphaned KDoc blocks were re-attached to
  the functions they describe (`trimPromotionMedian`, `shouldKeepSampling`,
  `runRecognitionPass`) rather than deleted. The `runRecognitionPass` KDoc
  now says resolution goes through `resolveTrack` → `resolvedWithAim`, not
  `onTrackResolved`, and mentions the direct-URI path and the confirmed
  fast-switch case.
- `SessionScreen.kt`: the "once-per-session gate is NOT yet enforced" TODO
  replaced by a note that `SessionViewModel.gateDismissedThisSession`
  enforces it.
- `AppRemoteSpotifyController.kt`: the `onFailure` comment no longer claims
  a `SpotifyAppDetector` check; `SessionViewModel` maps `AuthFailed`
  straight to `onPremiumRequired()`.

---

## Test changes — the four authorized, nothing else

`git diff --stat` over `core/tests`, `android/app/src/test`,
`android/app/src/androidTest`:

```
 .../app/spotify/AppRemoteControllerTest.kt         |  7 ------
 .../app/ui/session/SessionScreenTest.kt            | 27 ++++------------------
 .../app/ui/session/SessionViewModelTest.kt         |  9 +-------
 core/tests/test_synccore.cpp                       |  1 -
```

1. `SessionViewModelTest`: deleted
   `shouldOpenGuidedCalibrationPaneReflectsWhetherRequestRecalibrateStartedSomething`;
   `SessionScreenTest` header comment no longer mentions the function.
2. `SessionScreenTest`: deleted
   `openDeviceShelfActionSetsShowDeviceReviewAndInvokesTheCallback` and the
   header-comment mention.
3. `backToDeviceShelfReturnsFromDetailToTheShelf` calls
   `vm.openDeviceShelf()`.
4. `notifyLocalPlayback`: the assertion in `playReturnsFalseWhenDisconnected`,
   the list and override in `AppRemoteControllerTest`'s `FakeSyncEngine`,
   the override in `SessionViewModelTest`'s fake engine, and the
   `sc_notify_local_playback` check in `test_synccore.cpp`.

Two judgment calls inside those edits:

- In `SessionScreenTest` the `// ---- CFX-05: IDLE device-shelf entry point
  wiring` section divider was deleted with the only test under it.
- In `SessionViewModelTest` the `// ---- CFX-02: recalibrate / empty-state
  targeting` divider was **left**; it now sits above a helper with no test
  under it.

---

## Verification

- **Core** (`build/core`, llvm-mingw clang 22.1.8): builds; ctest 11/11
  passed — count unchanged.
- **Android** (`:app:assembleDebug :app:compileDebugAndroidTestKotlin
  :app:testDebugUnitTest --rerun-tasks`): BUILD SUCCESSFUL, including the
  `androidTest` source set. JUnit XML sum: **172 tests, 0 failures, 0
  errors, 0 skipped** = 174 baseline − 2 (items 1 and 2 of the authorized
  list). The 174 baseline was re-read from the pre-change XMLs.
- Packaged `libsynccore_jni.so` (arm64-v8a, armeabi-v7a, x86_64) timestamps
  are newer than the changed C++ sources.
- No reference to `notifyLocalPlayback`, `sc_notify_local_playback`,
  `kLocalPlayback` or `last_commanded_position_ms` remains under `core/`,
  `android/app/src/` or `technical-requirements.md`.

## Not done / reported instead

- **Item 6 has not been seen rendered.** The table above is from the code.
- `core/tests/test_synccore.cpp:238-239` still comments `// clamps to +750`
  / `// clamps to -750`. The real clamp is ±4000. Editing it was not
  authorized.
- `technical-requirements.md:68` still says `// ±750 clamp` beside
  `sc_set_user_nudge_ms`. It was not in the ticket's list, so it was left.
- `architecture-spec.md:140` gives the wheel's range as ±750 ms; the code
  comment in `synccore.cpp` says the wheel's UI range is ±1500. Not checked
  further, not in scope.
