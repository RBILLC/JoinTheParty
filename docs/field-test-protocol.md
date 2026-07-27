# Field test protocol — two phones and a microphone

How to run a full-loop sync test on real hardware, end to end, driven entirely
from this machine. Written so a future session can reproduce it without
rediscovering any of it.

## Why this rig exists

The app's own telemetry cannot be trusted to grade the app. Field Test 4 had
the engine reporting −705 ms and calling itself nearly converged while the
room was actually 1168 ms ahead — because the thing it was measuring against
was its own audio. **An independent microphone is the only honest referee.**
Never conclude "it's in sync" from the engine's `sync err` alone.

## Physical setup

- **Phone A** — the "room". Plays a YouTube video (Spotify refuses to play the
  same account on two devices at once, which is why the source is YouTube).
- **Phone B** — runs JoinTheParty against Spotify.
- **Beosound A1 microphone** on the PC, sitting **between the two phones** so it
  hears both. This is the ground truth.

Both phones on Wi-Fi with wireless debugging enabled.

## Connecting the phones

mDNS auto-discovery is already working — `adb devices -l` should list both
without any pairing step. Ports are not needed and change constantly; ignore
them.

```
adb devices -l
# adb-38101FDJH00JXZ-...  Pixel_8        transport_id:10   <- Phone A (room)
# adb-57161FDCH000BX-...  Pixel_10_Pro   transport_id:12   <- Phone B (JTP)
```

**Always address phones by `-t <transport_id>`, never by serial.** The Pixel 10
Pro's serial contains a space (`... (2)._adb-tls-connect._tcp`), which breaks
`adb -s` argument parsing. Transport IDs change between sessions — re-read them
from `adb devices -l` every time.

## The microphone

Device name, exactly: `Headset Microphone (Beosound A1 2nd Gen)`

Two traps, both of which have cost a whole run:

1. **`-audio_buffer_size 100` is required.** Without it ffmpeg produces a
   0-byte WAV.
2. **The device name must reach ffmpeg as ONE argument.** PowerShell's
   `Start-Process -ArgumentList` joins arguments with spaces *without*
   re-quoting, so `audio=Headset Microphone (...)` arrives as `audio=Headset`
   and ffmpeg fails with "Could not find audio only device". Use the Bash tool
   (which quotes properly), or `scratchpad/start_recording.ps1`, which embeds
   the quotes inside the argument string.

### Live analysis (preferred)

`lag_analyzer --stream` reads raw PCM on stdin and prints one line every 2 s,
so the room can be watched **while** the test runs rather than after:

```bash
export PATH="$S/toolchain/llvm-mingw-.../bin:$PATH"   # lag_analyzer needs its DLLs
ffmpeg -hide_banner -loglevel error -f dshow -audio_buffer_size 100 \
  -i audio="Headset Microphone (Beosound A1 2nd Gen)" \
  -t 600 -ac 1 -ar 44100 -f s16le - 2>/dev/null \
  | build/core/lag_analyzer.exe --stream --rate 44100 --channels 1 --min-lag-ms 60 \
  > "$S/live_lag.csv"
```

Output columns: `t_s,lag_ms,peak_ratio,confident,rms_db`.

Reading it:
- `lag_ms` is the acoustic offset between the two phones — the number that
  matters. This is what the listener hears.
- `peak_ratio > 4` sets `confident`. Ratios of 10–30 are typical with both
  phones audible; ignore anything not confident.
- **Noise floor is ~85 ms** — that is room reverb, measured with nothing
  playing. Do not read a lag below ~110 ms as a real offset.
- `rms_db` around −37 means both sources are audible. Near −45 with nothing
  playing means the room is silent; check Phone A.

## Driving the test

Phone A — open the video and rewind to the start. Tapping the seek bar does
**not** seek; you must drag the scrubber:

```
adb -t 10 shell am force-stop com.google.android.youtube
adb -t 10 shell am start -a android.intent.action.VIEW -d "https://www.youtube.com/results?search_query=billy+joel+my+life+official+audio"
sleep 7; adb -t 10 shell input tap 540 570      # first search result
sleep 7; adb -t 10 shell input tap 360 300      # reveal transport controls
adb -t 10 shell input swipe 820 740 15 740 500  # drag scrubber to 0:00
```

