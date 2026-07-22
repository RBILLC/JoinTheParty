package com.jointheparty.app.spotify

import android.content.Context
import android.content.pm.PackageManager

/**
 * AUTH-05: session-precondition check — is the Spotify app installed?
 * (technical-requirements.md §3.1 step 7). Package visibility to query
 * `com.spotify.music` on API 30+ is granted by the `<queries>` entry in
 * android/app/src/main/AndroidManifest.xml; without it this would always
 * report "not installed" on modern targetSdk regardless of reality.
 *
 * Feeds the `idle`/`listening` → `needsSpotify` transition (§2.4) at
 * session start; keep this fast (`getPackageInfo` is a local binder call,
 * no I/O) so it never blocks the listening path per NAT-08/AUTH-05's
 * acceptance criterion (< 1 s, non-blocking when Spotify is healthy).
 */
class SpotifyAppDetector(private val context: Context) {

    fun isSpotifyInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(SPOTIFY_PACKAGE_NAME, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        private const val SPOTIFY_PACKAGE_NAME = "com.spotify.music"
    }
}
