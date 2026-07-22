package com.jointheparty.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.jointheparty.app.audio.AudioRouteObserver
import com.jointheparty.app.spotify.SpotifyAppDetector
import com.jointheparty.app.ui.session.SessionPhase
import com.jointheparty.app.ui.session.SessionScreen
import com.jointheparty.app.ui.session.SessionViewModel
import com.jointheparty.app.ui.theme.BilletTheme

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
                val state by viewModel.syncState.collectAsState()
                SessionScreen(
                    state = state,
                    meterFrames = viewModel.meterFrames,
                    onJoinTap = ::joinTapped,
                    onTrimChange = { /* optimistic display only; commit below */ },
                    onTrimCommit = viewModel::onNudgeCommitted,
                )
            }
        }
    }

    private fun joinTapped() {
        // AUTH-05 precondition: detect the Spotify app up-front, before the
        // user is invested in a song (tech-req §3.1 step 7). Tapping the
        // gate screen itself proceeds recognition-only (§6.4 degradation).
        val phase = viewModel.syncState.value.phase
        if (phase != SessionPhase.NEEDS_SPOTIFY &&
            !SpotifyAppDetector(this).isSpotifyInstalled()
        ) {
            viewModel.onSpotifyMissing()
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
