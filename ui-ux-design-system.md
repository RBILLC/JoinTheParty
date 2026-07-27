# JoinTheParty — Design System: "Billet"

**Phase:** UI/UX Discovery
**Date:** 2026-07-21
**Codename:** *Billet* — every surface reads as if machined from a single block of warm metal, not rendered as a glowing dashboard.

---

## 0. What we scanned, and what we're leaving behind

The three prior repos (`NYCSmiley`, `space_bet`, `CatPlants`) share an explicit house contract — NYCSmiley's `theme.dart` even names it: *"dark #121212 base, translucent panels with hairline edges, Saira SemiCondensed for structure, JetBrains Mono with tabular figures for every value."* In practice that produced a **telemetry-HUD idiom**: cold flat black, translucent glass panels, hairline tick borders and HUD corner brackets, breathing/pulsing gradient backgrounds, brand cyan→blue gradients (`#0DCAF0 → #0D6EFD`), taxi-yellow CTAs, `LIVE` tags, odometer counters with delta chips, and tracked-uppercase mono micro-labels stamped on everything.

**Explicitly retired:**

| Old habit | Why it dies | Billet replacement |
|---|---|---|
| Flat cold `#121212` + translucent "glass" panels | Reads as software pretending to be a dashboard | Warm near-black base; **opaque matte** surfaces with machined depth (top-edge highlight + soft shadow) |
| Breathing/pulsing animation as constant decoration | Ambient motion behind playback, or laid over a data-dense surface, reads as arcade energy the moment it never stops | Static in steady state. Ambient motion is welcome in transitional/waiting states — discovery, pairing, listening, matching — where it signals the system working, not mood lighting; prefer driving it from a real signal over letting it free-run (§5) |
| Cyan/blue brand gradient, taxi yellow | Neon = "crypto bro" signature | One metallic accent (burnished brass), used for exactly one meaning: sync heat |
| Hairline HUD ticks, corner brackets, `LIVE` tags | Decoration cosplaying as instrumentation | Structure carries meaning or doesn't exist; no badges |
| JetBrains Mono for every value | Coder-terminal voice | One grotesk family; values are Light-weight tabular numerals, not code |
| Uppercase tracked micro-labels everywhere | Shouty, gamified | Sentence case by default; uppercase reserved for **engraved control labels** only (§3) |
| Odometer counters, delta chips, gamified stats | Dopamine UI | Zero gamification; the only number that matters is milliseconds |

What *survives* from the old contract, because it was always good practice: a single tokens file no widget bypasses, tabular figures for all numerals, and saturated color reserved for meaning.

---

## 1. Design thesis

JoinTheParty is a **precision instrument you happen to hold at a beach party**. The reference feeling is high-end audio hardware — dense, matte, warm, machined — without imitating any brand's trade dress. The interface should feel the way a good volume knob feels: heavy, damped, certain.

One aesthetic risk, spent in one place: **sync state is temperature, not color-coding.** The interface's sole accent behaves like metal being heated — graphite when searching, warming through bronze as the Kalman filter converges, fully burnished brass at lock. No green checkmarks, no status chips. You *see* the app lock the way you see an amplifier's standby lamp warm up.

Trademark guardrail: no B&O marks, wordmarks, product silhouettes, or copies of their specific layouts/renders. We take the *discipline* (negative space, restraint, tactility) and build our own material vocabulary.

---

## 2. Color — "warm metal in low light"

All hexes are the single source of truth; no widget hardcodes color.

### Base (warm, not cold — deliberately off the old `#121212` axis)

| Token | Hex | Role |
|---|---|---|
| `void` | `#131110` | App background. Warm near-black (brown-cast, not blue-cast). |
| `billet` | `#1D1A17` | Raised surface — cards, sheets, the wheel housing. Opaque, matte. |
| `recess` | `#0C0B0A` | Recessed wells — the meter window, text fields. Darker than background: things are *carved in*, not floated on. |
| `hairline` | `#332F2A` | 1px separators, control outlines. One value; no alpha-stacked border zoo. |

### Ink (warm greys)

