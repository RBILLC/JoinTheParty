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

### When a phone drops off adb (expect this)

Field test 7 lost the Pixel 10 Pro **three times**; its wireless debugging
switches itself off, and every re-enable assigns a **new port**. Symptoms:
`device offline`, or `cannot connect … actively refused it (10061)` against
both the old port and the one mDNS still advertises (mDNS caches a stale port —
do not trust it).

There is no adb-side fix: wireless debugging must be toggled back on **on the
phone**, and the new `IP:port` read off its settings screen. `adb kill-server`
does not help. Budget for this and ask the human early rather than burning the
run.

**Losing adb does not end a session.** With INT-06's foreground service the app
keeps listening, playing, and correcting with no debug connection at all — the
microphone still grades it. Treat a dropout as lost *instrumentation*, not a
lost run.

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
ffmpeg -hide_banner -loglevel error -f dshow -audio_buffer_size 100 \
  -i "audio=Headset Microphone (Beosound A1 2nd Gen)" \
  -ac 1 -ar 48000 -f s16le - \
  | build/core/lag_analyzer.exe --stream --rate 48000 --max-lag-ms 2500 \
  > "$S/live_lag.csv"
```

**The DLL trap.** `lag_analyzer.exe` needs llvm-mingw's `libunwind.dll` and
`libc++.dll`. Putting the toolchain on `PATH` is *not* enough for a piped
background job, which does not inherit an interactive `PATH` edit — it fails
with `error while loading shared libraries: libunwind.dll`, and because the
failure is on the far side of a pipe, ffmpeg reports a confusing muxer error
instead. Both DLLs now sit permanently next to the binary in `build/core/`;
if you rebuild into a clean tree, copy them again.

Omit `-t 600` unless you want the capture to self-terminate — an open-ended
capture lets one pipeline serve a whole session, and `wc -l` on the CSV gives
a cheap timeline marker (record the line number when you change something).

Output columns: `t_s,lag_ms,peak_ratio,confident,rms_db`.

Reading it:
- `lag_ms` is the acoustic offset between the two phones — the number that
  matters. This is what the listener hears.
- `peak_ratio > 4` sets `confident`. Ratios of 10–30 are typical with both
  phones audible; ignore anything not confident.
- **Measure the noise floor every session — it moves.** It is room reverb, so
  it depends on where the phones and mic sit: 85 ms in field test 4-5, but
  41–51 ms in field test 7. Record it with nothing playing before you join,
  and judge results against *that* number, not a remembered one.
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
5. **Backgrounded** — screen off for 10+ minutes against a long source, then
   judge by microphone alone (see the INT-06 section below).

## Two numbers that must agree

Take **both** readings at every checkpoint and write them down together:

| | where |
|---|---|
| engine belief | `sync err=…ms` in the `JTP` log |
| acoustic truth | `lag_ms` in the analyzer CSV, both sources audible |

A **stable gap** between them is not noise — it is a constant the engine cannot
see, and it points at calibration rather than the filter. Field test 7 read
`err=3ms` against a microphone-measured 207 ms, flat to ±2 ms across 51
windows; that constant is the uncalibrated output chain (INT-03), and the
flatness is what proved tracking was healthy underneath.

Also check which output path Spotify picked, because it changes the constant:

```bash
adb -t N shell dumpsys audio | grep "state:started"
#  FLAG_DEEP_BUFFER  -> high-latency path, expect a large constant bias
```

## Testing the foreground service (INT-06)

The service is what lets the phone be pocketed, so these checks need the screen
**off** — which also means the mic is your only instrument.

```bash
# is it actually a mic-type FGS?
adb -t N shell dumpsys activity services com.jointheparty.app | grep isForeground
#   isForeground=true foregroundId=1 types=0x00000080   <- 0x80 = MICROPHONE

# what does the notification say?
adb -t N shell dumpsys notification --noredact | grep "android.text=String"

