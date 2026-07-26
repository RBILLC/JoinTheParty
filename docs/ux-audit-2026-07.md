# UX Audit — Session Flow · 2026-07-25

Walked every phase as a user would, after three field-test evenings. Ordered by severity. ✅ = fixed in this pass.

## Critical — broken expectations

| # | Gap | Detail | Status |
|---|---|---|---|
| 1 | **No cancel/escape anywhere in a session** | Once Matching (or synced), there is no way back to IDLE. `reset()` has existed in the ViewModel since UI-02 — it was never wired to any UI. Back button just exits the app. | ✅ Quiet "Cancel" on Listening/Matching, "Leave the party" in active sessions |
| 2 | **Matching can zombie forever** | The recognition sampling cap (20 attempts ≈ 2 min, a quota guard) silently stops sampling but leaves the phase in MATCHING — the app looks alive and is permanently dead. | ✅ Cap now escalates to the error state with honest copy ("Couldn't find the song — tap to try again") |
| 3 | **Screen lock / backgrounding kills the session** | Mic capture runs inside the Activity process with no foreground service; Android cuts background mic access, so pocketing the phone (the *default* posture at a beach) silently degrades then loses sync. Also: screen isn't kept awake during a session. | ⬜ Needs a mic foreground service + notification (real ticket — INT-06 proposed below) |
| 4 | **Debug overlay ships to listeners** | Field testers watched raw logs scroll over the Billet UI all evening. | ✅ Tap the overlay to dismiss it for the session (full removal stays gated on end of field phase) |

## Major — missing affordances

| # | Gap | Detail |
|---|---|---|
| 5 | Mic permission denial is silent | Deny → returns to IDLE with zero explanation; second deny needs Settings deep-link. Needs one honest line under the Join button. |
| 6 | Calibrate is offered on Bluetooth routes where it cannot work | The chirp plays into the headphones; the mic can't hear them; every BT calibration ends in an 8 s timeout → "Failed". Either hide on BT routes or explain that the wheel *is* BT calibration. |
| 7 | Wheel gestures are undiscoverable | Double-tap-to-zero and (deferred) long-press numeric entry have no hints. A first-run one-line coach mark near the wheel would cover it. |
| 8 | No "we can hear music" feedback while Matching | Peak levels exist internally (debug overlay shows them); users get a bare "Matching…" with no clue whether the mic hears anything. A subtle level-reactive treatment of the phase word would close the loop. |
| 9 | No session auto-end | Silent room + idle session runs mic + paid recognition passes indefinitely (battery + quota). Auto-stop after ~2 min of no-match/silence with a "stopped listening" state. |
| 10 | Premium gate copy still conflates authorization with Premium | Known (`TODO(UI-06c)`); cost us an evening once already. Needs the two-cause copy. |

## Minor — polish

| # | Gap |
|---|---|
| 11 | No settings surface at all: can't view/clear per-route trim, can't re-run onboarding, no about/licenses (OFL attribution currently only in-repo) |
| 12 | "Connect Spotify" tap gives no in-app confirmation moment on return (label flips, but nothing announces success/failure) |
| 13 | Landscape untested; wheel ergonomics likely poor there |
| 14 | TalkBack pass still pending (semantics exist on meter + wheel, unverified on device) |
| 15 | Track artwork still absent (TODO UI-05b) — the identity block is text-only |

## Proposed ticket: INT-06 — Session foreground service
Mic-type foreground service owning capture + engine lifetime; notification with track + sync state + Stop action; keep-screen-on becomes unnecessary; Activity becomes a pure viewer. This is the single biggest gap between "demo" and "thing you use at an actual party," because pocketing the phone is the normal case.