| Token | Hex | Role |
|---|---|---|
| `ink` | `#EDE7DE` | Primary text, hero numerals. Bone white, never pure `#FFF`. |
| `ink2` | `#A79E93` | Secondary text, labels. |
| `ink3` | `#6B645B` | Tertiary — engraved labels, disabled, fine print. |

### Accent — the heat scale (one accent, four temperatures)

| Token | Hex | Meaning |
|---|---|---|
| `graphite` | `#4A463F` | Cold: idle, searching, no lock. |
| `bronze` | `#8A6F4B` | Warming: converging, |error| shrinking. |
| `brass` | `#C79A63` | Hot: locked. The only saturated moment in the app. |
| `brassBright` | `#EAD3A6` | Specular tip — wheel edge highlight, the fused line at lock. Never used as fill. |

Interpolation between temperatures is continuous, driven by `MeterFrame.confidence × convergence` — the accent is a *signal*, not a palette entry to decorate with.

### Functional

| Token | Hex | Role |
|---|---|---|
| `oxide` | `#B4574E` | Errors and destructive actions only. Muted, never glowing. |
| `spotify` | `#1DB954` | Spotify's own brand green, used **only** inside Spotify attribution/connect elements per their guidelines. Never for our UI meaning. |

Rule: at any moment the screen contains **at most one warm accent element**. If the meter is hot, buttons stay quiet.

---

## 3. Typography — one family, many voices

**Family: Instrument Sans (variable)** — Google Fonts, free for commercial use, and unrelated to both the old Saira/JetBrains pair and any hardware brand's proprietary face. One family across the entire app; hierarchy comes from weight and optical scale, not from font-switching. All numerals set with `tnum` (tabular).

| Role | Size / weight / spacing | Usage |
|---|---|---|
| `heroMs` | 76pt · Light 300 · −2% tracking · tabular | The milliseconds readout. The largest thing in the app; it earns the space by being the product. |
| `heroUnit` | 17pt · Medium 500 · `ink3` | The "ms" beside it, baseline-aligned. |
| `title` | 28pt · Medium 500 · −1% | Track title. |
| `subtitle` | 16pt · Regular 400 · `ink2` | Artist line. |
| `body` | 15pt · Regular 400 · 1.5 line height | Onboarding copy, explanations. |
| `label` | 13pt · Medium 500 | Buttons, list rows. Sentence case. |
| `engraved` | 10pt · SemiBold 600 · +14% tracking · UPPERCASE · `ink3` | **The only uppercase in the app.** Reserved for control-plate labels physically attached to instrument components: the wheel's `TRIM`, the meter's `+` / `−` scale ends, `A/B`. Mimics engraving on a faceplate; never used for section headers, tags, or emphasis. |
| `fine` | 11pt · Regular 400 · `ink3` | Legal, latency fine print. |

Dynamic Type / font scaling: everything scales except `heroMs` (clamped at 1.3×) and `engraved` (fixed — it's part of the control's artwork; its meaning is always duplicated accessibly).

---

## 4. Space, shape, material

- **Grid:** 8pt. Screen gutters 24. Sections separated by whitespace (≥ 40pt), never by divider lines. `hairline` appears only on interactive control outlines.
- **Negative space is the luxury.** The session screen holds exactly four elements: track identity, the meter, the ms readout, the wheel. Nothing else. Settings, calibration, A/B live behind a single quiet entry point.
- **Radius:** continuous (squircle) 24 on cards/sheets, fully-round on controls. No sharp corners anywhere — the old system's razor edges are gone.
- **Depth = machining, not glass:** raised elements get a 1px top inner highlight (`ink` at 4%) + a soft 12pt shadow (black 35%); recessed wells get the inverse (1px bottom-inner light edge). No blur, no translucency, no glow/bloom ever.
- **Iconography:** 1.5px stroke, round caps, geometric, `ink2`. No filled icons except the play state.

---

## 5. Motion & haptics — "damped mass"

