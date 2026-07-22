# Android Implementation Review — SCAF-03 + NAT-04 · 2026-07-21

**Scope:** Android app scaffold with NDK wiring (SCAF-03) and the JNI ↔ SyncCore bridge (NAT-04), built on the Windows host.
**PM decisions applied:** 25 ms deadband unchanged · learned command latency now persistable across sessions (new ABI getter) · ±30 ms self-hearing window confirmed (CORE-06 scope, hook already in place).

---

## 1. Files created

### Core ABI change (commit `9f9a84f`)

| File | Change |
|---|---|
| `core/include/synccore/synccore.h` | New `sc_get_command_latency_ms()` — reads the config prior refined by online learning, so shells can persist it per device/route and hand it back as `command_latency_prior_ms` at next `sc_create` (no more cold-start reset to 250 ms) |
| `core/src/synccore.cpp` | Worker mirrors the policy's learned value into an atomic after each fix; getter reads it from any thread |
| `core/tests/test_synccore.cpp` | Getter readback + null-arg coverage; suite stays 100 % green (5/5) |

### SCAF-03 — Android scaffold

| File | Purpose |
|---|---|
| `android/settings.gradle.kts` | Repo modes, plugin repos, `:app` |
| `android/build.gradle.kts` | Root plugin aliases |
| `android/gradle/libs.versions.toml` | Every dependency pinned exactly: AGP 8.9.0, Kotlin 2.1.10, Compose BOM 2025.01.00, coroutines 1.10.1 |
| `android/gradle.properties` | AndroidX, JVM args, non-transitive R |
| `android/gradlew(.bat)` + wrapper | Gradle 8.11.1 pinned |
| `android/app/build.gradle.kts` | minSdk 24, target/compile 35, **NDK 28.2.13676358 pinned**, CMake 3.31.4, ABIs arm64-v8a/armeabi-v7a/x86_64, `-DSYNCCORE_BUILD_TESTS=OFF` |
| `android/app/src/main/AndroidManifest.xml` | `RECORD_AUDIO`, `INTERNET`, `<queries>` for `com.spotify.music`, launcher activity |
| `android/app/src/main/java/.../MainActivity.kt` | Bring-up screen on Billet tokens: instantiates the engine, shows command-latency readback |
| `android/app/proguard-rules.pro` | Keeps `SyncCore.onNativeEvent` (called via cached JNI method ID) |
| `android/local.properties` | Machine-local `sdk.dir` (gitignored) |

### NAT-04 — JNI bridge

| File | Purpose |
|---|---|
| `android/app/src/main/cpp/CMakeLists.txt` | `add_subdirectory(../../../../../core)` — the **same** CMake tree desktop tests/CI build; produces `libsynccore_jni.so` |
| `android/app/src/main/cpp/synccore_jni.cpp` | Full ABI surface: create/destroy, capture push, fixes, player states, nudge/route/AEC, seek/local-playback notifies, reference push, calibration, latency readback; event trampoline → JVM |
| `android/app/src/main/java/.../core/SyncCore.kt` | Typed Kotlin API: sealed `Event` hierarchy, `SharedFlow` event stream, conflated `meterFrames`, `AutoCloseable` lifecycle |
| `android/app/src/androidTest/java/.../SyncCoreBridgeTest.kt` | Device test: config rejection, estimate + correction round trip with payload assertions, settle-window rejection, cross-thread capture push |

## 2. JNI memory & threading design

- **Ownership:** one `BridgeHandle` per Kotlin instance — `sc_session_t*`, a **global ref** to the Kotlin object, and the cached `onNativeEvent` method ID. `nativeCreate` allocates it; `nativeDestroy` is the single free path.
- **The destruction-order contract is the whole safety story:** `sc_destroy` *joins the engine worker first* (the ABI guarantees no callback after it returns), and only then is the global ref deleted and the handle freed. The trampoline can never race a dead object.
- **Callback thread attach:** the engine worker attaches via `AttachCurrentThreadAsDaemon` — daemon so an attached native thread can never block JVM shutdown; repeat calls are cheap once attached. Exceptions thrown by the Kotlin handler are cleared in the trampoline (a listener bug must not kill the sync engine).
- **Event marshaling:** one flattened primitive signature `(IDDDIIJ)V` for all six event types — no per-event JNI object construction on the hot path; Kotlin re-types into the sealed `Event` hierarchy and publishes with non-suspending `tryEmit` (`DROP_OLDEST`, capacity 64).
- **Capture push:** `GetPrimitiveArrayCritical` around `sc_push_capture` — safe because the push only memcpys into the lock-free ring (no locks/allocation inside the critical window, released with `JNI_ABORT`). This JNI entry point exists for tests/bring-up; the production path (NAT-02) pushes directly from the Oboe C++ callback, no JNI on the audio thread.
- **`callbackFlow` deviation (spec §NAT-04):** the engine has a single callback slot but two mandated consumers (session store + meter). A per-collector `callbackFlow` would re-register the native callback per collection, so the bridge uses one `MutableSharedFlow` fan-out instead; typed events and non-blocking delivery are unchanged, `meterFrames` stays conflated per the two-stream rule.