Do not guess YouTube video IDs — a wrong ID silently lands on "This video is
unavailable". Go through search and screenshot to confirm.

Phone B — install, launch, tap Join (the Join button is at ~`541 1131`):

```
adb -t 12 install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -t 12 logcat -c
adb -t 12 logcat -s JTP > "$S/run_jtp.log"   &
adb -t 12 shell am force-stop com.jointheparty.app
adb -t 12 shell am start -n com.jointheparty.app/.MainActivity
sleep 3; adb -t 12 shell input tap 541 1131
```

Screenshot to verify UI state rather than assuming: `adb -t N shell screencap
-p /sdcard/s.png` then `adb -t N pull /sdcard/s.png`.

## Reading the engine trace

Key lines in the `JTP` logcat tag:

| line | meaning |
|---|---|
| `fixdbg: offset=… zEnd=… capAge=… (ps=…@…ms)` | the raw recognizer result. `zEnd` is the raw error observation; `ps=X@-Yms` is the last Spotify player state and how stale it is |
| `sync err=…ms drift=…ppm conf=…` | the filter's belief, 1 Hz |
| `CORRECTION → seek …ms (jump …ms) e=… conf=…` | a seek, plus the engine state it was computed from |
| `fix rejected: SELF_HEARING` | the self-match guard fired |

### The invariant that finds measurement bugs

The room plays continuously, so **the recognizer's reported offset must advance
1:1 with the wall clock.** `scratchpad/analyze_fixes.ps1` tests exactly this
across a whole log and flags every interval that slips. This is what proved
the self-match bug: 22 of 55 intervals were short by 1.2–1.8 s, periodically,
and `zEnd` alternated between two distinct populations.

Run it first on any suspicious log — it is far faster than reading the trace.

## What to test

1. **Convergence** — join, then 90 s undisturbed. Mic lag should settle near
   the noise floor and corrections should become rare.
2. **Perturbation** — double-tap the right side of Phone A's video for +10 s
   jumps. The app should re-aim without restarting the track.
3. **Song change** — let the video end, or open a second video. Watch for a
   `play(uri)` restart from 0:00 (a known unguarded path).
4. **Multi-song** — the real bar. Sync must survive two or three consecutive
   songs with no user intervention.

## Reading persisted state off the phone

**Do this before blaming the sync engine.** The app restores a per-route
engine setpoint every session, and a bad one makes a perfectly healthy engine
hold a large constant offset — the engine drives its own error to ~0 while the
microphone measures seconds of lag. Field Test 4 spent two runs chasing a
"filter divergence" that was a stored −2007 ms.

The tell: `e=` in the CORRECTION log line differs from the fix's `zEnd=` by a
constant. That constant is `setpoint + output latency`, not a filter bug.

```powershell
$b64 = adb -t 12 shell "run-as com.jointheparty.app sh -c 'base64 files/datastore/nudge_store.preferences_pb'"
$bytes = [Convert]::FromBase64String(($b64 -join '').Trim())
-join ($bytes | ForEach-Object { if ($_ -ge 32 -and $_ -lt 127) { [char]$_ } else { '.' } })
```

That prints the keys (`trim:`, `setpoint2:`, `outlatency:` per route). To read
a value, find the key's offset and dump the following bytes: the value is a
protobuf varint after a `12 <len> 18` prefix, and negative numbers appear as a
long run of `ff`. `18 a9 f0 ff ff ff ff ff ff ff 01` decodes to −2007.

`adb shell pm clear com.jointheparty.app` wipes this, but it also destroys the
Spotify auth tokens and forces a re-authorisation through the UI — prefer
reading the value and fixing the code.

## Known traps

- **`lag_analyzer.exe` needs the llvm-mingw `bin` on `PATH`** or it fails with
  `libunwind.dll: cannot open shared object file`.
- **Rebuilding while the analyzer is running fails to link** ("Permission
  denied" writing `lag_analyzer.exe`). Stop it first, or build only the target
  you need.
- **Confirm the native code actually shipped.** A Gradle build that finishes in
  3 s may still be correct, but check that the APK's timestamp is newer than
  the edited `.cpp` before trusting a native fix on-device.
- **Do not pipe a long-running process through `Select-Object -First N`** in
  PowerShell — it kills the process.
- **Spotify Development Mode** allowlists 25 users; Phone B's Spotify account
  must be added under User Management or App Remote fails to authorize.
