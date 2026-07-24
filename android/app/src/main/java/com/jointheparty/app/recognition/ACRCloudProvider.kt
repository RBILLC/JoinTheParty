package com.jointheparty.app.recognition

import com.jointheparty.app.debug.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * NAT-06 (ACRCloud pivot, PM decision 2026-07-22): [RecognitionProvider]
 * over ACRCloud's identify API — replaces the stubbed ShazamKitProvider on
 * the Android MVP path.
 *
 * Protocol: POST https://<host>/v1/identify, multipart/form-data, HMAC-SHA1
 * signature over "POST\n/v1/identify\n<key>\naudio\n1\n<ts>" (signature
 * version 1). A successful match carries `play_offset_ms` (position in the
 * catalog track at the END of the sample), `external_ids.isrc`, and a
 * 0–100 `score` mapped to confidence (the documented floor is 70, so a
 * match never maps below 0.7). ACRCloud's schema documents
 * `frequency_skew` (see docs/recognition-audit-2026.md §1) — read when
 * present; when absent the estimator's observation-only drift path covers.
 *
 * Credentials: [Config] carries the console keys. A null config (today's
 * default until keys are injected — see docs/real-world-handoff.md) makes
 * [recognizeOnce] return null immediately: honest-but-inert, same policy as
 * the former stubs. SECURITY NOTE: shipping the access secret in the APK is
 * demo-tier only; production proxies identify calls through the backend
 * (AUTH-03's server) so the secret never leaves it.
 *
 * Audio: recognition samples come from [PcmWindowSource] — a tee of recent
 * mic audio. TODO(NAT-06b): implement the tee (AudioRecord during passes,
 * or a C++ capture tap); until then a null source also disables passes.
 */
class ACRCloudProvider(
    private val config: Config?,
    private val source: PcmWindowSource? = null,
) : RecognitionProvider {

    data class Config(
        val host: String,          // e.g. "identify-eu-west-1.acrcloud.com"
        val accessKey: String,
        val accessSecret: String,
    )

    /** A window of recent 16-bit little-endian mono 8 kHz–48 kHz PCM. */
    interface PcmWindowSource {
        data class PcmWindow(
            val pcm: ByteArray,
            val sampleRateHz: Int,
            val endMonoNs: Long,   // capture time of the window's last frame
        )

        fun latestWindow(): PcmWindow?
    }

    override suspend fun recognizeOnce(): RecognitionProvider.RecognitionFixResult? {
        val cfg = config
        if (cfg == null) {
            DebugLog.log("ACR: no keys configured — recognition inert")
            return null
        }
        val window = source?.latestWindow() ?: return null
        return withContext(Dispatchers.IO) {
            try {
                identify(cfg, window).also {
                    if (it != null) {
                        DebugLog.log(
                            "MATCH ✓ '${it.title}' offset=${it.matchOffsetMs}ms " +
                                "uri=${it.spotifyUri ?: "none(enable 3rd-party integ.)"}",
                        )
                    }
                }
            } catch (e: Exception) {
                DebugLog.log("ACR request failed: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }
    }

    override fun close() = Unit  // no persistent session; HTTP per pass

    private fun identify(
        cfg: Config,
        window: PcmWindowSource.PcmWindow,
    ): RecognitionProvider.RecognitionFixResult? {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val signature = sign(
            stringToSign("POST", "/v1/identify", cfg.accessKey, timestamp),
            cfg.accessSecret,
        )

        val boundary = "----JoinTheParty${System.nanoTime()}"
        val body = multipartBody(
            boundary,
            fields = mapOf(
                "access_key" to cfg.accessKey,
                "sample_bytes" to window.pcm.size.toString(),
                "timestamp" to timestamp,
                "signature" to signature,
                "data_type" to "audio",
                "signature_version" to "1",
            ),
            fileField = "sample",
            fileName = "sample.wav",
            fileBytes = pcmToWav(window.pcm, window.sampleRateHz),
        )

        val conn = URL("https://${cfg.host}/v1/identify")
            .openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty(
                "Content-Type", "multipart/form-data; boundary=$boundary",
            )
            conn.outputStream.use { it.write(body) }
            if (conn.responseCode !in 200..299) {
                DebugLog.log("ACR HTTP ${conn.responseCode} (check host/keys/network)")
                return null
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val status = json.optJSONObject("status")
            val code = status?.optInt("code", -1)
            if (code != 0) {
                // 1001 = no result (too quiet / not in catalog); 3xxx =
                // key/quota/signature problems worth surfacing verbatim.
                DebugLog.log("ACR status $code '${status?.optString("msg")}'")
            }
            parseMatch(json, window.endMonoNs)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseMatch(
        json: JSONObject,
        endMonoNs: Long,
    ): RecognitionProvider.RecognitionFixResult? {
        if (json.optJSONObject("status")?.optInt("code", -1) != 0) return null
        val music = json.optJSONObject("metadata")
            ?.optJSONArray("music")?.optJSONObject(0) ?: return null
        val offsetMs = music.optLong("play_offset_ms", -1L)
        if (offsetMs < 0) return null
        return RecognitionProvider.RecognitionFixResult(
            // play_offset_ms references the END of the sample; endMonoNs is
            // exactly that instant on our clock — the pairing SyncCore wants.
            matchOffsetMs = offsetMs,
            captureMonoNs = endMonoNs,
            // Documented in ACRCloud's metadata schema (audit 2026-07-22);
            // 0.0 when the response omits it.
            frequencySkew = music.optDouble("frequency_skew", 0.0),
            confidence = (music.optDouble("score", 80.0) / 100.0)
                .coerceIn(0.0, 1.0).toFloat(),
            title = music.optString("title").ifEmpty { null },
            artist = music.optJSONArray("artists")?.optJSONObject(0)
                ?.optString("name")?.ifEmpty { null },
            isrc = music.optJSONObject("external_ids")
                ?.optString("isrc")?.ifEmpty { null },
            // Requires "3rd Party Integration" enabled on the ACR console
            // project; absent otherwise.
            spotifyUri = music.optJSONObject("external_metadata")
                ?.optJSONObject("spotify")?.optJSONObject("track")
                ?.optString("id")?.ifEmpty { null }
                ?.let { "spotify:track:$it" },
        )
    }

    companion object {
        private const val TAG = "JTP"

        /** ACRCloud signature-version-1 string; internal for unit testing. */
        internal fun stringToSign(
            method: String,
            uri: String,
            accessKey: String,
            timestamp: String,
        ): String = "$method\n$uri\n$accessKey\naudio\n1\n$timestamp"

        internal fun sign(input: String, secret: String): String {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA1"))
            return android.util.Base64.encodeToString(
                mac.doFinal(input.toByteArray(StandardCharsets.UTF_8)),
                android.util.Base64.NO_WRAP,
            )
        }

        private fun multipartBody(
            boundary: String,
            fields: Map<String, String>,
            fileField: String,
            fileName: String,
            fileBytes: ByteArray,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            fun writeLine(s: String) = out.write("$s\r\n".toByteArray())
            for ((k, v) in fields) {
                writeLine("--$boundary")
                writeLine("Content-Disposition: form-data; name=\"$k\"")
                writeLine("")
                writeLine(v)
            }
            writeLine("--$boundary")
            writeLine(
                "Content-Disposition: form-data; name=\"$fileField\"; " +
                    "filename=\"$fileName\"",
            )
            writeLine("Content-Type: application/octet-stream")
            writeLine("")
            out.write(fileBytes)
            writeLine("")
            writeLine("--$boundary--")
            return out.toByteArray()
        }

        /** Minimal 16-bit mono WAV header around raw PCM. */
        internal fun pcmToWav(pcm: ByteArray, sampleRateHz: Int): ByteArray {
            val byteRate = sampleRateHz * 2
            val total = 36 + pcm.size
            val out = ByteArrayOutputStream(44 + pcm.size)
            fun le32(v: Int) = out.write(
                byteArrayOf(
                    (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
                    ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
                ),
            )
            fun le16(v: Int) = out.write(
                byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()),
            )
            out.write("RIFF".toByteArray()); le32(total)
            out.write("WAVE".toByteArray()); out.write("fmt ".toByteArray())
            le32(16); le16(1); le16(1); le32(sampleRateHz); le32(byteRate)
            le16(2); le16(16)
            out.write("data".toByteArray()); le32(pcm.size)
            out.write(pcm)
            return out.toByteArray()
        }
    }
}
