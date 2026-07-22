package com.spotify.android.appremote.api

/**
 * STUB — compile-time stand-in for the Spotify App Remote AAR (NAT-08).
 * Delete this file when the real dependency is vendored; call sites must
 * not change.
 *
 * Mirrors `com.spotify.android.appremote.api.ConnectionParams` as of App
 * Remote 0.8.x: an immutable value built via [Builder], constructed with
 * the app's Spotify client ID, a redirect URI, and whether to force the
 * (re-)auth view on connect.
 */
class ConnectionParams private constructor(
    val clientId: String,
    val redirectUri: String?,
    val showAuthView: Boolean,
) {
    class Builder(private val clientId: String) {
        private var redirectUri: String? = null
        private var showAuthView: Boolean = false

        fun setRedirectUri(redirectUri: String): Builder = apply { this.redirectUri = redirectUri }

        fun showAuthView(showAuthView: Boolean): Builder = apply { this.showAuthView = showAuthView }

        fun build(): ConnectionParams = ConnectionParams(clientId, redirectUri, showAuthView)
    }
}