Everything moves like it has weight and a damper. One principle governs when motion is allowed to run on its own: **steady state stays still; transitional and waiting states may breathe.** Once something's locked, settled, or simply there to be read (the session at lock, a list, the caliper scale), motion stops — there's nothing left to signal, only something to look at. While the system is working on something the user is waiting on — device discovery, pairing, listening, matching — ambient motion is welcome, because it *is* information (the system is alive and busy) rather than decoration on top of information. Where a real signal exists, drive the motion from it; free-running motion is the fallback only where no signal exists. The instant a lock happens, ambient motion stops outright and the heat scale (§2) becomes the one living element on screen — the instrument settling, not two things moving at once.

| Token | Spec | Used by |
|---|---|---|
| `settle` | Critically damped spring, ω≈14, no overshoot | Meter line movement, layout transitions |
| `heavy` | Spring with mass 1.4, slight overshoot | Sheet presentation, wheel release |
| `heat` | 900ms ease-in-out color interpolation | Accent temperature changes |
| Reduced Motion | Springs → 200ms crossfades; wheel inertia off (detent-step only) | System setting, respected globally |

**Haptic vocabulary** (iOS Core Haptics / Android `VibrationEffect` compositions):

| Event | Haptic |
|---|---|
| Wheel detent (each 5 ms) | Light tick, sharpness high, intensity 0.4 |
| Wheel coarse stop (50 ms boundary) | Medium tick, 0.7 |
| Sync lock achieved | Single heavy "thunk" — transient, intensity 1.0, sharpness low. The signature physical moment: the app *engages* like a tonearm dropping. |
| Lock lost | Two soft ticks descending |
| A/B toggle | Rigid click |

Sound: none. An audio-sync tool must never emit UI sounds.

---

## 6. Components

### 6.1 The Sync Meter — "phase horizon"

Not a gauge, not a bar with ticks (both retired idioms). The meter is a recessed horizontal window (`recess` well, full-width, 96pt tall) containing **two thin horizontal lines**:

- **Reference line** (the external speaker): fixed at vertical center, 1.5px, `ink2`.
- **Local line** (your playback): same weight, offset vertically by sync error — mapped logarithmically, ±5 ms ≈ ±2pt, ±250 ms ≈ ±36pt. Ahead = above, behind = below.

Behavior:
- While converging, the local line drifts toward the reference under `settle` physics; its color runs the heat scale with confidence.
- **At lock** (|error| inside deadband, converged): the two lines **fuse into a single 2px line** in `brassBright`, with the lock "thunk" haptic. Two waves become one — the product's entire promise in one image.
- On drift: the line splits again, cooling toward `bronze`.
- Below the well: the `heroMs` readout shows signed error (`−12 ms`), live at ≤15 Hz from the `MeterFrame` stream; at lock it reads `in sync` (Light 300, `brass`).
- `engraved` `+`/`−` labels at the well's right edge mark ahead/behind. Confidence renders as the local line's opacity (0.35 → 1.0) — uncertainty looks *faint*, not flashing.
- Accessibility: meter state mirrored to an accessibility label ("12 milliseconds behind, converging"); error direction never encoded by color alone (position is the encoding).

**Before the meter — Listening / Matching**

No `MeterFrame` stream exists yet in these phases — there's no fix to report — so the phase word is the entire screen: `title`/`ink2`, centered, with a quiet `Cancel` beneath it in `label`/`ink3`. Copy is confirmed as-is: **"Listening…"**, then **"Matching…"** — plain, and it doesn't oversell what's happening.

