# Real-World Handoff — Vendor Swap, ACRCloud Pivot, Polish · 2026-07-22

**PM decision applied:** ShazamKit → **ACRCloud** for the Android MVP.
**Status:** all unit suites + `assembleDebug` **green against the real Spotify AARs** — the stubs are gone.

---

## 1. Spotify SDK vendor swap — CONFIRMED

- Vendored from the public GitHub release `v0.8.0-appremote_v2.1.0-auth` into `android/app/libs/`: `spotify-app-remote-release-0.8.0.aar` (130 KB) + `spotify-auth-release-2.1.0.aar` (38 KB). `tools/fetch_spotify_sdks.sh` records provenance and is the upgrade path. `gson` added (App Remote's documented transitive requirement).
- **`android/app/src/main/java/com/spotify/` deleted entirely.** The compile-faithful-stub bet paid off almost perfectly — the whole swap needed exactly **two** real-world fixes:
  1. `EventCallback` is *nested* (`Subscription.EventCallback`) in the real SDK, not top-level (one import + one call site).
  2. The auth AAR's manifest is placeholder-parameterized — `manifestPlaceholders["redirectSchemeName"/"redirectHostName"]` now supply `jointheparty`/`callback` in Gradle.
- Build + all unit tests green afterwards; the controller's JVM tests still run (the null-context path short-circuits before touching the real API, exactly as designed).

## 2. ACRCloud pivot — provider ready for keys

- `com.shazam.shazamkit` stubs and `ShazamKitProvider` **deleted**.
- New `recognition/ACRCloudProvider.kt` implements `RecognitionProvider` with the **real** identify protocol fully coded: HMAC-SHA1 signature (version 1), multipart upload, WAV framing, response parsing (`play_offset_ms` paired with the sample-end capture timestamp — the exact pairing SyncCore wants; `score`→confidence; ISRC for the resolver). ACRCloud reports no frequency skew, so drift falls back to the estimator's observation-only path.
- Two things gate it going live: **credentials** (below) and the **mic-PCM tee** (`PcmWindowSource`, `TODO(NAT-06b)`) — recognition audio currently has no path from the C++ capture ring to Kotlin.

## 3. Polish — CONFIRMED

- **INT-03b chirp playback:** `audio/ChirpPlayer.kt` — `AudioTrack` (static mode, 48 kHz PCM16, USAGE_MEDIA) renders a sweep that mirrors `core/src/correlate/correlate.cpp::generate_chirp` **exactly** (800→4000 Hz, 200 ms, 10 ms Hann fades, 0.8 amplitude — a mismatched sweep would simply never correlate). `startCalibration()` arms the detector first, then plays; the measured delta is the route's output-chain latency. Calibration is now functional end-to-end on a real device.
- **Instrument Sans:** variable TTF vendored to `res/font/` (OFL license at `docs/licenses/OFL-InstrumentSans.txt`); `BilletTheme` registers Light/Regular/Medium/SemiBold pinned to `wght` instances. API < 26 renders the default instance (acceptable at minSdk 24).
- **UI-06 once-per-session gate:** proceeding past a concierge gate marks it dismissed for the session; `onSpotifyMissing()`/`onPremiumRequired()` return `false` thereafter (callers proceed recognition-only), cleared on `reset()`.
- **CI:** `core-ci.yml` now covers Ubuntu (ASan/UBSan + TSan), Windows (MSVC), and **macOS** (clang). Note: it has still never executed — the repo has no remote yet.

## 4. YOUR NEXT STEPS — exact key-injection points

**Spotify (needed for App Remote connect + PKCE):**
1. Create an app at developer.spotify.com/dashboard; add redirect URI **`jointheparty://callback`**; enable the Android package (`com.jointheparty.app` + your debug keystore SHA-1: `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`).
2. Put the Client ID in **two places** (both marked `TODO(AUTH-02b)`):
   - `spotify/AppRemoteSpotifyController.kt` — replace `"PENDING_AUTH-02_CLIENT_ID"`.
   - Wherever `SpotifyAuthManager` is constructed (currently nowhere at runtime — first call site arrives with INT-02 wiring) — pass `clientId` there.
   - Recommended cleanup at that moment: move it to a `buildConfigField` instead of two literals.

**ACRCloud (needed for recognition):**
1. Create a free project at console.acrcloud.com (type: Audio & Video Recognition, bucket: ACRCloud Music); note **host**, **access key**, **access secret**.
2. Inject in `SessionViewModel.Companion.Factory`: replace `ACRCloudProvider(config = null)` with `ACRCloudProvider(ACRCloudProvider.Config(host = "…", accessKey = "…", accessSecret = "…"))`.
3. Security note (documented in the provider): keys in the APK are demo-tier; production proxies identify calls through the backend so the secret never ships.

**Then the remaining engineering (mine, not yours):** the `PcmWindowSource` mic tee (NAT-06b) — after which a phone with Spotify Premium can attempt the first real INT-02 lock. Also recommended: create the GitHub remote and push so CI finally runs.

## 5. Build output

```
> Task :app:testDebugUnitTest
> Task :app:assembleDebug

BUILD SUCCESSFUL in 27s
```
