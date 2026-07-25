package com.jointheparty.app.spotify

import android.content.Context
import com.jointheparty.app.core.SyncEngine
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.PlayerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * NAT-08: [SpotifyController] implemented against the REAL Spotify App
 * Remote AAR (android/app/libs/, vendored from the public GitHub release —
 * tools/fetch_spotify_sdks.sh records provenance). The former com.spotify.*
 * stubs are deleted; the swap required exactly one adjustment (nested
 * Subscription.EventCallback).
 *
 * [context] is nullable to keep this class JVM-unit-testable without
 * Robolectric (see AppRemoteControllerTest): [connect] treats a null
 * context as an immediate, honest [SpotifyController.ConnectionResult.SpotifyMissing]
 * — there is no Context to hand App Remote, so no wake-up is possible,
 * which is the same terminal state a real device reaches when App Remote
 * can't be reached. Production call sites always supply a real
 * [Context] (Application/Activity).
 */
class AppRemoteSpotifyController(
    private val context: Context?,
    private val engine: SyncEngine,
    private val redirectUri: String = "jointheparty://callback",
) : SpotifyController {

    // TODO(AUTH-02): source the real Spotify client ID from configured app
    // credentials once the PKCE flow ticket lands; App Remote's
    // ConnectionParams requires one to attempt a connection at all.
    private val clientId: String = com.jointheparty.app.BuildConfig.SPOTIFY_CLIENT_ID

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * FIELD FIX (2026-07-24): App Remote's `showAuthView` renders the
     * consent UI through an **Activity**; handed only an application
     * context it cannot present anything and fails immediately with
     * UserNotAuthorizedException (observed on device even after the web
     * PKCE consent — App Remote authorization is a separate grant).
     * MainActivity sets this while resumed and clears it on destroy, so no
     * Activity outlives its lifecycle here.
     */
    var activityContext: Context? = null

    private val playerStatesFlow = MutableSharedFlow<SpotifyController.RemotePlayerState>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** App Remote callbacks arrive on App Remote's own main-thread handler. */
    override val playerStates: Flow<SpotifyController.RemotePlayerState> = playerStatesFlow.asSharedFlow()

    private var remote: SpotifyAppRemote? = null

    override val isConnected: Boolean
        get() = remote?.isConnected == true

    override suspend fun connect(): SpotifyController.ConnectionResult {
        // Prefer the Activity so the auth view can actually be shown.
        val ctx = activityContext ?: context
            ?: return SpotifyController.ConnectionResult.SpotifyMissing

        val params = ConnectionParams.Builder(clientId)
            .setRedirectUri(redirectUri)
            .showAuthView(true)
            .build()

        // FIELD FIX (2026-07-24): App Remote MUST be connected from the main
        // thread — its Connector posts callbacks to the caller's Looper, so
        // calling from Dispatchers.Default binds the service but never fires
        // onConnected/onFailure (observed: session hung in AIMING forever).
        // The timeout is belt-and-braces: a silent SDK is now a reported
        // failure rather than an infinite wait.
        return withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            withContext(Dispatchers.Main) { awaitConnection(ctx, params) }
        } ?: SpotifyController.ConnectionResult.Failed(
            IllegalStateException("App Remote connect timed out after ${CONNECT_TIMEOUT_MS}ms"),
        )
    }

    private suspend fun awaitConnection(
        ctx: Context,
        params: ConnectionParams,
    ): SpotifyController.ConnectionResult {
        return suspendCancellableCoroutine { cont ->
            SpotifyAppRemote.connect(
                ctx,
                params,
                object : Connector.ConnectionListener {
                    override fun onConnected(spotifyAppRemote: SpotifyAppRemote) {
                        remote = spotifyAppRemote
                        subscribeToPlayerState(spotifyAppRemote)
                        if (cont.isActive) cont.resume(SpotifyController.ConnectionResult.Connected)
                    }

                    override fun onFailure(throwable: Throwable) {
                        // technical-requirements.md §3.1 step 6 error taxonomy:
                        // no Spotify app → needsSpotify; not-logged-in / not
                        // authorized (incl. Premium-gated actions rejected by
                        // the app) → needsPremium/re-auth, surfaced as
                        // AuthFailed here — the caller (session flow) maps
                        // AuthFailed to needsPremium once it has also
                        // confirmed the app IS installed via
                        // SpotifyAppDetector (AUTH-05).
                        com.jointheparty.app.debug.DebugLog.log(
                            "AppRemote onFailure: ${throwable.javaClass.simpleName}: " +
                                "${throwable.message}",
                        )
                        val result = when (throwable) {
                            is CouldNotFindSpotifyApp -> SpotifyController.ConnectionResult.SpotifyMissing
                            is NotLoggedInException,
                            is UserNotAuthorizedException,
                            -> SpotifyController.ConnectionResult.AuthFailed
                            else -> SpotifyController.ConnectionResult.Failed(throwable)
                        }
                        if (cont.isActive) cont.resume(result)
                    }
                },
            )
        }
    }

    override fun disconnect() {
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
    }

    override fun play(spotifyUri: String): Boolean {
        val playerApi = remote?.playerApi ?: return false
        // Same main-thread rule as connect(): App Remote's transport posts
        // its result callbacks to the caller's Looper.
        mainHandler.post { playerApi.play(spotifyUri) }
        // Self-hearing guard arm (spec §7.3): position 0 because play(uri)
        // always starts a track from the top.
        engine.notifyLocalPlayback(0)
        return true
    }

    override fun seekTo(positionMs: Long): Boolean {
        val playerApi = remote?.playerApi ?: return false
        // Captured BEFORE issuing the command: sc_notify_seek_issued's
        // timestamp must reflect when *this process* committed to the seek,
        // not whenever App Remote's (never-firing, in the stub) result
        // callback happens to return. SyncCore uses this both to open the
        // ~3 s settle-window suppression (tech-req §1.2) and, via
        // notifyLocalPlayback-adjacent bookkeeping, to learn command
        // latency online (CORE-03 extra: sc_get_command_latency_ms).
        val issuedMonoNs = System.nanoTime()
        mainHandler.post { playerApi.seekTo(positionMs) }
        engine.notifySeekIssued(positionMs, issuedMonoNs)
        return true
    }

    private fun subscribeToPlayerState(spotifyAppRemote: SpotifyAppRemote) {
        // Real-SDK note (vendor swap): EventCallback is nested in
        // Subscription, and PlayerState/Track expose public Java fields.
        spotifyAppRemote.playerApi.subscribeToPlayerState()
            .setEventCallback(
                Subscription.EventCallback<PlayerState> { data ->
                    val receivedMonoNs = System.nanoTime()
                    playerStatesFlow.tryEmit(
                        SpotifyController.RemotePlayerState(
                            trackUri = data.track?.uri,
                            positionMs = data.playbackPosition,
                            isPaused = data.isPaused,
                            receivedMonoNs = receivedMonoNs,
                        ),
                    )
                    engine.submitPlayerState(data.playbackPosition, data.isPaused, receivedMonoNs)
                },
            )
    }

    private companion object {
        /** App Remote occasionally never calls back; don't hang the session. */
        const val CONNECT_TIMEOUT_MS = 20_000L
    }
}
