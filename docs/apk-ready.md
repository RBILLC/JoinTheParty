# APK Ready — Final Integration · 2026-07-22

**Status:** all unit suites + desktop core suites green; sideloadable debug APK built with the real Spotify SDKs, live PCM tee, and your registered Client ID.

---

## Sideload it now

**APK path (absolute):**

```
C:\Users\RBILLC\source\repos\JoinTheParty\android\app\build\outputs\apk\debug\app-debug.apk
```

**Install (device connected with USB debugging enabled):**

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r "C:\Users\RBILLC\source\repos\JoinTheParty\android\app\build\outputs\apk\debug\app-debug.apk"
```

> Reminder for App Remote to actually connect: your Spotify dashboard entry must list the Android package `com.jointheparty.app` **and your debug keystore's SHA-1** (`keytool -list -v -keystore $env:USERPROFILE\.android\debug.keystore -alias androiddebugkey -storepass android`), plus redirect URI `jointheparty://callback`.

## 1. Client ID injection — CONFIRMED

- `buildConfigField SPOTIFY_CLIENT_ID = "e010515b16e34b86b77a2a0798126ede"` (`buildConfig = true` enabled); single source for both consumers.
- `AppRemoteSpotifyController` reads `BuildConfig.SPOTIFY_CLIENT_ID` (placeholder literal deleted).
- `SpotifyAuthManager` is instantiated in `MainActivity` with the same id; **"Connect Spotify"** — a whisper-quiet label under the Join pill on the IDLE screen — launches `beginAuth()` (Custom Tab PKCE), and the redirect completes through `AuthCallbackActivity → PendingCallback → handleCallback` collected in the Activity's lifecycle scope. Tokens land in the encrypted store.

## 2. NAT-06b PCM tee — CONFIRMED

- **C ABI:** `sc_copy_recent_capture(session, out, max_frames, out_end_mono_ns)` — copies the newest ~12 s of **post-AEC** capture history (worker-maintained circular buffer, brief mutex, never touches the RT ring) in chronological order. One deliberate addition to the directive's signature: the end-timestamp out-param, because a match offset references the *sample's end* and without that pairing the fix timestamp would be garbage. Desktop-tested (ordering, count, end-timestamp math, null args).
- **JNI/Kotlin:** `SyncEngine.copyRecentCapture(out) → CaptureWindow(frames, endMonoNs)`.
- **`EnginePcmWindowSource`:** pulls a 10 s window, decimates 48 kHz → 8 kHz (mean-of-6; crude-but-adequate anti-alias for fingerprinting, `TODO(NAT-06c)` proper FIR), converts to PCM16 LE, hands `ACRCloudProvider` the WAV-ready bytes with the end timestamp. Wired into the ViewModel factory — recognition hears the engine's audio the moment ACRCloud keys exist.

## 3. What the APK does today, and the one remaining key

On device right now: onboarding → Join → mic capture (real Oboe), Spotify detector gate, App Remote connect attempt **with your real client id** (against real Spotify), PKCE account link, calibration with an audible chirp, nudge wheel with per-route persistence.

**Recognition stays silent until ACRCloud keys are injected** — one line in `SessionViewModel.Companion.Factory`:

```kotlin
recognition = ACRCloudProvider(
    config = ACRCloudProvider.Config(
        host = "identify-….acrcloud.com",   // from console.acrcloud.com
        accessKey = "…",
        accessSecret = "…",
    ),
    source = EnginePcmWindowSource(engine),
)
```

With those keys in place, the full INT-02 loop is armed end-to-end: hear → match → resolve → play → seek → converge.

## 4. Build output

```
> Task :app:testDebugUnitTest
> Task :app:assembleDebug

BUILD SUCCESSFUL in 27s
```

Desktop core: `100% tests passed, 0 tests failed out of 5` (incl. the new tee test).