## 3. Windows build challenges overcome

1. **SDK CMake too old for the core.** `core/CMakeLists.txt` requires ≥ 3.28; the SDK shipped only 3.22.1. Installed **`cmake;3.31.4`** via `sdkmanager` and pinned it in `app/build.gradle.kts` (`externalNativeBuild.cmake.version`). This is a durable SDK-managed install, unlike the session-scratchpad CMake used for desktop builds.
2. **No system JDK.** No `java` on PATH; Android Studio's JBR (OpenJDK 21.0.10) drives Gradle via `JAVA_HOME`. Documented in `android/README.md`.
3. **No system Gradle.** Bootstrapped from a scratchpad Gradle 8.11.1 distribution, then generated the committed wrapper (`gradle wrapper --gradle-version 8.11.1`) so the repo is self-sufficient (`gradlew`).
4. **NDK pin deviation, documented:** spec said r27; this host has r26.1/r28.2 installed. Pinned **28.2.13676358** — the point of the requirement is *a* pin + reproducibility, and r28 is what build hosts carry. `technical-requirements.md` §4 should be amended r27 → r28.2 at next doc pass.
5. **No device/emulator connected** (`adb devices` empty): the instrumentation suite compiles (`assembleDebugAndroidTest`) but cannot execute here. It is the NAT-04 "receive events on device" acceptance artifact — first connected device runs `gradlew :app:connectedDebugAndroidTest`.

## 4. Build output

First full build on the Windows host (`gradle :app:assembleDebug`, Gradle 8.11.1, JBR 21, NDK 28.2, CMake 3.31.4 — native build ran for all three ABIs):

```
> Task :app:mergeDebugShaders
> Task :app:compileDebugShaders NO-SOURCE
> Task :app:generateDebugAssets UP-TO-DATE
> Task :app:mergeDebugAssets
> Task :app:compressDebugAssets
> Task :app:desugarDebugFileDependencies
> Task :app:checkDebugDuplicateClasses

> Task :app:configureCMakeDebug[arm64-v8a]
[CXX5304] This version only understands SDK XML versions up to 3 but an SDK XML
file of version 4 was encountered. (benign: Studio vs cmdline-tools release skew)

> Task :app:mergeLibDexDebug
> Task :app:buildCMakeDebug[arm64-v8a]
> Task :app:mergeExtDexDebug
> Task :app:configureCMakeDebug[armeabi-v7a]
> Task :app:buildCMakeDebug[armeabi-v7a]
> Task :app:configureCMakeDebug[x86_64]
> Task :app:buildCMakeDebug[x86_64]
> Task :app:mergeDebugJniLibFolders
> Task :app:processDebugManifestForPackage
> Task :app:mergeDebugNativeLibs
> Task :app:validateSigningDebug
> Task :app:writeDebugAppMetadata
> Task :app:writeDebugSigningConfigVersions
> Task :app:stripDebugDebugSymbols
> Task :app:processDebugResources
> Task :app:compileDebugKotlin
> Task :app:compileDebugJavaWithJavac NO-SOURCE
> Task :app:dexBuilderDebug
> Task :app:mergeDebugGlobalSynthetics
> Task :app:processDebugJavaRes
> Task :app:mergeProjectDexDebug
> Task :app:mergeDebugJavaResource
> Task :app:packageDebug
> Task :app:createDebugApkListingFileRedirect
> Task :app:assembleDebug

BUILD SUCCESSFUL in 48s
41 actionable tasks: 41 executed
```

Instrumentation test APK (`:app:assembleDebugAndroidTest`):

```
BUILD SUCCESSFUL in 13s
50 actionable tasks: 33 executed, 17 up-to-date
```

Artifacts: `app-debug.apk` (9.6 MB, includes `libsynccore_jni.so` ×3 ABIs), `app-debug-androidTest.apk` (0.4 MB).

## 5. Device run (2026-07-22)

`connectedDebugAndroidTest` executed on the `Pixel_10_Pro` AVD (API 16 preview image, x86_64): **4/4 tests pass** — config rejection, estimate + correction round trip (+50 ms error, seek target = player + latency − error), settle-window rejection, cross-thread capture push.

First run exposed a race in the test itself (not the bridge): `events` is a hot SharedFlow with no replay, and the test subscribed *after* submitting the fix, so the worker's events fired before the collector attached. Fixed by subscribing with `CoroutineStart.UNDISPATCHED` before submission — which is also the documented consumer contract: subscribe at session start, before feeding the engine.

## 6. Follow-ups
- Amend `technical-requirements.md` §4: NDK pin r27 → **r28.2.13676358** (this doc is the record of the deviation).
- NAT-02 (Oboe capture) should push from the C++ audio callback directly; retire `nativePushCapture` from any RT path at that point.
