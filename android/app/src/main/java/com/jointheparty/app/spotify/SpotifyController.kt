package com.jointheparty.app.spotify

import kotlinx.coroutines.flow.Flow

/**
 * NAT-08: the app-facing seam over Spotify App Remote — mirrors the iOS
 * `SpotifyController` (NAT-07) so both shells expose the same controller
 * duties: connect lifecycle, `play(uri)`, `seekTo` with the
 * `sc_notify_seek_issued` echo, and a player-state subscription that feeds
 * `SyncEngine.submitPlayerState`.
 *
 * [AppRemoteSpotifyController] is the production implementation, built
 * against the (currently stubbed — see `com.spotify.*`) App Remote API.
 * Extracted as an interface, same rationale as `SyncEngine`
 * (android/app/src/main/java/com/jointheparty/app/core/SyncEngine.kt):
 * lets session/UI code substitute a fake without touching App Remote.
 */
interface SpotifyController {

    /**
     * Outcome of [connect], mirroring technical-requirements.md §3.1 step 6
     * and the §2.4 `needsSpotify`/`needsPremium` phase transitions.
     */
    sealed interface ConnectionResult {
        data object Connected : ConnectionResult
        data object SpotifyMissing : ConnectionResult
        data object AuthFailed : ConnectionResult
        data class Failed(val cause: Throwable) : ConnectionResult
    }

    /** One player-state sample, timestamped when this process received it. */
    data class RemotePlayerState(
        val trackUri: String?,
        val positionMs: Long,
        val isPaused: Boolean,
        val receivedMonoNs: Long,
    )

    /** Every player-state event App Remote delivers, in order. */
    val playerStates: Flow<RemotePlayerState>

    /** Most recent player state, for aim-verification (null before any). */
    val lastKnownPlayerState: RemotePlayerState?

    /** Connects to the Spotify app via App Remote. Safe to call again after a [Failed]/[SpotifyMissing] result. */
    suspend fun connect(): ConnectionResult

    /** Tears down the App Remote connection. Idempotent; safe if never connected. */
    fun disconnect()

    /** Issues `PlayerApi.play(spotifyUri)`. Returns false if not connected. */
    fun play(spotifyUri: String): Boolean

    /**
     * Issues `PlayerApi.seekTo(positionMs)` and MUST echo
     * `sc_notify_seek_issued` (technical-requirements.md §1.2) so the
     * estimator suppresses measurements during the settle window. Returns
     * false if not connected — no seek issued, no echo.
     */
    fun seekTo(positionMs: Long): Boolean

    val isConnected: Boolean
}
