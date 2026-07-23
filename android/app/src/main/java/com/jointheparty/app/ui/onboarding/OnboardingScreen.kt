package com.jointheparty.app.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jointheparty.app.ui.theme.BilletTheme
import com.jointheparty.app.ui.theme.BilletType
import com.jointheparty.app.ui.theme.DT
import com.jointheparty.app.ui.theme.recessedWell

/**
 * UI-06: onboarding (ui-ux-design-system.md §6.4) — three screens, one
 * sentence each (listen → match → play in sync), each illustrated by the
 * phase-horizon motif (§6.1) progressing toward fusion. No feature tours: no
 * headline-plus-caption structure, no per-page iconography beyond the motif,
 * just the one sentence.
 *
 * The motif here is a deliberately *static* scene-setter — it reuses
 * [heatColor][com.jointheparty.app.ui.theme.heatColor]'s vocabulary (cold
 * graphite → bronze → fused brassBright) but draws a fixed frame per page
 * rather than [SyncMeter][com.jointheparty.app.ui.components.SyncMeter]'s
 * live animation loop; onboarding has no sync data to animate yet.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val pageCount = OnboardingPage.entries.size
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val lastPage = pageCount - 1

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DT.Colors.void)
            // Keep Skip (and everything else) clear of the status bar.
            .safeDrawingPadding(),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            OnboardingPageContent(page = OnboardingPage.entries[index])
        }

        if (pagerState.currentPage < lastPage) {
            Text(
                text = "Skip",
                style = BilletType.label,
                color = DT.Colors.ink3,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(DT.Space.gutter)
                    .clickable(onClick = onDone),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = DT.Space.sectionGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (pagerState.currentPage == lastPage) {
                JoinButton(onClick = onDone)
                Spacer(modifier = Modifier.height(DT.Space.sectionGap))
            }
            PageIndicator(pageCount = pageCount, currentPage = pagerState.currentPage)
        }
    }
}

/**
 * Page identity + copy. Sentence case, one job per sentence (§6.4 /
 * "words are design material") — no clause is doing double duty explaining a
 * feature *and* setting a mood.
 */
private enum class OnboardingPage(val sentence: String) {
    LISTEN("Hear a speaker playing music."),
    MATCH("We find the song and the exact beat."),
    PLAY_IN_SYNC("Your Spotify joins in, perfectly in sync."),
}

@Composable
private fun OnboardingPageContent(page: OnboardingPage, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = DT.Space.gutter)
            // Reserves room below for the bottom-aligned indicator dots and
            // (on the last page) the primary pill, so centered content never
            // collides with them.
            .padding(bottom = 148.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OnboardingMotif(page = page)
        Spacer(modifier = Modifier.height(DT.Space.sectionGap))
        Text(
            text = page.sentence,
            style = BilletType.title,
            color = DT.Colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 300.dp),
        )
    }
}

/**
 * The phase-horizon motif, static per page (§6.1's two-lines-become-one
 * image): page 1 shows the reference and local lines far apart in cold
 * `graphite` (searching), page 2 close together in `bronze` (converging),
 * page 3 fused into a single `brassBright` line (locked) — the product's
 * entire promise, told without a word.
 */
@Composable
private fun OnboardingMotif(page: OnboardingPage, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .recessedWell(RoundedCornerShape(DT.Shape.radiusCard)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val inset = 20.dp.toPx()
            when (page) {
                OnboardingPage.LISTEN -> drawHorizonPair(
                    cy = cy,
                    inset = inset,
                    localOffset = 32.dp.toPx(),
                    localColor = DT.Colors.graphite,
                )
                OnboardingPage.MATCH -> drawHorizonPair(
                    cy = cy,
                    inset = inset,
                    localOffset = 8.dp.toPx(),
                    localColor = DT.Colors.bronze,
                )
                OnboardingPage.PLAY_IN_SYNC -> drawLine(
                    color = DT.Colors.brassBright,
                    start = Offset(inset, cy),
                    end = Offset(size.width - inset, cy),
                    strokeWidth = 2.dp.toPx(),
                )
            }
        }
    }
}

/** Reference line (fixed, `ink2`) plus a local line offset by [localOffset]. */
private fun DrawScope.drawHorizonPair(cy: Float, inset: Float, localOffset: Float, localColor: Color) {
    drawLine(
        color = DT.Colors.ink2,
        start = Offset(inset, cy),
        end = Offset(size.width - inset, cy),
        strokeWidth = 1.5.dp.toPx(),
    )
    drawLine(
        color = localColor,
        start = Offset(inset, cy - localOffset),
        end = Offset(size.width - inset, cy - localOffset),
        strokeWidth = 1.5.dp.toPx(),
    )
}

/** Three 4dp dots, no numbers (§6.4): `hairline` inactive, `ink2` active. */
@Composable
private fun PageIndicator(pageCount: Int, currentPage: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(DT.Space.grid)) {
        repeat(pageCount) { index ->
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(if (index == currentPage) DT.Colors.ink2 else DT.Colors.hairline),
            )
        }
    }
}

/** Primary pill (§6.3): `brass` fill, `void` text, [BilletType.label]. */
@Composable
private fun JoinButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(DT.Colors.brass)
            .clickable(onClick = onClick)
            .padding(horizontal = DT.Space.gutter, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "Join the party", style = BilletType.label, color = DT.Colors.void)
    }
}

// ---- Previews ---------------------------------------------------------------

@Preview(name = "Onboarding — 1. Listen", showBackground = true, backgroundColor = 0xFF131110)
@Composable
private fun OnboardingListenPreview() {
    BilletTheme { OnboardingPageContent(page = OnboardingPage.LISTEN) }
}

@Preview(name = "Onboarding — 2. Match", showBackground = true, backgroundColor = 0xFF131110)
@Composable
private fun OnboardingMatchPreview() {
    BilletTheme { OnboardingPageContent(page = OnboardingPage.MATCH) }
}

@Preview(name = "Onboarding — 3. Play in sync", showBackground = true, backgroundColor = 0xFF131110)
@Composable
private fun OnboardingPlayInSyncPreview() {
    BilletTheme { OnboardingPageContent(page = OnboardingPage.PLAY_IN_SYNC) }
}

@Preview(name = "Onboarding — full pager", showBackground = true, backgroundColor = 0xFF131110)
@Composable
private fun OnboardingScreenPreview() {
    BilletTheme { OnboardingScreen(onDone = {}) }
}
