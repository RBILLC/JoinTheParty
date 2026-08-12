# CFX-03 Implementation Review — CaliperScale semantics + connected-state non-colour encoding (Issue #27)

Date: 2026-08-12
Scope: Android UI layer, `CaliperScale` and its accessibility contract (tech-req §2.6
"CaliperScale accessibility contract" / "Connected-state encoding"; ui-ux-design-system.md
§6.5 "Accessibility contract (Input mode)" / "Settled line").

## What the issue required

Issue #27 (labels `partial`, `epic-7-cfx`) bundled two originally-separate defects under one
ticket:

1. **CaliperScale has no accessibility contract.** `CaliperScale` is a bare `Canvas` with a
   bespoke drag gesture; because By-ear tone-match is the *only* calibration path ever
   offered on a route that can't be measured acoustically, a screen-reader user with no
   drag-free path to a value cannot calibrate that device at all. Required: a value/state
   description (ms) on both `ReadOut` and `Input`, plus a drag-free commit path
   (increment/decrement) on `Input`.
2. **Connection state was colour-only.** The settled line's `brass`/`ink2` colour was the
   sole encoding of "this is the currently-connected device" — unlike provenance three doors
   down, which carries three redundant tells. Required: a plain-text "Connected" qualifier
   wherever the brass line appears.

Five acceptance criteria: (1) a semantics test for `ReadOut`'s value/state description, with
an explicit fallback to "flag as needing a device pass" if the project's test setup can't do
headless semantics assertions; (2) a semantics test for `Input`'s accessibility
increment/decrement action, explicitly described as testable *without* a live TalkBack
session; (3) an explicitly-flagged, not-JVM-testable device/TalkBack pass; (4) a unit test
that `DeviceShelfRow`/`DeviceDetail` render "Connected" iff `connected == true`; (5) a copy
audit against ui-ux §6.5's verbatim wording.

## What already existed (before this pass)

The issue's own status note ("ReadOut/Input semantics... + the 'Connected' text tell done;
pending: TalkBack/device pass") was accurate and verified by inspection:

- **`CaliperScale.kt`** already had a full `.semantics { }` block: `contentDescription` +
  `stateDescription` on both modes, and `progressBarRangeInfo` + `setProgress` on `Input`
  (the standard Compose idiom for TalkBack's increment/decrement gesture — same pattern as
  `NudgeWheel`). `caliperSnapToStep` quantized the accessibility target to
  `CALIPER_ADJUST_STEP_MS` (±30 ms, anchored to the by-ear accuracy floor). All of this was
  covered by `CaliperScaleTest.kt`, but only at the *pure-function* layer (the announcement
  copy and the step arithmetic) — the semantics **attachment** itself (does a real
  `SemanticsConfiguration` actually end up carrying these values/actions?) was uncovered by
  any test and explicitly documented as such.
- **The "Connected" text tell (AC4/AC5)** was fully implemented and tested:
  `Provenance.kt`'s `provenanceQualifier(profile, nowMs, connected)` appends
  `CONNECTED_QUALIFIER_SUFFIX = "· Connected"` whenever `connected == true`, threaded through
  from both `DeviceShelf.kt`'s `DeviceShelfRow` and `DeviceDetail.kt` (including its drift-
  banner branch). `ProvenanceTest.kt` already had a dedicated "CFX-03: Connected-state
  encoding" section (6 tests) asserting the suffix constant, its presence when `connected`,
  its absence by default/when `false`, and that it survives the drift-banner swap. The
  rendered text — `"MEASURED · measured 2 days ago · Connected"` — matches ui-ux §6.5's
  worked example character-for-character. I made **no changes** here; it was already correct
  and already tested per AC4/AC5's actual wording ("the rendered provenance/title string
  contains 'Connected'").

## What I changed, and why

The only real gap was AC1/AC2: the *attachment* of `CaliperScale`'s semantics was untested,
even though AC2 explicitly frames this as achievable without a live TalkBack session
("invokable directly as a semantics-action lambda in a unit test"). I confirmed this is
possible in this project's plain-JVM (no Robolectric, no `androidx.compose.ui:ui-test*`)
unit test setup: `androidx.compose.ui.semantics.SemanticsConfiguration` is a plain Kotlin
class with a public no-arg constructor and no Android framework dependency — it's the same
object type a real `Canvas` node's semantics populate, just constructible directly in a JVM
test.

**`CaliperScale.kt`**: extracted the two `when (mode)` branches out of the inline
`.semantics { }` lambda into two internal `SemanticsPropertyReceiver` extension functions,
`applyCaliperReadOutSemantics(settledValueMs: Float?)` and
`applyCaliperInputSemantics(mode: CaliperMode.Input)`. The composable's `.semantics { }`
block now just dispatches to them (`is CaliperMode.ReadOut -> applyCaliperReadOutSemantics(...)`
etc.) — behavior is unchanged, byte-identical semantics output, just refactored so the exact
same code path is callable from a test with a `SemanticsConfiguration()` receiver instead of
only from inside a live Compose composition. Comments carried over verbatim from the original
inline block.

