# Android UI Implementation Review — Billet on Compose · 2026-07-22

**Scope:** UI-01 (theme foundation), UI-03 (Sync Meter), UI-04 (Nudge Wheel), UI-02 (session state machine + persistence).
**Design source:** `ui-ux-design-system.md` ("Billet") via the generated `DesignTokens.kt` — no widget hardcodes a value.

---

## UI-01 — Theme foundation (commit `acf8bc6`)

| Piece | File | Notes |
|---|---|---|
| Theme + type ramp | `ui/theme/BilletTheme.kt` | Dark-committed Material3 scheme mapped from `DT.Colors` (background `void`, surface `billet`, primary `brass`, error `oxide`). `BilletType` builds every `TextStyle` from `DT.Type` tokens: size, weight, tracking as **percent-of-size** (heroMs: −2% of 76sp = −1.52sp), line height, `tnum` feature where tabular. |
| Machined depth | `ui/theme/Machined.kt` | `Modifier.machinedDepth`: 12dp soft shadow + 1px top inner highlight (`ink` @ 4%). `Modifier.recessedWell`: carved-in `recess` fill, dark occlusion line at the top lip, caught-light line at the bottom lip. No blur/translucency/glow anywhere (§4: machining, not glass). |
| Heat scale | `ui/theme/Heat.kt` | `heatColor(t)`: `graphite` → `bronze` (t=0.5) → `brass` (t=1). The app's single accent, expressed as temperature. |

**Instrument Sans status:** the variable font file isn't licensed/bundled yet. All styles are structured so the swap is one line (`BilletFontFamily`); until then platform sans carries exact sizes/weights/tracking/`tnum`.

## UI-03 — Sync Meter, "phase horizon" (commit `acf8bc6`)

`ui/components/SyncMeter.kt` + `ui/model/MeterFrame.kt`. Emulator-verified in all three states (drifting −161 ms cold/faint, converging −37 ms bronze, locked fused `brassBright` + brass "in sync").

- **Geometry:** recessed 96dp well; reference line (`ink2`, 1.5dp) at center; local line offset by the log map `y = 20·ln(1 + |e|/48)` pt — fits the spec anchors (±5 ms → 2pt, ±250 ms → 36.7pt vs 36 specced), clamped to ±40pt. Ahead = above.
- **Motion:** offset animated with a critically damped spring (no overshoot — `settle`, §5); color runs the heat scale on a 900 ms `heat` tween; confidence maps to line alpha 0.35→1.0 (uncertainty reads as *faint*, never as flashing).
- **Fusion:** when `converged && |error| ≤ deadband` and the animated offset has effectively landed, the two lines are replaced by one 2dp `brassBright` line — the product's promise in one image.
- **Readout:** `heroMs` (76sp Light, tabular) signed value; at lock it becomes "in sync" in `brass`.
- **Accessibility:** position is the encoding, the semantics narrate it ("180 milliseconds behind, converging"), magnitude rounded to 10 ms so TalkBack isn't spammed.

### The two-stream rule: how 15 Hz redraws avoid recomposition

This is the load-bearing architectural detail (tech-req §2.3 acceptance: zero recompositions of the session root during meter animation).

1. **One collection point.** `SyncMeter` collects the conflated `Flow<MeterFrame>` in a `LaunchedEffect` and writes into: a `mutableStateOf<MeterFrame>`, and two `Animatable`s (line offset, heat temperature).
2. **Draw-phase reads.** Those states are read **inside the `Canvas` draw lambda only**. Compose subscribes state reads per phase: a read that happens during the draw phase invalidates *only the draw pass* of that node. A new frame or animation tick therefore re-executes ~30 lines of `DrawScope` code — no composition, no layout, for the meter or anything above it.
3. **Quantized leaves.** The two places that genuinely need composition (the ms readout `Text`, the semantics description) read through `derivedStateOf` with quantized outputs — whole-ms string, 10 ms-rounded narration — so they recompose only when the *visible* value changes, not per 66 ms frame.
4. **Structural guarantee upstream.** The frame flow is passed into `SyncMeter` as a parameter and is never collected into any ViewModel/session state (`meterFrames` is a pass-through on the store side), so the session screen root *cannot* observe it. The rule is enforced by architecture, not discipline.

Remaining acceptance evidence, tracked in the backlog: Layout Inspector recomposition counts on a scripted 15 Hz run, and a TalkBack pass on hardware.

---

## UI-04 — Nudge Wheel, "trim dial"

