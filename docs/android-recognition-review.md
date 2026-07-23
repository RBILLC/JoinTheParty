# Android Recognition & Concierge Review — NAT-06 + AUTH-03/04 Clients + UI-06 · 2026-07-22

**Scope:** the last links of the Android critical path before INT-02 — recognition (stubbed ShazamKit), backend client seams, onboarding, and the real concierge gates.
**PM decision applied:** RES-01 passed — ShazamKit for Android is a go; the Apple AAR follows the same stub-and-swap strategy as Spotify's.
**Verified on emulator (fresh install):** onboarding (3 pages, motif progression) → brass CTA → IDLE → Join tap → detector gate ("Spotify isn't installed", Get Spotify + Keep identifying songs) → recognition-only path → capture live (OS mic indicator) → **"Matching…"** with the recognition bootstrap pass running.

---

## 1. NAT-06 — ShazamKit stubbing & event mapping

**Stubs** under the real `com.shazam.shazamkit` package (delete-on-vendor, zero call-site changes — same contract as the `com.spotify.*` stubs): `ShazamKit` (catalog/session factories), `DeveloperTokenProvider`/`DeveloperToken`, `Catalog`, `AudioSampleRateInHz`, `ShazamKitResult`, `StreamingSession` (`matchStream` + `recognitionResults(): Flow<MatchResult>`), `MatchResult` (Match/NoMatch/Error), `MatchedMediaItem` (title/artist/isrc/matchOffsetInSeconds/**predictedCurrentMatchOffsetInSeconds**/frequencySkew). The stub session's result flow uses `awaitCancellation()` — never emits, never completes — so callers exercise the genuine timeout path rather than crashing on an empty flow.

**Mapping to the engine** (`recognition/ShazamKitProvider.kt` → `SyncEngine.submitRecognitionFix`):
- `predictedCurrentMatchOffsetInSeconds × 1000` → `matchOffsetMs` (the now-extrapolated timestamp, arch §3 — not raw `matchOffset`)
- `frequencySkew` → the estimator's direct drift observation (0.0 when absent)
- capture-time `monoNs` recorded at pass start → `captureMonoNs`
- confidence fixed at 0.9 for a Match (ShazamKit exposes no granular confidence — documented)
- ISRC rides along for the resolver; one `StreamingSession` reused across passes (quota discipline, tech-req §3.2)

**Cadence — the no-free-running rule:** exactly two triggers, both guarded by an `AtomicBoolean` so passes never overlap. (1) A one-time bootstrap when `startListening()` lands (the engine cannot emit `SC_EVT_REQUEST_FIX` before its first fix): LISTENING → MATCHING → first pass. (2) After that, **only** `Event.RequestFix` triggers passes — the engine owns the schedule (8–30 s adaptive). While MATCHING, a successful fix also resolves ISRC → `onTrackResolved` → AIMING; resolution failures stay in MATCHING for the next engine-scheduled pass.

## 2. AUTH-03/04 — Backend client seams (`backend/BackendClient.kt`)

`BackendClient` interface with production-shaped contracts: `fetchShazamToken()` (POST `/v1/tokens/shazam`, in-memory cache, refresh under 1 h remaining TTL — tech-req §3.2) and `resolveIsrcToSpotifyUri(isrc)` (GET `/v1/track-map?isrc=`, `Resolved(uri, looseSync)` / `NotFound` / `Failure`). `HttpBackendClient(baseUrl)` carries real `HttpURLConnection` plumbing (Dispatchers.IO, 10 s timeouts, typed error mapping); **`baseUrl == null` — today's state — short-circuits to clearly-marked mock responses** (`mock-shazam-token`, `spotify:track:mock`) so INT-02 wiring is unblocked. Deploying the real backend changes one Factory argument.

## 3. UI-06 — Onboarding & concierge gates

**Onboarding** (`ui/onboarding/OnboardingScreen.kt`): three `HorizontalPager` pages, each a static phase-horizon drawing progressing toward fusion (far-apart graphite → close bronze → single fused `brassBright`) over one sentence — *"Hear a speaker playing music." / "We find the song and the exact beat." / "Your Spotify joins in, perfectly in sync."* Dot indicator, quiet Skip, brass "Join the party" only on the last page. First-run gating via `AppPrefs` DataStore (`app_prefs`); no flash-of-wrong-screen while the flag loads. Post-screenshot fix: `safeDrawingPadding()` keeps Skip clear of the status bar.

**Concierge gates** (in `SessionScreen.kt`, replacing the UI-05 placeholders): Premium gate copy **verbatim** from ui-ux §6.4 with primary "See Premium plans" (→ spotify.com/premium) and secondary "Keep identifying songs" (→ the recognition-only path); needsSpotify mirror ("Spotify isn't installed", "Get Spotify" → Play Store listing). Spotify brand attribution deliberately deferred (`TODO(UI-06b)`) rather than faking logo assets.

**Honest gap, flagged in-code:** the §6.4 "gate shown at most once per session after dismissal" rule is *not yet enforced* — the ViewModel has no dismissal memory, so re-tapping Join can re-raise the gate. `TODO(UI-06 follow-up)` on `ConciergeContent`; small ViewModel change, queued.

## 4. Build output

```
> Task :app:testDebugUnitTest
> Task :app:assembleDebug

BUILD SUCCESSFUL in 22s
47 actionable tasks: 17 executed, 30 up-to-date
```

Unit suites: SessionViewModelTest 9/9 (incl. the new recognition-bootstrap test), BackendClientTest 2/2, controller + PKCE suites green. Final integrated re-run after the insets fix: `BUILD SUCCESSFUL in 16s`.

## 5. Critical-path status

Every INT-02 dependency is now implemented (CORE-03 ✅, NAT-02 ✅, NAT-04 ✅, NAT-06 stub ✅, NAT-08 stub ✅, AUTH-02 ✅, AUTH-04 client ✅, UI-05 ✅). What separates us from a real end-to-end lock: the two vendored AARs (ShazamKit, App Remote) + a Spotify client id + the deployed backend — all external artifacts, no new code paths.
