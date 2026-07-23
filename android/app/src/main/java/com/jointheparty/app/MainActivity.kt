package com.jointheparty.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.jointheparty.app.audio.AudioRouteObserver
import com.jointheparty.app.data.DataStoreAppPrefs
import com.jointheparty.app.spotify.SpotifyAppDetector
import com.jointheparty.app.ui.onboarding.OnboardingScreen
import com.jointheparty.app.ui.session.SessionPhase
import com.jointheparty.app.ui.session.SessionScreen
import com.jointheparty.app.ui.session.SessionViewModel
import com.jointheparty.app.ui.theme.BilletTheme
import com.jointheparty.app.ui.theme.DT
import kotlinx.coroutines.launch

/**
 * UI-05: hosts the session. The Activity owns platform concerns only —
 * mic permission, route observation — and hands projections/callbacks to
 * the stateless [SessionScreen]. All session logic lives in
 * [SessionViewModel]; all engine traffic below that.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: SessionViewModel by viewModels {
        SessionViewModel.Companion.Factory(applicationContext)
    }

    private var routeObserver: AudioRouteObserver? = null

    private val appPrefs by lazy { DataStoreAppPrefs(applicationContext) }

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.startListening()
        // Denied: stay in IDLE — the Join button remains the retry point.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        routeObserver = AudioRouteObserver(this) { route, routeId, routeName ->
            viewModel.onRouteChanged(routeId, routeName, route)
        }.also { it.start() }

        setContent {
            BilletTheme {
                // UI-06 (§6.4): three-screen onboarding gates the very first
                // launch. `null` = not yet known — while unknown we show a
                // plain void background rather than guessing, so neither
                // onboarding nor the session screen ever flashes wrongly.
                var showOnboarding by remember { mutableStateOf<Boolean?>(null) }
                val scope = rememberCoroutineScope()

                LaunchedEffect(Unit) {
                    showOnboarding = !appPrefs.onboardingSeen()
                }

                when (showOnboarding) {
                    null -> Box(modifier = Modifier.fillMaxSize().background(DT.Colors.void))
                    true -> OnboardingScreen(
                        onDone = {
                            showOnboarding = false
                            scope.launch { appPrefs.setOnboardingSeen() }
                        },
                    )
                    false -> {
                        val state by viewModel.syncState.collectAsState()
                        SessionScreen(
                            state = state,
                            meterFrames = viewModel.meterFrames,
                            onJoinTap = ::joinTapped,
                            onTrimChange = { /* optimistic display only; commit below */ },
                            onTrimCommit = viewModel::onNudgeCommitted,
                            onGetSpotify = ::openGetSpotify,
                            onSeePremiumPlans = ::openSeePremiumPlans,
                            onStartCalibration = viewModel::startCalibration,
                            onCancelCalibration = viewModel::cancelCalibration,
                            onDismissCalibration = viewModel::acknowledgeCalibration,
                        )
                    }
                }
            }
        }
    }

    /** §6.4 needsSpotify primary: deep-link to the Play Store listing. */
    private fun openGetSpotify() {
        openUrl("https://play.google.com/store/apps/details?id=com.spotify.music")
    }

    /** §6.4 needsPremium primary: deep-link to Spotify's Premium plans page. */
    private fun openSeePremiumPlans() {
        openUrl("https://www.spotify.com/premium/")
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            // No browser/Play Store to handle it — nothing sensible to do
            // here beyond staying put; the user is already looking at the
            // concierge screen's honest explanation.
        }
    }

    private fun joinTapped() {
        // AUTH-05 precondition: detect the Spotify app up-front, before the
        // user is invested in a song (tech-req §3.1 step 7). Tapping the
        // gate screen itself proceeds recognition-only (§6.4 degradation).
        val phase = viewModel.syncState.value.phase
        if (phase != SessionPhase.NEEDS_SPOTIFY &&
            !SpotifyAppDetector(this).isSpotifyInstalled() &&
            viewModel.onSpotifyMissing()  // false once dismissed → proceed
        ) {
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.startListening()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onDestroy() {
        routeObserver?.stop()
        routeObserver = null
        super.onDestroy()
    }
}
