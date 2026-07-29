# Deployment & documentation review · 2026-07-29

## Tailnet deployment — SUCCESS (after one phone-side toggle)

The debug APK carrying all three control-loop features shipped today
(CTL-02 `5f03d08` · CTL-03 `9237e3a` · CTL-01 `7d0cc28`) is installed on
the field phone over the Tailnet.

- Build: `:app:assembleDebug` up to date (the same artifact verified green
  against 129/129 JVM tests and the JNI compile).
- First connection attempt **failed exactly the way the field protocol
  predicts**: `adb connect 100.107.161.95:5555` (and the port-less form,
  which also resolves to :5555) was actively refused — wireless debugging
  was down and needed the human to re-toggle it on the phone. No retry
  loop was attempted, per `docs/field-test-protocol.md`'s guidance.
- After the toggle, the phone advertised dynamic port **41029** (Android's
  wireless-debugging port churns per session — 5555 only exists after a
  USB-side `adb tcpip 5555`, which this rig doesn't use):

  ```
  $ adb connect 100.107.161.95:41029
  connected to 100.107.161.95:41029
  $ adb devices -l
  100.107.161.95:41029   device product:blazer model:Pixel_10_Pro device:blazer transport_id:25
  $ adb -s 100.107.161.95:41029 install -r android/app/build/outputs/apk/debug/app-debug.apk
  Performing Streamed Install
  Success
  ```
- Post-install verification on-device: `com.jointheparty.app` present,
  `versionName=0.1.0`, `lastUpdateTime=2026-07-29 18:58:15`.

**Operational note for the next session:** re-read `adb devices -l` every
time and address the device by the IP:port serial (or `-t <transport_id>`)
— both the port and the transport id churn, per the standing field-rig
facts.

## Documentation updates

- **`docs/REFERENCES.md`** (new, commit `16d6677`): the formal bibliography
  behind the DSP and control-loop design — 9 entries grouped by retrieval
  status (7 read in full; 2 partially retrieved standing in for 4
  unreachable primaries), each with a citation, the honest retrieval trail
  carried forward from the two research docs, and an "Application in this
  codebase" paragraph naming the shipped mechanism, file, spec section,
  and ticket — or stating plainly that nothing has shipped (the planned
  MHT bank from the PDA/Grinberg and Gururani & Lerch entries). A summary
  table cross-references all nine.
- **`README.md`** (same commit): new "Literature & Algorithms" section
  linking `docs/REFERENCES.md` and both research docs.
- **Post-review corrections** (this commit): Wang (2003)'s venue corrected
  to ISMIR 2003 — the research doc carried an "ICMIR" typo the bibliography
  had faithfully copied, fixed in both places — and a brittle line-number
  citation replaced with a stable `PolicyConfig` reference.

## Ready on the phone

The installed build contains every mechanism the pending field passes need:
the five-cycle CTL-02 check (Vienna/Dreams echo should correct within ~60 s
of lock), the CTL-03 forced ~1.2 s room seek (one held cycle, then a single
clean correction; `comb_ratio` now visible in `lag_analyzer --stream`), and
the CTL-01 device pass (audible ~200 ms probe + forced self-match ending in
track-lost → re-listen).
