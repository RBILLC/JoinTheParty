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
| Breathing/pulsing animated backgrounds | Ambient motion = arcade energy | Static background; motion exists only where the user's hand or the sync state causes it |
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

Everything moves like it has weight and a damper. **No looping ambient animation anywhere** (the anti-breathing rule).

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

## 7. Token delivery

One `DesignTokens` module per shell (Swift enum / Kotlin object), generated from a single `tokens.json` in-repo so hexes, type ramps, spring constants, and haptic patterns stay identical across platforms. Widgets never hardcode a value — the one house rule the old repos got right, kept.