**`CaliperScaleTest.kt`**: added a new "Semantics attachment" section (9 tests) that
constructs a real `SemanticsConfiguration()`, applies the two functions above, and reads back
exactly what a screen reader would see/invoke via `SemanticsConfiguration.getOrNull(key)`
(the documented readback API — the builder-style `receiver.property` getters throw
`UnsupportedOperationException` when used outside a `.semantics { }` lambda, which is why the
tests use `getOrNull(SemanticsProperties.X)` / `getOrNull(SemanticsActions.SetProgress)`
rather than the property-syntax getters):

- `ReadOut` exposes `contentDescription` = `"Calibration scale"` and `stateDescription`
  containing the settled ms value (or `"Not calibrated"` when `null`) — satisfies AC1 as a
  genuine headless semantics assertion, stronger than the AC's own fallback ("otherwise flag
  as needing a device pass") required.
- `Input` exposes a distinct `contentDescription`, a live `stateDescription`, and a
  `progressBarRangeInfo` spanning `0f..scaleRangeMs` at the current cursor value.
- `Input`'s `SemanticsActions.SetProgress` action, invoked directly as a lambda (no
  TalkBack, no Compose test framework), calls `onCursorChange` with the cursor offset by
  exactly one `CALIPER_ADJUST_STEP_MS` step in both directions (increment: 300→330,
  decrement: 300→270) — satisfies AC2 exactly as specified, plus a regression guard that an
  unaligned raw target (223) still snaps to the nearest step (210) before committing.
- `ReadOut` does **not** expose `SemanticsActions.SetProgress` — a read-only surface must not
  accidentally offer a commit path with nothing to commit to.

Also updated a stale comment block in `CaliperScale.kt` (immediately above the accessibility
helpers) that claimed the semantics attachment was "NOT covered by any automated test here" —
it now describes what is and isn't covered post-change.

## Accessibility treatment

- **Non-colour connected-state encoding**: verified pre-existing and correct — see above.
  `brass` vs. `ink2` remains the *reinforcing* signal; the `"· Connected"` text is the
  primary, colour-independent tell, present on both the shelf row and the detail pane
  (including under the drift banner).
- **TalkBack path for `Input` mode**: `contentDescription` names the control distinctly from
  `ReadOut`; `stateDescription` announces the live ms value; `progressBarRangeInfo` +
  `setProgress` is the platform-standard way to make a Compose node "Adjustable" to
  TalkBack's increment/decrement gesture without requiring a drag — confirmed by code
  inspection to be wired correctly and now confirmed by test to actually populate the
  `SemanticsConfiguration` TalkBack would read. The drag gesture (`pointerInput`) is layered
  on top of, not instead of, the `.semantics` modifier, so it can never shadow the
  accessibility node.
- **AC3 (device/TalkBack pass): NOT DONE.** This remains genuinely outside what any JVM
  test — including the new `SemanticsConfiguration`-based ones — can verify: whether
  TalkBack itself discovers this node, announces it correctly, and turns its own gesture
  into the `setProgress` call is platform accessibility-service behavior, observable only
  on a real device/emulator with TalkBack running. Explicitly flagged per the issue's own
  instruction; no test in this repo substitutes for it.

## Tests run

`android/`: `./gradlew.bat :app:testDebugUnitTest` (full suite, not just the touched class).

- **155 tests, 0 failures, 0 errors** (aggregated from `app/build/test-results/testDebugUnitTest/*.xml`).
- `CaliperScaleTest`: 19 tests (10 pre-existing + 9 new), all passing.
- `ProvenanceTest`: unchanged, all passing (confirms the pre-existing Connected-suffix
  coverage still holds after the `CaliperScale.kt` refactor, which never touched
  `Provenance.kt`).
- No existing test was modified or removed.

Not run: `assembleDebug`, any instrumented/`androidTest` task, install on device/emulator —
out of scope per task instructions.

## AC-by-AC status

| AC | Status | Notes |
|---|---|---|
| 1. `ReadOut` semantics unit test | **DONE** | Real `SemanticsConfiguration` assertions, not just the pure-function fallback the AC permitted. |
| 2. `Input` accessibility increment/decrement unit test | **DONE** | `SemanticsActions.SetProgress` invoked directly; asserts exact one-step offset both directions. |
| 3. Device/TalkBack pass | **NOT DONE** — explicitly flagged, as the issue itself anticipates. No JVM test can substitute; requires a real device/emulator with TalkBack. |
| 4. `DeviceShelfRow`/`DeviceDetail` "Connected" unit test | **DONE (pre-existing)** | `ProvenanceTest.kt`'s CFX-03 section, unchanged by this pass. |
| 5. Copy audit vs. ui-ux §6.5 verbatim | **DONE (pre-existing)** | `connectedQualifierSuffixMatchesTheDeckVerbatim` + the worked-example test; string matches §6.5's `"MEASURED · measured 2 days ago · Connected"` exactly. |

## Files touched

- `android/app/src/main/java/com/jointheparty/app/ui/components/CaliperScale.kt` — refactor
  only (extracted two testable semantics-application functions; no behavior change).
- `android/app/src/test/java/com/jointheparty/app/ui/components/CaliperScaleTest.kt` — 9 new
  tests + updated class KDoc.
- `docs/cfx03-review.md` — this file (new).

No other files were modified. `DeviceShelf.kt`, `DeviceDetail.kt`, `Provenance.kt`, and their
tests were read for verification but not changed — the connected-state text tell they already
carry was correct and already covered.