A smoothed input-level signal now exists here (≤15 Hz, alongside the meter's own stream family, never in `SyncState`), and Listening/Matching are themselves waiting states — exactly the case §5's motion principle names as welcome. The phase word's opacity tracks it: `ink2` at rest, brightening toward `ink` as level rises, `0.55 + 0.45 × level` (level normalized 0–1), through `settle` (§5, ω=`settleOmega`) so a stray syllable doesn't flicker it — it eases toward the new level and holds, weight responding to loudness rather than jitter. Nothing else moves: no scale, no bounce (arcade), no glow or gradient (retired, §2/§4). No color changes either — these phases have no sync heat yet, so the word stays inside ink/ink2/ink3 throughout; `brass` still means sync confidence alone, nothing else, ever.

At silence the word holds its 0.55 floor — dim, not gone. That dimness *is* the "we can't hear anything" signal, legible without a line of copy. If it never lifts, the existing recognition-timeout backstop already covers the terminal case (`"Couldn't find the song — tap to try again"`, the `ERROR` phase) — no second error string is needed here.

It stops at `AIMING`: once a fix lands and the meter appears, the heat scale becomes the one living element on screen (above), and the phase word's own motion has nothing left to add.

**Reduced Motion**: level no longer drives continuous opacity. It quantizes to two states — dim / bright — and crossfades between them over `reducedMotionCrossfadeMs` (200 ms) only when the state actually changes, rather than tracking continuously.

### 6.2 The Nudge Wheel — "the trim dial"

A horizontal **cylinder edge**, not a face-on knob: the bottom 88pt of the session screen shows the edge of a wide drum, as if a machined roller were embedded in the device's bottom bezel — knurled with fine vertical striations (drawn, subtle parallax as it rolls), matte `billet` body, `brassBright` specular line along its top edge.

Interaction:
- **Drag horizontally** to roll. 1 detent = **5 ms**, ≈ 9pt of travel per detent, light tick haptic per detent. The striations move with your finger 1:1 — direct manipulation, no abstraction.
- **Flick** for inertial roll with heavy friction (`heavy` release physics); detents click past audibly through haptics. Reduced Motion: inertia disabled.
- **Long-press** the readout to type a value; **double-tap** to zero the trim (with confirm if |trim| > 100 ms).
- Range ±750 ms, hard stop with a compressed-spring rubber band and a firm end-stop haptic.
- Above the drum: `engraved` label `TRIM`, current value in 22pt Light tabular (`−180 ms`), and the persisted route name (`AirPods Pro`) in `fine` — making per-route persistence visible.
- The wheel **re-aims** the sync target (spec §8, `technical-requirements.md`): committed value debounced 400 ms → one micro-seek. The meter visibly re-settles after each commit — cause and effect in one glance.
- A/B check: a small round `engraved`-labeled `A/B` button beside the drum mutes local playback while held (compare against the room). Rigid click haptic both ways.

### 6.3 Buttons

| Variant | Look | Use |
|---|---|---|
| Primary | Pill, `brass` fill, `void` text, Medium 500. Pressed: darkens 8%, scales 0.98 with `settle`. | One per screen maximum ("Join the party", "Connect Spotify") |
| Secondary | Pill, no fill, 1px `hairline` outline, `ink` text | Alternatives, dismissals |
| Quiet | Text-only, `ink2` | Tertiary ("Not now") |
| Destructive | Secondary with `oxide` text | Sign out, reset calibration |

No gradients on fills, no glow on press, no disabled-but-visible primaries (if the action is unavailable, the screen explains why instead — see 6.4).

### 6.4 Onboarding & the Premium gate

Onboarding is **three screens, one sentence each** (listen → match → play in sync), each illustrated by the phase-horizon motif progressing toward fusion. No feature tours.

**The Spotify Premium block** — treated as a graceful concierge moment, never a dead end or a dark pattern:

1. Detection is up-front (at connect, before the user is emotionally invested in a song — spec §2.4 `needsPremium`).
2. Screen copy (sentence case, plain, honest):
   - Title: **"Syncing needs Spotify Premium"**
   - Body: *"JoinTheParty drives your Spotify app and seeks to the exact beat — Spotify only allows seeking on Premium accounts."*
   - Primary: **"See Premium plans"** (deep-link to Spotify — clearly leaving our app; Spotify attribution per their brand guidelines, their green, their logo, untouched).
   - Secondary: **"Keep identifying songs"** — graceful degradation: without Premium the app still runs recognition and shows *what's playing and where it is in the track*, live. The user keeps real utility, learns the product, and upgrades when ready.
3. Same pattern for "Spotify not installed" (`needsSpotify`): explain, one primary ("Get Spotify"), one honest fallback.
4. Never: countdown timers, "unlock" language, feature-gating theatrics, or repeating the gate after dismissal more than once per session.

Errors app-wide follow the same voice: state what happened, state the fix, no apology theater. *"Can't hear the speaker — move closer or turn the music up."*

---

### 6.5 Calibration — provenance, the caliper scale, and the quiet entry point

Reached only from the single quiet entry point behind Settings/calibration/A-B (§4) — never surfaced on the session screen itself. Three provenance classes feed one shared visual language: the caliper scale, used to browse (device shelf), inspect (device detail), and — for By ear — dial in a value (tone-match, below). One signature element carries the whole feature, deliberately: §1's thesis is "a precision instrument you happen to hold," and a caliper *is* that instrument, literally rather than decoratively. No other visual device is introduced anywhere in this section.

**Provenance — three labels, and why they must never render identically**

| Label | What it means | How it's produced | Caliper tell |
|---|---|---|---|
| `Measured` | Ground truth: the phone heard its own chirp round-trip through this route. | Acoustic chirp (phone mic + speaker/BT speaker). | Real ticks (1–`maxRetainedSamples`), **solid** hairline. |
| `By ear` | The user judged it. | Tone-match ritual, or a nudge-wheel trim promoted after repeating (§6.2, below). | Real ticks, **solid** hairline. Accuracy is quoted as ±30 ms in copy, never implied tighter. |
| `Estimated` | We have not measured this device. A flat placeholder so playback isn't silent while unset — nothing is inferred or computed. | Applied only when the first-contact gate is declined. | Zero ticks, **dashed** hairline. |

Rendering these identically would flatten a real trust gradient: a measured chirp is closer to ground truth than a placeholder nobody's touched. The interface says so without a badge — provenance is carried by the engraved word itself plus the caliper's own tick-count and stroke-style vocabulary (below). No color is spent distinguishing them: color stays reserved for connection-state here and for the heat scale elsewhere in the app (§2).

**The caliper scale — the signature element**

Latency is one scalar, so this is not a scatter plot; it's a single horizontal ms axis, drawn like a vernier scale, that plays two roles — a **read-out** everywhere a value is shown (shelf, detail), and, during tone-match, an **input**: the same axis, dragged instead of read. One component, two modes; the display *is* the control.

- **Axis** — a `hairline` stroke spanning the available width. `0` at the left, `DT.Calibration.scaleRangeMs` (600 ms) at the right, both set in `engraved`/`ink3`, matching the meter's `+`/`−` well-edge labels (§6.1). Linear, not logarithmic: this axis compares devices to a fixed ritual and to each other, not error-around-zero like the meter, so it doesn't need the meter's log compression.
- **Measurement ticks** — one fine vertical line per retained sample, `DT.Calibration.tickStrokeWidthPt` (1pt) wide, in `ink3` at `DT.Calibration.tickAlpha` (0.35) — the same faint-means-uncertain idiom as the meter's confidence alpha. Where samples agree, their ticks land on the same column and compound under ordinary alpha blending — no stacking logic, just several translucent lines drawn on top of each other, darkening toward `ink` exactly where the device agrees with itself. A wide scatter stays faint and spread; a tight cluster reads as a solid dark band. No number, badge, or word is needed — spread *is* the confidence.
- **Retention** — up to `DT.Calibration.maxRetainedSamples` (12) most-recent samples, a ring buffer: a stale outlier from a noisier moment ages out on its own, so the strip always reflects current conditions. This is provenance-agnostic — an acoustic chirp reading and a tone-match result are both just ticks to the caliper (see Trim promotion, below, for the wheel's contribution).
- **Settled line** — the profile's committed value is a single `DT.Calibration.settledLineStrokeWidthPt` (2pt) hairline drawn over the ticks. Its **color** encodes connection state, not provenance: `brass` for the currently connected device (the one warm accent, §2/§4), `ink2` for every other known device — cold shelf, warm now, exactly one warm line on screen at a time. Its **stroke style** encodes provenance instead: **solid** where the line is backed by real ticks (Measured, By ear), **dashed** where it isn't (Estimated) — the honest tell that this number was never actually taken.
- **Zero / one / many samples**:
  - *No profile at all* (never seen before the gate ran): no line, no ticks — the row/pane reads "Not calibrated" instead of a provenance word.
  - *Estimated*: the settled line only, dashed, zero ticks.
  - *One sample*: a single tick coincident with the settled line — nothing to compound yet, so it reads at full `tickAlpha`, not stacked.
  - *Many samples*: ticks compound as above; the settled line sits at the profile's committed value (computed elsewhere — a technical-requirements concern), drawn last, on top.
- **As an input** (tone-match) — same well, same axis, same stroke weights. Instead of a static settled line, a **cursor** in the connected device's line color tracks the user's drag along the 0–600 axis. There is no separate handle or thumb graphic: the line you'll end up looking at forever *is* the thing you're dragging now.

**Device shelf**

One row per known device — name, latency (tabular numerals), provenance, and a compact caliper strip (`DT.Calibration.shelfStripHeightPt`, 20dp — a thumbnail of the detail pane's scale, identical stroke weights, same 0–600 range). Tapping a row opens Device detail.

```
 Living room speaker                              204 ms
 MEASURED · measured 2 days ago
 ┈┈┈┈┈┈┈┈┈┈┈┆┆┃┆┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈   ← brass, solid: connected now

 AirPods Pro                                       182 ms
 ESTIMATED · not measured yet
 ┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈╌╌╌╌╌╌╌╌╌╌┈┈┈┈┈┈┈┈┈┈┈┈┈   ← ink2, dashed, no ticks: never measured

 Kitchen speaker                                    96 ms
 BY EAR · set by ear, 6 days ago
 ┈┈┈┈┈┈┈┆┈┆┈┆┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈   ← ink2, solid, ticks from repeated tone-match
```

Row anatomy, top to bottom: name in `label`/`ink`; value right-aligned on the same baseline, `label`/`ink`, tabular; provenance line — `engraved`/`ink3` word plus a `fine`/`ink3` qualifier ("measured {relative time}" / "not measured yet" / "set by ear, {relative time}"); the strip, full row width, beneath.

**Empty state** (no known devices yet) — an invitation, not a shrug:
- Body: *"No devices calibrated yet. Play something through a speaker or headphones and JoinTheParty will get to know it."*
- Primary: **"Calibrate phone speaker"** — the one device that's always available, so the invitation has somewhere to go immediately.

**Device detail**

```
                       204 ms
                       ▔▔▔▔▔▔

 0                                                     600
 ┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┆┆┆┃┆┆┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈

 MEASURED · measured 2 days ago

           [ Calibrate again ]
```

Value in `heroMs` + `heroUnit` (§3), always `ink` — never `brass`, even for the connected device: brass is spent once, on the caliper line, not doubled onto the number (a deliberate call to keep "exactly one warm element" unambiguous rather than having two things both plausibly claim it). Full caliper scale beneath at `DT.Calibration.detailScaleHeightPt` (72dp) in a `recess` well, the same carved-in language as the meter (§6.1). Provenance line as on the shelf. One secondary pill, **"Calibrate again"** — secondary, not primary: arriving here isn't itself a call to redo anything.

Two banners can appear above the action in place of the plain provenance line — never both at once, and neither interrupts playback; both live only inside this deliberately-opened pane:

*Drift* (the referee finds the profile no longer matches reality):
- Provenance line becomes: *"MEASURED · timing's drifted, worth a redo"*
- Banner: *"This one's drifted from where we measured it. A quick recalibration will tighten it back up."*
- Primary: **"Recalibrate"** · Quiet: **"Later"**

*Trim promotion* (repeated manual nudges converge on the same offset):
- Banner: *"You've nudged this by about −180 ms, three times running. Make that the calibration?"*
- Primary: **"Use this offset"** · Quiet: **"Keep as is"**
- On accept: the nudge wheel (§6.2) returns to zero; confirmation in `fine`: *"Folded into the calibration — the wheel's back at zero."* The accepted offset is stored as a By ear tick, same as any tone-match result — the caliper doesn't care how a tick was produced, only whether it agrees with its neighbors.

**Guided calibration**

Extends the existing four-state sheet (Idle / Running / Success / Failed), unchanged in shape: eyebrow `CALIBRATE`, route name as title, one state's copy visible at a time, no progress bar (`CalibrationSheet.kt`). Two route classes now exist.

*Measured — acoustic chirp* (phone speaker, Bluetooth speakers). Unchanged from the shipped copy:

| State | Copy | Action |
|---|---|---|
| Idle | "Plays a short tone and measures how long this route takes to make it audible." | Primary **"Start calibration"** |
| Running | "Listening for the chirp…" | Secondary **"Cancel"** |
| Success | Title (`brass`): "Latency measured: {ms} ms" · fine: "Saved for this route — sync will aim ahead by it automatically." | Primary **"Done"** |
| Failed | "Couldn't hear the chirp — turn the volume up and try again." | Primary **"Try again"** · Quiet **"Try by ear instead"** |

The Quiet exit on Failed is new: By ear is a fallback on *every* route, not just headphones.

*By ear — tone-match* (the only headphone flow; also reachable from any acoustic Failed state). Interaction is **adjust-until-aligned**, not tap-along-to-a-beat: tapping measures the user's own reaction time stacked on top of the audio latency, which is a systematic bias in one direction; judging perceptual alignment isn't.

| State | Copy | Action |
|---|---|---|
| Idle | "Headphones can't be heard by the phone's mic — so you're the instrument here. We'll play a tone on a loop; drag the mark on the scale until it lands with what you hear." | Primary **"Start"** |
| Running | Interactive caliper (below), persistent line: "Drag until they land together." | Primary **"That's it"** (enabled after the first drag) · Quiet **"Cancel"** |
| Success | Title (`brass`): "By-ear offset set: {ms} ms" · fine: "Good to about ±30 ms — enough to keep things tight. Nudge it further anytime from the trim wheel." | Primary **"Done"** |
| Failed | *(none — every completed attempt produces a usable value; a bad result gets fixed via "Calibrate again," not a retry state)* | — |

Idle copy is deliberately not apologetic — "so you're the instrument here" reframes the mic's limitation as the user's role, the same concierge move already established for the Premium gate (§6.4). Success copy states the ±30 ms figure plainly and never implies laboratory precision.

**The visual beat**

During Running, the interactive caliper plays the tone on a loop, one repetition every `DT.Calibration.toneMatchPeriodMs` (1200 ms). Each repetition, the cursor **strikes**: a hard cut to full `brassBright` for `DT.Calibration.toneMatchStrikeMs` (100 ms), then a hard cut back to resting `brass` — a discrete registration mark, not a fade or a glow. Paired with an `abClick` haptic tick every cycle (§5's existing "rigid click": tone-match is structurally the same gesture as A/B "compare against the room," comparing what you hear against a reference, so the same token is reused rather than inventing a new one). `lockThunk` stays reserved for session sync engagement alone — §5's "signature physical moment" — and isn't spent here. Confirming ("That's it") carries no dedicated haptic: an ordinary pill tap, like every other button in the app.

This is the case §5's motion principle names as welcome: transitional, user-caused, data-driven. Tone-match Running *is* the working state, and the strike is the actual reference signal being judged against — not decoration laid over one.

**What breathes and what stays still, across this section**: the tone-match beat (above) is the only ambient-style motion anywhere in §6.5. The caliper scale itself — on the shelf, in Device detail, and as the tone-match input's resting frame — is a data-dense, steady-state surface; it stays static once its one orchestrated `settle` reveal completes (Motion, below), including while a calibration is running elsewhere in the sheet. At rest it's something to read, not something working.

**Reduced Motion**: the brightness flash is dropped — a strobing strike is a stronger accessibility concern than an eased spring — and replaced by a static engraved-style mark at the cursor's position each cycle; the `abClick` haptic becomes the primary beat reference in its place. The tone itself is unaffected; only the visual strike loses its motion.

**First-contact gate**

Shown once, before playback, when an unknown device becomes the active output. A guided prompt, not a wall — it always offers a way out that doesn't read as failure.

*Acoustic-capable device (phone speaker, Bluetooth speaker):*
- Title: *"New here: {device name}"*
- Body: *"A quick calibration keeps everyone in sync on this speaker. Takes about ten seconds."*
- Primary: **"Calibrate now"** → guided acoustic flow.
- Quiet: **"Not now"** — fine caption beneath: *"We'll use a generic default until you do."*

*Headphone-class device:*
- Title: *"New here: {device name}"*
- Body: *"Headphones can't be heard by the phone's mic — so you're the instrument here. We'll play a tone and you match it by ear. Takes about fifteen seconds."*
- Primary: **"Calibrate by ear"** → guided tone-match flow.
- Quiet: **"Not now"** — same fine caption.

Declining either applies `Estimated`, described honestly rather than as a computed guess: the provenance qualifier reads **"not measured yet"** on both the shelf and the detail pane, never "estimated from…" anything. Nothing is inferred automatically anywhere in this system — Estimated is a flat placeholder, and the copy never implies otherwise.

**Drift prompt**

Never a modal interruption mid-party — §4's negative-space rule extends here: nothing competes with the session screen's four elements. The invitation lives entirely inside the shelf/detail pane, visited deliberately:
- The shelf row's provenance qualifier swaps to *"timing's drifted, worth a redo."*
- Opening that row's detail pane surfaces the drift banner (specified under Device detail, above).

No push, no toast, no badge count — the same "spread is confidence, not a number" restraint that governs how the caliper looks also governs *when* the prompt is allowed to appear.

**Trim promotion**

Copy and behavior are specified under Device detail, above, as a banner rather than a separate screen — it needs the detail pane's context (which device, what history) to make sense on its own. Ask, never adopt silently; accepting zeroes the wheel and stores the folded offset as a By ear tick.

**Motion**

- Opening the sheet, and swapping shelf → detail → guided panes: `heavy` (§5) — the same spring already used for sheet presentation, reused rather than inventing a second vocabulary for calibration specifically.
- The caliper's ticks and settled line, on a pane's first appearance: one orchestrated `settle` (ω = `settleOmega`, 14) pass — they rise/fade in together, once, as the pane lands; never re-triggered while the pane stays open.
- The tone-match strike is the one exception to "everything is a spring": a hard cut by design (above), not a `settle`/`heavy` motion at all.
- Reduced Motion: springs become a `reducedMotionCrossfadeMs` (200 ms) crossfade, per §5, applied to the pane-open reveal; the strike's reduced-motion behavior is specified above (flash removed outright, not crossfaded — a flash has no meaningful crossfade).

**Tokens (`DT.Calibration`)**

| Token | Value | Role |
|---|---|---|
| `scaleRangeMs` | 600 | Caliper axis, 0–600 ms, linear. Shared by every read-out *and* by the tone-match input. |
| `tickStrokeWidthPt` | 1 | Individual measurement tick. |
| `tickAlpha` | 0.35 | Per-tick opacity; compounds under ordinary blending where samples agree. |
| `settledLineStrokeWidthPt` | 2 | Settled value / drag cursor stroke — matches the meter's fused-line weight (§6.1). |
| `maxRetainedSamples` | 12 | Ring buffer of ticks kept per device, any provenance. |
| `shelfStripHeightPt` | 20 | Compact caliper on the device shelf. |
| `detailScaleHeightPt` | 72 | Full caliper well on Device detail. |
| `toneMatchPeriodMs` | 1200 | Tone/strike repetition interval during By ear calibration. |
| `toneMatchStrikeMs` | 100 | Duration of the cursor's `brassBright` flash per repetition. |
| `byEarAccuracyMs` | 30 | The ± figure quoted in By ear success copy — single source for that claim. |
| `trimPromotionSampleCount` | 3 | Repeats at ~the same offset before the promotion banner offers to adopt it. |
| `trimPromotionToleranceMs` | 15 | "About the same offset" tolerance band for the count above. |
| `driftMinSamples` | 3 | Referee measurements required to confirm drift, not just noise. |
| `driftThresholdMs` | 40 | Deviation from the settled value that qualifies as drift. |

---

## 7. Token delivery

One `DesignTokens` module per shell (Swift enum / Kotlin object), generated from a single `tokens.json` in-repo so hexes, type ramps, spring constants, and haptic patterns stay identical across platforms. Widgets never hardcode a value — the one house rule the old repos got right, kept.
