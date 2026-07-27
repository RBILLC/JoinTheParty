package com.jointheparty.app.ui.theme

import android.content.Context
import android.provider.Settings

/**
 * §5 Reduced Motion detection, shared by every composable that branches
 * between free-running/spring motion and a flat [DT.Motion
 * .reducedMotionCrossfadeMs] crossfade — originally NudgeWheel's fling
 * inertia toggle, now also CAL-06's phase-word opacity. There is no
 * dedicated Android "reduce motion" flag; the platform convention (shared
 * with Settings > Accessibility > Remove animations) is to treat the
 * animator duration scale of 0 as the signal.
 */
fun isReducedMotionEnabled(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
