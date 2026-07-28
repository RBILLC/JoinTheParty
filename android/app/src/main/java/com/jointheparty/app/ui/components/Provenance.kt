package com.jointheparty.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jointheparty.app.data.CalibrationProfile
import com.jointheparty.app.ui.theme.BilletType
import com.jointheparty.app.ui.theme.DT

/**
 * CAL-08 (ui-ux-design-system.md §6.5 "Provenance"): the engraved-word +
 * fine-qualifier pair shared verbatim by the device shelf's rows and Device
 * detail's provenance line — "provenance is carried by the engraved word
 * itself plus the caliper's own tick-count and stroke-style vocabulary...
 * No color is spent distinguishing them."
 *
 * Split into plain functions ([provenanceLabel]/[provenanceQualifier]/
 * [relativeTimeAgo]) plus a thin composable wrapper, so the copy itself —
 * what §6.5's acceptance criteria calls a "string diff" audit — is testable
 * on the JVM without Compose.
 */

/** The engraved word: "MEASURED" / "BY EAR" / "ESTIMATED". */
fun provenanceLabel(method: CalibrationProfile.Method): String = when (method) {
    CalibrationProfile.Method.MEASURED -> "MEASURED"
    CalibrationProfile.Method.BY_EAR -> "BY EAR"
    CalibrationProfile.Method.ESTIMATED -> "ESTIMATED"
}

/**
 * The fine qualifier following the engraved word, verbatim per §6.5's copy
 * deck: "measured {relative time}" / "not measured yet" / "set by ear,
 * {relative time}" — plus the Drift prompt's swap ("timing's drifted,
 * worth a redo"), which §6.5 specifies replaces the qualifier on BOTH the
 * shelf row and the detail pane, regardless of [CalibrationProfile.method].
 *
 * CFX-03 (tech-req §2.6 "Connected-state encoding"): when [connected] is
 * true, a `· Connected` suffix is appended — the text half of the caliper's
 * settled-line connection tell (ui-ux §6.5's "Settled line" bullet: "wherever
 * a brass line appears, the row/pane also carries a plain-text 'Connected'
 * qualifier... color is a reinforcing signal, not the only one"). Applied
 * unconditionally alongside the drift swap too — the drift banner replaces
 * *what happened*, not *whether this is the connected device right now*,
 * and §6.5's rule has no drift-state exception.
 */
fun provenanceQualifier(profile: CalibrationProfile, nowMs: Long, connected: Boolean = false): String {
    val base = if (profile.drifted) {
        "timing's drifted, worth a redo"
    } else {
        when (profile.method) {
            CalibrationProfile.Method.MEASURED ->
                "measured ${relativeTimeAgo(nowMs, profile.updatedAtMs)}"
            CalibrationProfile.Method.BY_EAR ->
                "set by ear, ${relativeTimeAgo(nowMs, profile.updatedAtMs)}"
            CalibrationProfile.Method.ESTIMATED -> "not measured yet"
        }
    }
    return if (connected) "$base $CONNECTED_QUALIFIER_SUFFIX" else base
}

/** ui-ux §6.5 verbatim: `MEASURED · measured 2 days ago · Connected` — the `·` separator plus the word, joined onto [provenanceQualifier]'s base text. `internal const` so ProvenanceTest's copy audit diffs against the real string. */
internal const val CONNECTED_QUALIFIER_SUFFIX = "· Connected"

/** "just now" / "N minute(s) ago" / "N hour(s) ago" / "N day(s) ago" — the wireframe's "2 days ago" shape. */
fun relativeTimeAgo(nowMs: Long, thenMs: Long): String {
    val deltaMs = (nowMs - thenMs).coerceAtLeast(0L)
    val minutes = deltaMs / 60_000L
    val hours = deltaMs / 3_600_000L
    val days = deltaMs / 86_400_000L
    return when {
        minutes < 1L -> "just now"
        minutes < 60L -> "$minutes minute${if (minutes == 1L) "" else "s"} ago"
        hours < 24L -> "$hours hour${if (hours == 1L) "" else "s"} ago"
        else -> "$days day${if (days == 1L) "" else "s"} ago"
    }
}

/**
 * Row anatomy shared by the shelf and detail (ui-ux §6.5): "the engraved/
 * ink3 word plus a fine/ink3 qualifier." No color distinguishes the three
 * classes — only the word and the qualifier text do.
 *
 * [connected] threads through to [provenanceQualifier]'s `· Connected`
 * suffix (CFX-03). It stays in the SAME `fine`/`ink3` register as the rest
 * of the qualifier — deliberately not a badge (ui-ux §0 retires badges
 * outright): quiet text, not a chip.
 */
@Composable
fun ProvenanceLine(profile: CalibrationProfile, nowMs: Long, modifier: Modifier = Modifier, connected: Boolean = false) {
    Row(modifier = modifier) {
        Text(provenanceLabel(profile.method), style = BilletType.engraved, color = DT.Colors.ink3)
        Text(
            text = " · " + provenanceQualifier(profile, nowMs, connected),
            style = BilletType.fine,
            color = DT.Colors.ink3,
        )
    }
}
