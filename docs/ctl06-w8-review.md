# CTL-06/W8 implementation review — ACRCloud raw timing/skew diagnostic line

**Ticket:** GitHub issue #50 (CTL-06/W8). **Context:** chronic zEnd bias
investigation, wayfinder map #41 — leading root-cause candidate is that
`ACRCloudProvider` pairs `play_offset_ms` with the capture window's END
under an undocumented assumption (ACRCloud's own docs example pairs
`play_offset_ms` 9040 with `sample_end_time_offset_ms` 9280, not begin), and
that the provider reads `frequency_skew` when ACRCloud's documented schema
names the field `time_skew` (every field fix has logged skew=0.0, possibly
because the field name is wrong). **Status:** implemented, additive-only,
all touched suites green.

---

## What changed

**File:** `android/app/src/main/java/com/jointheparty/app/recognition/ACRCloudProvider.kt`
(303 lines, was 246).

1. **New `acrtime:` diagnostic log line**, emitted immediately after the
   existing `MATCH ✓ ...` line, same logger (`DebugLog`), same match path.
   Format:
   ```
   acrtime: off=<play_offset_ms> sBeg=<sample_begin_time_offset_ms> sEnd=<sample_end_time_offset_ms> dbBeg=<db_begin_time_offset_ms> dbEnd=<db_end_time_offset_ms> tskew=<time_skew or 'absent'> fskew=<frequency_skew or 'absent'> dur=<duration_ms or -1>
   ```
   Missing numeric fields print `-1`. The two skew fields print the raw
   value if the key is present in the response (any JSON type, via
   `JSONObject.has`/`.get(...).toString()`) else the literal `absent` — so a
   present-but-zero skew (the field-name-mismatch symptom under
   investigation) is visibly distinct from a genuinely absent key. `off` is
   the same `offsetMs` value the MATCH ✓ line just logged (passed in, not
   re-read), so the two lines always pair.

   Both log statements now live in `parseMatch` (previously only `parseMatch`
   built the `RecognitionFixResult`; the `MATCH ✓` log call lived in
   `recognizeOnce`'s `.also{}`). They were moved together into `parseMatch`
   because that's the only place the raw `music` JSONObject the `acrtime`
   line needs is in scope, and the two lines must be emitted in that literal
   order (`MATCH ✓` before `acrtime`, `off` values equal). `recognizeOnce`'s
   `withContext` block is now just `identify(cfg, window)` — no behavior
   change, only where the logging happens. `RecognitionFixResult` itself
   (fields, computation) is untouched, and `frequencySkew = music.optDouble
   ("frequency_skew", 0.0)` is untouched — this ticket makes fields visible,
   it does not fix the candidate field-name bug.

   Formatting is factored into a new pure companion function,
   `formatAcrTimeLine(offsetMs, sampleBeginMs, sampleEndMs, dbBeginMs,
   dbEndMs, timeSkew, frequencySkew, durationMs): String`, called by a thin
   `acrTimeLine(music: JSONObject, offsetMs: Long): String` wrapper that does
   the JSON-field extraction. This split exists purely for testability — see
   below.

2. **Hint-text fix**: `none(enable 3rd-party integ.)` → `none(no spotify
   mapping)` in the `MATCH ✓` line's `uri=` fallback, and the comment above
   `spotifyUri`'s parse updated from "Requires '3rd Party Integration'
   enabled on the ACR console project; absent otherwise" to state that
   integration is already enabled on this project (confirmed via the
   console pass) and that an absent URI means the matched catalog entry has
   no Spotify mapping, not that integration is off.

No other files in `android/main` were touched; `RecognitionProvider.kt` and
every caller of `ACRCloudProvider` are unmodified.

## Why the JSONObject-reading wrapper isn't the unit-tested surface

`org.json.JSONObject` is not usable in this module's plain JVM
`testDebugUnitTest` suite — there's no Robolectric dependency and no
`testOptions.unitTests.isReturnDefaultValues`, so `android.jar`'s stub
`org.json` methods throw `RuntimeException: Method optLong in
org.json.JSONObject not mocked` (confirmed empirically: an earlier version
of the test that called `ACRCloudProvider.acrTimeLine(music, ...)` directly
with a real `JSONObject` failed all 4 cases at the first `optLong` call with
exactly that exception; the JSONObject constructor itself worked, only
method calls on the resulting instance threw). No file outside `tests` and
this review doc may be touched under this ticket's rules, so adding a real
`org.json` test dependency or Robolectric to `app/build.gradle.kts` was not
an option. `acrTimeLine`'s string assembly was therefore extracted into the
pure `formatAcrTimeLine` (no JSONObject involved) so it's directly testable
with plain `Long`/`String` arguments; `acrTimeLine` itself is now a
one-line-per-field extraction with nothing left to verify once the
formatting it delegates to is covered.

## Test inventory

**New file:** `android/app/src/test/java/com/jointheparty/app/recognition/ACRCloudProviderTest.kt`
(113 lines) — no prior test existed for this provider.

- `formatAcrTimeLineReportsEveryFieldWhenTheResponseCarriedThem` — all eight
  fields present (using ACRCloud's own docs example values for
  off/sEnd/etc.) produce the exact expected line.
- `formatAcrTimeLinePrintsMinusOneForMissingNumericFieldsAndAbsentForMissingSkewKeys` —
  every numeric field defaulted to `-1`, both skew fields `"absent"`.
- `formatAcrTimeLinePrintsAPresentSkewValueEvenWhenItIsZero` — a
  present-but-zero skew value renders `0.0`, not `absent` (the symptom the
  audit flagged: "every field fix has logged skew=0.0").
- `formatAcrTimeLineUsesTheOffsetPassedInVerbatim` — the `off=` field in the
  output is exactly the value passed in.

Existing tests are byte-unmodified — no other test file in the repo was
touched.

## Zero-behavior-change verification

- `RecognitionProvider.RecognitionFixResult`'s fields and every expression
  that computes them (`matchOffsetMs`, `captureMonoNs`, `frequencySkew`,
  `confidence`, `title`, `artist`, `isrc`, `spotifyUri`) are byte-identical
  to before this change.
- `identify()`'s HTTP request construction, signing, and response-code
  handling are untouched.
- The only functional addition is two new `DebugLog.log(...)` calls
  (`acrtime:` line) plus relocating the pre-existing `MATCH ✓` log call from
  `recognizeOnce` into `parseMatch` — same text (aside from the hint-text
  fix), same trigger condition (a successful match), same logger.
- `SessionViewModelTest.kt` (which constructs `RecognitionProvider.
  RecognitionFixResult` directly, bypassing `ACRCloudProvider` entirely) is
  unaffected and unmodified — confirmed by the full suite rerun below.

## Commands run and results

```
cd android
./gradlew.bat :app:testDebugUnitTest --rerun-tasks
# -> BUILD SUCCESSFUL

# Authoritative count via JUnit XML sum (build-environment.md's documented
# trap: gradle's own summary can under-report on a warm run) — summed
# tests="N" across all 11 TEST-*.xml files under
# app/build/test-results/testDebugUnitTest/:
#   files=11 tests=171 (167 baseline + 4 new, all in
#   TEST-com.jointheparty.app.recognition.ACRCloudProviderTest.xml)

./gradlew.bat :app:assembleDebug
# -> BUILD SUCCESSFUL
```

The 171 count is the sum actually observed on this run (167 + 4), not a
projection.