`ui/components/NudgeWheel.kt`. Public API: `NudgeWheel(trimMs, routeName, onTrimChange, onTrimCommit, modifier)` — the caller owns the committed value; the wheel keeps gesture-local state while dragging (optimistic display, tech-req §2.2 pattern).

- **Visual:** a horizontal machined drum *edge* (pill, `machinedDepth`, 88dp) — not a face-on knob. Knurl striations (~6dp pitch) drawn on Canvas translate 1:1 with the finger, alpha-faded toward the drum's edges to suggest curvature; `brassBright` specular line along the top. Above: engraved `TRIM`, the live value in 22sp Light tabular, and the route name — making per-route persistence visible on the control (§6.2).
- **Mechanics:** 9dp travel = 1 detent = 5 ms (all from `DT.Wheel`, nothing hardcoded), clamped ±750 ms with rubber-band visual (≈30% rate past the stop) and a firm end-stop haptic. Flick → inertial roll via `exponentialDecay(frictionMultiplier = 3f)` with detent haptics continuing through the roll; **reduced motion** (`ANIMATOR_DURATION_SCALE == 0`) disables inertia entirely. Double-tap zeroes the trim.
- **Haptics:** `BilletHaptics` maps `DT.Haptics` tokens to `VibrationEffect` composition primitives (`PRIMITIVE_TICK`/`CLICK` with token intensity) on API 30+, amplitude-scaled one-shots below; `VibratorManager` on 31+. Ticks fire only on detent boundary *crossings* — no spam. Required adding `VIBRATE` to the manifest.
- **Commit path:** `snapshotFlow { liveTrim }.debounce(400 ms)` → `onTrimCommit` exactly once per settled adjustment — the hook that calls `sc_set_user_nudge_ms` (one micro-seek per adjustment, §6.2).
- **Two-stream discipline:** drag offset lives in state read only inside the Canvas draw lambda; the value text is a quantized `derivedStateOf` leaf, same pattern as the meter.
- **Emulator-verified:** rendered correctly; a scripted 500px swipe moved trim −180 → −75 ms, consistent with detent math + inertia.
- **Deferred to follow-ups** (in §6.2 prose but outside this ticket's cut): long-press numeric entry, A/B hold-to-mute button, >100 ms reset confirmation.

## UI-02 — Session state machine + persistence

`ui/session/` + `data/NudgeStore.kt` + `core/SyncEngine.kt`. **8/8 JVM unit tests green.**

- **Testability seam:** `SyncEngine` interface extracted from the JNI bridge's control surface; `SyncCore` implements it. The ViewModel depends on the interface, so the state machine unit-tests on the JVM with a `FakeSyncEngine` — no native library, no device. (One knock-on: Kotlin forbids default parameters on overrides, so `submitRecognitionFix` lost its `frequencySkew = 0.0` default; call sites pass it explicitly.)
- **State machine:** `SessionPhase` (11 states, tech-req §2.4) with every transition gated through one `transition()` function against an explicit allowlist — illegal transitions are ignored, so the table in the spec *is* the implementation. Engine events drive CONVERGING/DRIFTING↔LOCKED via the `converged` flag; `TrackLost` auto-restarts listening with a **3-strike counter → ERROR**, reset upon reaching LOCKED (backlog UI-02 AC).
- **Two-stream rule, enforced structurally:** `SessionViewModel.meterFrames` is a *pass-through* mapping of `engine.meterFrames` — never collected into `syncState`, so meter traffic cannot recompose anything observing session state.
- **Subscription race, caught by tests:** the engine's `SharedFlow` has no replay, so the ViewModel's event collector starts `CoroutineStart.UNDISPATCHED` — registered synchronously in the constructor before any event can be emitted. Same class of bug the bridge instrumentation test hit; now closed on both consumers.
- **Persistence (`NudgeStore` interface + `DataStoreNudgeStore`):** Preferences DataStore keyed per route id (`"bluetooth:AirPods Pro"`): trim *and* learned command latency (PM decision 2026-07-21 — latency survives cold starts; `onRouteChanged` replays both into the engine, `onNudgeCommitted` writes through).
- **Test coverage:** happy path to LOCKED, converged-flag flapping (LOCKED↔DRIFTING), 3-loss escalation to ERROR, counter reset after re-lock, illegal-transition rejection, nudge persistence write-through, route-change restore.

## Build status

`:app:testDebugUnitTest` (8/8) and `:app:assembleDebug` green on the Windows host. One integration fix during assembly: `exponentialDecay` needed an explicit `<Float>` type argument (the generic can't infer from `AnimationState` at that call site).
