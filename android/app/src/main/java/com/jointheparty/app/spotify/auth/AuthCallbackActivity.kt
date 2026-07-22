package com.jointheparty.app.spotify.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * AUTH-02: receives the `jointheparty://callback` deep link Spotify
 * redirects to after the user approves/declines on accounts.spotify.com
 * (technical-requirements.md §3.1 step 2; registered in AndroidManifest.xml
 * with `launchMode="singleTask"`).
 *
 * Deliberately dumb — no auth logic here. It hands the redirect
 * [android.net.Uri] to [SpotifyAuthManager.PendingCallback] for whichever
 * app-layer component is driving the auth flow to pick up, then finishes
 * immediately so it never lingers in the back stack or shows a frame of
 * UI.
 */
class AuthCallbackActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        finish()
    }

    private fun handleIntent(intent: Intent) {
        intent.data?.let { SpotifyAuthManager.PendingCallback.deliver(it) }
    }
}
