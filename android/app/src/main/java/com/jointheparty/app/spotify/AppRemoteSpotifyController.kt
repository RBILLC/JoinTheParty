package com.jointheparty.app.spotify

import android.content.Context
import com.jointheparty.app.core.SyncEngine
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import com.spotify.protocol.client.EventCallback
import com.spotify.protocol.types.PlayerState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * NAT-08: [SpotifyController] implemented against Spotify App Remote (the
 * `com.spotify.*` classes are, for now, the compile-faithful STUBS under
 * android/app/src/main/java/com/spotify/ — see their headers for the
 * stub-to-real swap procedure). Every call site below is written exactly
 * as it will be against the real AAR.
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
    private val clientId: String = "PENDING_AUTH-02_CLIENT_ID"

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
        val ctx = context ?: return SpotifyController.ConnectionResult.SpotifyMissing

        val params = ConnectionParams.Builder(clientId)
            .setRedirectUri(redirectUri)
            .showAuthView(true)
            .build()

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
        playerApi.play(spotifyUri)
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
        playerApi.seekTo(positionMs)
        engine.notifySeekIssued(positionMs, issuedMonoNs)
        return true
    }

    private fun subscribeToPlayerState(spotifyAppRemote: SpotifyAppRemote) {
        spotifyAppRemote.playerApi.subscribeToPlayerState()
            .setEventCallback(
                EventCallback<PlayerState> { data ->
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
}
