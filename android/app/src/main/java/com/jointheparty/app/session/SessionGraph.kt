package com.jointheparty.app.session

import android.content.Context
import com.jointheparty.app.audio.AudioRouteObserver
import com.jointheparty.app.audio.AudioTrackChirpPlayer
import com.jointheparty.app.backend.HttpBackendClient
import com.jointheparty.app.core.SyncCore
import com.jointheparty.app.data.DataStoreNudgeStore
import com.jointheparty.app.recognition.ACRCloudProvider
import com.jointheparty.app.recognition.EnginePcmWindowSource
import com.jointheparty.app.service.SessionForegroundService
import com.jointheparty.app.spotify.AppRemoteSpotifyController
import com.jointheparty.app.ui.session.SessionPhase
import com.jointheparty.app.ui.session.SessionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * INT-06a (technical-requirements.md §2.5): the process-scoped owner of the
 * session object graph — SyncCore, recognition, backend, chirp playback,
 * Spotify control, route observation, and the [SessionViewModel] built on
 * top of them. Previously this graph was built by `SessionViewModel
 * .Companion.Factory` and lived only as long as `MainActivity`'s
 * ViewModelStore; now it is built once per process (lazily, from
 * [com.jointheparty.app.JoinThePartyApplication.sessionGraph]) so an
 * Activity recreation (rotation, config change) reattaches to the live
 * session instead of tearing it down and losing phase/track state.
 *
 * **Single-owner rule:** [SessionGraph] is the ONLY caller of
 * `engine.close()` — see [close]. In practice this graph lives for the
 * process lifetime and nothing calls [close] yet; it exists for the
 * service-stop path (INT-06b, once `SessionForegroundService` owns when a
 * session is actually over) and for tests.
 */
class SessionGraph(context: Context) {

    /** The session's lifetime anchor, replacing `viewModelScope`. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Retained for [SessionForegroundService.start] below. */
    private val appContext = context.applicationContext

    private val engine = SyncCore(deadbandMs = ENGINE_DEADBAND_MS)

    private val nudgeStore = DataStoreNudgeStore(context.applicationContext)

    // ACRCloud credentials come from the gitignored android/local.properties
    // via BuildConfig (acr.host / acr.key / acr.secret) — see
    // app/build.gradle.kts. Empty (unconfigured) keeps recognition safely
    // inert.
    private val acrConfig = ACRCloudProvider.Config(
        host = com.jointheparty.app.BuildConfig.ACR_HOST,
        accessKey = com.jointheparty.app.BuildConfig.ACR_KEY,
        accessSecret = com.jointheparty.app.BuildConfig.ACR_SECRET,
    )

    private val recognition = ACRCloudProvider(
        config = acrConfig.takeIf { it.accessKey.isNotEmpty() },
        source = EnginePcmWindowSource(engine),
    )

    // AUTH-03/04: no backend is deployed yet, so this is the mock-mode
    // HttpBackendClient(baseUrl = null) — see its class doc for the swap
    // procedure once one exists.
    private val backend = HttpBackendClient(baseUrl = null)

    private val chirp = AudioTrackChirpPlayer()

    private val spotify = AppRemoteSpotifyController(
        context = context.applicationContext,
        engine = engine,
    )

    val viewModel: SessionViewModel = SessionViewModel(
        engine = engine,
        nudgeStore = nudgeStore,
        recognition = recognition,
        backend = backend,
        chirp = chirp,
        spotify = spotify,
        scope = scope,
    )

    // NAT-02: route changes feed the engine's per-route latency prior via
    // the ViewModel (see SessionViewModel.onRouteChanged). AudioRouteObserver
    // uses applicationContext internally, so it is safe to anchor here
    // rather than on an Activity.
    private val routeObserver = AudioRouteObserver(context) { route, routeId, routeName ->
        viewModel.onRouteChanged(routeId, routeName, route)
    }.also { it.start() }

    // INT-06b (technical-requirements.md §2.5): start SessionForegroundService
    // on the IDLE/ERROR → any-active-phase edge. This is a push — the graph
    // never calls stopService; the service observes the same syncState
    // stream and stops itself on the way back to idle/error (see that
    // class's doc for the split — exactly one stop decision point). The
    // trigger is always a foreground Join tap, so the mic-type FGS
    // background-start restriction (API 34+) is satisfied: this is never
    // invoked while backgrounded.
    private var lastPhaseWasInactive = true

    init {
        viewModel.syncState
            .map { it.phase }
            .onEach { phase ->
                val nowInactive = phase == SessionPhase.IDLE || phase == SessionPhase.ERROR
                if (lastPhaseWasInactive && !nowInactive) {
                    SessionForegroundService.start(appContext)
                }
                lastPhaseWasInactive = nowInactive
            }
            .launchIn(scope)
    }

    /**
     * Tears the graph down: cancels [scope], stops [routeObserver], and
     * closes [engine] — the single call site for `engine.close()` per the
     * class doc's single-owner rule. Nothing calls this yet in production;
     * it exists for the future service-stop path (INT-06b) and for tests.
     */
    fun close() {
        scope.cancel()
        routeObserver.stop()
        engine.close()
    }

    companion object {
        /**
         * Engine correction deadband for this shell (sc_config_t.deadband_ms):
         * above ACR's fix noise so corrections fire only on audibly-wrong
         * error; an audible skip every few seconds annoys more than
         * sub-400 ms offset between separated sources.
         */
        private const val ENGINE_DEADBAND_MS = 350
    }
}
