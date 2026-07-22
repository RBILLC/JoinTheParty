# Android Spotify Integration Review — AUTH-02 + AUTH-05 + NAT-08 · 2026-07-22

**Scope:** OAuth PKCE flow with encrypted token storage, session preconditions, and the App Remote controller compiled against signature-faithful stubs.
**Status:** `:app:testDebugUnitTest :app:assembleDebug` green (PKCE known-answer, controller, and state-machine suites all passing).

---

## 1. AUTH-02 — OAuth 2.0 Authorization Code + PKCE (`spotify/auth/`)

| Piece | Notes |
|---|---|
| `Pkce.kt` | Pure-JVM verifier/challenge functions: 64 CSPRNG bytes → 86-char base64url verifier (inside RFC 7636's 43–128 window); S256 challenge. Unit-tested against the **RFC 7636 Appendix B known-answer vector**. |
| `TokenStore.kt` | `TokenStore` interface + `EncryptedTokenStore`: Keystore `MasterKey` (AES256-GCM) over `EncryptedSharedPreferences` (AES256-SIV keys / AES256-GCM values), all I/O on `Dispatchers.IO`. `SpotifyTokens.toString()` redacts — tokens can't leak through logs by accident. |
| `SpotifyAuthManager.kt` | `beginAuth()` → Custom Tab at `accounts.spotify.com/authorize` with exactly the three scopes from tech-req §3.1; `handleCallback(uri)` exchanges the code (`HttpURLConnection`, 10 s timeouts, `org.json`); `validAccessToken()` refreshes proactively when < 5 min remain. |
| `AuthCallbackActivity.kt` | No-UI `singleTask` deep-link target for `jointheparty://callback` (manifest intent-filter added); forwards the redirect URI into a `StateFlow` and finishes — zero business logic in the Activity. |

Two details worth review attention:

- **Process death between launch and callback is routine** (Custom Tab takes foreground; the OS may kill us). The verifier is therefore persisted to encrypted prefs with a synchronous `commit()` *before* the tab launches, read back in `handleCallback`, and cleared in a `finally` on every outcome.
- **Refresh-token rotation, both behaviors:** Spotify's PKCE refresh sometimes returns a new `refresh_token` and sometimes omits it. The refresh path overwrites when present, keeps the old one when absent, `clear()`s (forcing re-auth) on 400/401, and serves the stale-but-unexpired access token on pure network failure.

Pending: real client id injection (`TODO(AUTH-02b)`) — currently a constructor parameter.

## 2. AUTH-05 — Session preconditions

`spotify/SpotifyAppDetector.kt` checks `com.spotify.music` via `PackageManager` (visible thanks to the `<queries>` entry from SCAF-03). Wiring: `MainActivity.joinTapped()` runs the detector **before** starting a session; missing → `viewModel.onSpotifyMissing()` → `NEEDS_SPOTIFY`. The gate screen's tap proceeds anyway into LISTENING — the §6.4 "keep identifying songs" recognition-only degradation, now a legal allowlist transition (`NEEDS_SPOTIFY/NEEDS_PREMIUM → LISTENING`), so the gate is never a dead-end. UI-06 replaces the placeholder copy with the real concierge treatment.

## 3. NAT-08 — App Remote controller against stubs

### The stubbing strategy

The real App Remote AAR isn't vendored yet (tech-req §4: App Remote + Auth AARs pinned together; external dependency). Rather than mock at our own seam and rewrite later, the stubs live under the **real Spotify package names** (`com.spotify.android.appremote.api.*`, `com.spotify.protocol.*`) with 0.8.x-faithful signatures — `ConnectionParams.Builder`, `Connector.ConnectionListener`, `SpotifyAppRemote.connect/disconnect`, `PlayerApi` (play/pause/resume/seekTo/subscribeToPlayerState), `CallResult`/`Subscription` + callback interfaces, `PlayerState`/`Track`/`Artist` types, and the error taxonomy (`CouldNotFindSpotifyApp`, `NotLoggedInException`, `UserNotAuthorizedException`).

**Swap procedure when the AAR lands:** delete everything under `android/app/src/main/java/com/spotify/` and add the dependency — zero call-site changes. Each stub file carries a header saying exactly that. Runtime behavior is honest-but-inert: stub `connect` always fails with `CouldNotFindSpotifyApp` (there is genuinely no Spotify to bind), and stub callbacks never fire — nothing pretends to work.

### The controller (`spotify/SpotifyController.kt` + `AppRemoteSpotifyController.kt`)

- `connect()` suspends over `SpotifyAppRemote.connect`; error mapping: `CouldNotFindSpotifyApp` → `SpotifyMissing`, `NotLoggedIn`/`UserNotAuthorized` (the Premium-rejection surface, §3.1) → `AuthFailed`, else `Failed(cause)`.
- Player-state events → `playerStates` SharedFlow **and** `engine.submitPlayerState(...)` — the estimator's local-timeline feed.
- **`seekTo(ms)` captures `System.nanoTime()` before issuing, then echoes `engine.notifySeekIssued(ms, t)`** — the §1.2 contract that (a) opens the settle window suppressing recognition fixes, (b) shifts the Kalman error state by the commanded jump, and (c) feeds command-latency learning. When disconnected it returns `false` *without* echoing — no phantom settle windows (unit-tested).
- `play(uri)` arms the self-hearing guard via `engine.notifyLocalPlayback(0)`.

## 4. Build output

```
> Task :app:testDebugUnitTest
> Task :app:assembleDebug

BUILD SUCCESSFUL in 31s
47 actionable tasks: 47 executed
```

(Post-glue re-run with the AUTH-05 wiring + allowlist change: `BUILD SUCCESSFUL in 14s`, all unit suites passing.)

## 5. Follow-ups

- Vendor the real App Remote + Auth AARs (delete `com/spotify/` stubs), inject the real client id, and run the connect flow against a device with Spotify installed.
- UI-06: replace both gate placeholders with the concierge screens ("See Premium plans" / "Get Spotify" / "Keep identifying songs").
- The remaining Android critical path to INT-02: NAT-06 (ShazamKit AAR — still gated on RES-01) + AUTH-03 (ISRC→URI backend) are now the only unimplemented links in the chain.