# screen off, and confirm it
adb -t N shell input keyevent KEYCODE_SLEEP
adb -t N shell dumpsys power | grep -m1 mWakefulness=   # expect Dozing/Asleep
```

**Use a long room source for soak tests.** Field test 7's soak was cut short
because the room track (4:46) simply ended — a YouTube *mix* or playlist keeps
the room alive for the full ten minutes. A soak bounded by track length proves
nothing about the service.

**The Stop action cannot be driven by `am start-service`** — the service is
correctly `exported="false"`, so the intent is refused with "Requires
permission not exported from uid". It must be tapped:

```bash
adb -t N shell cmd statusbar expand-notifications
# expand the notification (tap its chevron), then scroll the shade —
# on a Pixel 10 Pro the Stop action sits below the fold
adb -t N shell input swipe 540 2100 540 1500 300
```

Verify a Stop actually completed, all three ways — the phase reaches `IDLE`,
`dumpsys activity services` prints `(nothing)`, and there are zero
`NotificationRecord` entries for the package.

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

### Decide the calibration state before any standardized test

Every suite starts from SOME stored route profile, and not knowing which
one invalidates comparisons between runs. Pre-flight, read the persisted
profile (recipe above) and then decide deliberately, and write the choice
into the run notes:

- **Testing product behaviour** (convergence, perturbation, multi-song):
  re-run the calibration flow for the active route first, so the run
  measures the engine, not a stale constant.
- **Testing the self-correction path itself** (the CTL-02 referee closing
  a residual): deliberately KEEP the stale profile and say so — a fresh
  calibration would mask exactly the behaviour under test.

What is never acceptable is starting a suite with an unknown profile:
Field Test 9 spent its first test discovering a stale +565 ms trim and an
under-measured output chain the hard way.

### Set both devices' media volumes before any standardized test

Volume is part of the rig, not a nicety. The analyzer's confidence and the
noise-floor reading both depend on the two sources being comparably audible
at the mic (`rms_db` ~−35 with both playing), and the duck actuator's
episodes are computed from the *current* stream volume — a session started
at an odd volume changes what a duck episode measures. Pre-flight: set both
phones' media volume to a known, repeatable level (and write it down in the
run notes) before the first join. `adb -t N shell media volume --show
--stream 3 --set <idx>` does it without touching the screen.

### One phone can appear as TWO adb transports (FT10)

With wireless debugging, `adb devices -l` routinely lists the same phone
twice — once as the `IP:port` transport you connected, once as an
`adb-<serial>...adb-tls-connect` mDNS entry. FT10's first launch nearly
counted the Pixel 10's mDNS ghost as the second phone. Rules: identify
phones by `product:`/`model:`, not by row count; address every command
with an explicit `-s IP:port` (the mDNS serial contains a space and
breaks quoting); and when the wireless-debugging port changes after a
reconnect (it will), re-run `adb connect` to the new port rather than
trusting a stale transport.

### Check the audio ROUTE, not just Bluetooth power (FT10)

A connected Bluetooth audio device silently steals the media stream: the
phone "plays" but the room speaker stays quiet, and every acoustic number is
measured against nothing. Field Test 10's rig check found Phone B with an
A2DP headset `STATE_CONNECTED` and `dumpsys audio` showing the music stream
index live on `bt_a2dp` while `2 (speaker)` sat at 0 — Bluetooth *power*
being on was expected; the active *route* was the problem. Pre-flight, per
phone: `adb -s <serial> shell dumpsys audio | grep -A4 "STREAM_MUSIC"` and
confirm the live index is on the speaker device, not `bt_a2dp`/`ble`;
`adb shell svc bluetooth disable` clears it (re-enable after the run).

### Reset the custom trim before any standardized test

A user-set trim carried over from casual listening silently shifts every
acoustic number in a run. Field Test 9's first test started with a cached
+565 ms trim from earlier manual testing — the mic read "almost in sync"
while the uncalibrated chain was actually ~565 ms further behind, and a
mid-run reset to 0 stepped the lag and split the segment.

Pre-flight rule: **before joining, read the persisted trim (recipe above)
and have the human zero it in the app UI** (do not `pm clear`). If a test
specifically exercises trim behaviour, that test sets its own trim and says
so. If the trim must change mid-run for any reason, record the wall time and
the live CSV line number at the moment of the change and analyse the
sub-segments separately — never average across a setpoint step.

## Two ways to fool yourself with the microphone

**A low lag reading with only ONE source playing is meaningless.** The analyzer
reports the strongest secondary peak in its search range; with a single source
that peak is room reverb, which sits at ~60–90 ms — indistinguishable from
perfect sync. A run where the room's video ended read a beautiful 62 ms while
nothing was synchronised at all. Always confirm both phones are actually
playing (`rms_db` around −35 with two sources; check Phone A's screen) before
believing a good number.

**Keep `--max-lag-ms` at 2500.** Widening it to 4000 to chase large offsets
made the analyzer lock onto harmonics of the music's own periodicity, producing
spurious 3166 ms readings between valid ones. The narrower range is more
trustworthy; if the true lag exceeds it you will see incoherent values rather
than a plausible wrong one, which is the safer failure.

## Keep the screens awake

Phone B's screen locking silently invalidates a run: the Join tap lands on the
keyguard, the app never starts a session, and the log comes back empty while
Spotify keeps auto-advancing in the background. A secured lock screen cannot be
dismissed over adb (`wm dismiss-keyguard` fails), so this needs a human.

```
adb -t 12 shell settings put system screen_off_timeout 1800000
adb -t 10 shell settings put system screen_off_timeout 1800000
```

Always confirm the log is non-empty a few seconds after joining, before
trusting anything downstream of it:

```
adb -t 12 logcat -s JTP > run.log &
# ... join ...
(Get-Item run.log).Length   # must be non-zero
```

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
