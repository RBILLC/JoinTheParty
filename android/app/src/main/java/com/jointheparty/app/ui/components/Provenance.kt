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
 */
fun provenanceQualifier(profile: CalibrationProfile, nowMs: Long): String {
    if (profile.drifted) return "timing's drifted, worth a redo"
    return when (profile.method) {
        CalibrationProfile.Method.MEASURED ->
            "measured ${relativeTimeAgo(nowMs, profile.updatedAtMs)}"
        CalibrationProfile.Method.BY_EAR ->
            "set by ear, ${relativeTimeAgo(nowMs, profile.updatedAtMs)}"
        CalibrationProfile.Method.ESTIMATED -> "not measured yet"
    }
}

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
 */
@Composable
fun ProvenanceLine(profile: CalibrationProfile, nowMs: Long, modifier: Modifier = Modifier) {
    Row(modifier = modifier) {
        Text(provenanceLabel(profile.method), style = BilletType.engraved, color = DT.Colors.ink3)
        Text(
            text = " · " + provenanceQualifier(profile, nowMs),
            style = BilletType.fine,
            color = DT.Colors.ink3,
        )
    }
}
