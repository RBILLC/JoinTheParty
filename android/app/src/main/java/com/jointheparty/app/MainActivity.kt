package com.jointheparty.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.lifecycle.lifecycleScope
import com.jointheparty.app.data.DataStoreAppPrefs
import com.jointheparty.app.spotify.SpotifyAppDetector
import com.jointheparty.app.spotify.auth.EncryptedTokenStore
import com.jointheparty.app.spotify.auth.SpotifyAuthManager
import com.jointheparty.app.ui.onboarding.OnboardingScreen
import com.jointheparty.app.ui.session.SessionPhase
import com.jointheparty.app.ui.session.SessionScreen
import com.jointheparty.app.ui.session.SessionViewModel
import com.jointheparty.app.ui.theme.BilletTheme
import com.jointheparty.app.ui.theme.DT
import kotlinx.coroutines.launch

/**
 * UI-05: hosts the session. The Activity owns platform concerns only —
 * mic permission, App Remote's consent UI handoff — and hands
 * projections/callbacks to the stateless [SessionScreen]. All session logic
 * lives in [SessionViewModel]; all engine traffic below that. Route
 * observation moved to [com.jointheparty.app.session.SessionGraph] (INT-06a,
 * technical-requirements.md §2.5) — the session outlives this Activity.
 */
class MainActivity : ComponentActivity() {

    // INT-06a: the graph — and this ViewModel — is process-scoped
    // (JoinThePartyApplication.sessionGraph), not owned by this Activity's
    // ViewModelStore, so recreation (rotation) reattaches to the same live
    // instance instead of rebuilding the session.
    private val viewModel: SessionViewModel
        get() = (application as JoinThePartyApplication).sessionGraph.viewModel

    private val appPrefs by lazy { DataStoreAppPrefs(applicationContext) }

    private val tokenStore by lazy { EncryptedTokenStore(applicationContext) }

    /** AUTH-02, live: PKCE flow with the registered client id. */
    private val authManager by lazy {
        SpotifyAuthManager(
            context = this,
            tokenStore = tokenStore,
            clientId = BuildConfig.SPOTIFY_CLIENT_ID,
        )
    }

    /** Field feedback: the IDLE screen must know Spotify is already linked. */
    private val spotifyLinkedState = androidx.compose.runtime.mutableStateOf(false)

    // INT-06c: RECORD_AUDIO and POST_NOTIFICATIONS (API 33+) are requested
    // together in one flow. See joinTapped() for when each is included.
    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) viewModel.startListening()
        // Denied: stay in IDLE — the Join button remains the retry point.
        // POST_NOTIFICATIONS denial is non-fatal (tech-req §2.5 permission
        // matrix) — the FGS still runs, its notification is just
        // suppressed — so it never gates startListening() here.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // AUTH-02: complete the PKCE exchange when the Custom Tab redirects
        // back through AuthCallbackActivity → PendingCallback.
        lifecycleScope.launch {
            spotifyLinkedState.value = tokenStore.tokens() != null
            SpotifyAuthManager.PendingCallback.redirect.collect { uri ->
                if (uri != null) {
                    authManager.handleCallback(uri)
                    SpotifyAuthManager.PendingCallback.consume()
                    spotifyLinkedState.value = tokenStore.tokens() != null
                }
            }
        }

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
                            onConnectSpotify = { authManager.beginAuth() },
                            spotifyLinked = spotifyLinkedState.value,
                            playbackPositionMs = viewModel.playbackPositionMs,
                            onLeaveSession = viewModel::reset,
                            inputLevel = viewModel.inputLevel,
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
        val micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (micGranted) {
            // INT-06c: mic is the gating permission. Once it's granted we
            // never re-ask for POST_NOTIFICATIONS on a later tap — a
            // denied notification permission is non-fatal (§2.5) and must
            // not block or nag the join flow.
            viewModel.startListening()
            return
        }
        // First-run one-shot combined ask: RECORD_AUDIO (missing here) plus
        // POST_NOTIFICATIONS on API 33+ if it isn't already granted.
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        permissionRequest.launch(permissions)
    }

    override fun onStart() {
        super.onStart()
        // INT-06c: attach only while the Activity is started — the window
        // where App Remote's consent UI could actually render (tech-req
        // §2.5). A backgrounded reconnect that needs consent fails closed
        // to needsSpotify; the service notification's "Action needed"
        // copy is the recovery path.
        viewModel.attachActivity(this)
    }

    override fun onStop() {
        viewModel.attachActivity(null)
        super.onStop()
    }
}
