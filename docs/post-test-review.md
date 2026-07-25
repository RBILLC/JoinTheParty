# Post-Field-Test Review & Security Cleanup · 2026-07-24

## 1. Field test feedback — what was actually addressed

The feedback placeholder in the cleanup directive was left unfilled, so no *user-reported* items existed to action. What this pass captures instead is everything found during the live debugging session on the Pixel 10 Pro (full narrative: the field-test artifact + `docs/apk-ready.md` lineage):

| Finding (on device) | Fix now committed |
|---|---|
| Stuck on "Matching…" — no retry existed before the engine's first accepted fix | First pass waits 4 s for the capture window; 6 s retry cadence |
| Session froze in AIMING — App Remote connected off the main thread, callbacks never fired | Main-dispatcher connect + 20 s timeout; play/seek post to the main looper |
| `UserNotAuthorizedException` despite web consent — no Activity for App Remote's consent UI | `activityContext` attached by MainActivity (cleared on destroy) |
| Authorization failure mislabeled as "needs Premium" (cost real debugging time; actual cause: Development-Mode allowlist) | Honest logging + `TODO(UI-06c)` copy note — App Remote cannot distinguish grant-missing from Premium-missing |
| First fix always discarded (recognition precedes playback → no local timeline) → session silently stalled in CONVERGING | Shell keeps sampling until a fix is *accepted* (`firstEstimateSeen`), then hands scheduling to the engine |
| Unbounded retry loop — caught by the unit suite (9.2 M virtual-time calls), would bill ACR every 6 s forever | Capped at 20 shell-driven attempts (~2 min), reset per session |
| No sync telemetry for field runs | 1 Hz `sync err/drift/conf` lines + `CORRECTION → seek` logging + on-screen overlay |

**Still pending — the next test:** the convergence measurement run (external speaker, headphones on the phone, 90 s untouched). Everything above exists to make that run diagnosable; the build on the device is functionally identical to this commit.

## 2. Credentials — CONFIRMED secured

- `android/local.properties` (verified git-ignored) now holds **all** keys: `spotify.client.id`, `acr.host`, `acr.key`, `acr.secret`.
- `build.gradle.kts` injects each via `buildConfigField` at compile time; the client-id literal was removed from tracked source (verified: `git grep` finds no credential strings in tracked files). Missing client id produces a loud configure-time warning rather than a silent broken build.
- Note honestly: the ACR access secret transited chat/console during setup; rotating it in the ACR console is cheap if that ever concerns anyone.

## 3. Version control — CONFIRMED

- Git was already initialized (this repo has carried the project since SCAF-01; ~25 commits).
- `local.properties` ignored ✅; the two Spotify AARs (168 KB total) are deliberately **committed** in `android/app/libs/` with `tools/fetch_spotify_sdks.sh` as provenance ✅.
- This state is committed as **"feat: Android MVP Complete - Field Test 1"** — with the caveat that "complete" means *the full loop runs on hardware through playback*; sync convergence is measured in Field Test 2.
