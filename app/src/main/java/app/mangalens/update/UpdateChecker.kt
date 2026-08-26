package app.mangalens.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import app.mangalens.translate.LlmHttp
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sideloaded apps have no store to update them, so the app checks for itself:
 * one anonymous request to the repository's latest-release endpoint when the
 * home screen opens, a quiet banner if the version there is newer, and
 * nothing at all — no error, no nag — when offline or rate-limited. The
 * banner links straight to the correct APK asset; nothing downloads without
 * the user. Releases before 0.9.2 were signed with the public debug key, so
 * Android 9+ gets a one-time proof-of-rotation bridge while Android 8 must do
 * a one-time reinstall (that platform predates signing-key rotation).
 */
object UpdateChecker {

    const val REPO = "mkisontop/mangalens"
    const val LATEST_URL = "https://github.com/$REPO/releases/latest"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    private const val PRIMARY_APK = "MangaLens.apk"
    private const val LEGACY_BRIDGE_APK = "MangaLens-legacy-update.apk"
    private const val RELEASE_CERT_SHA256 =
        "6bde3720dc055d1f233970e4c038cb5955d816a6353c557aa59689742ab75ab6"
    private const val LEGACY_DEBUG_CERT_SHA256 =
        "df0e8ef059da2b1bb121e49252a07c55e468fb4453a4fbb7d90263d3c7b7a344"

    enum class SigningTrack { RELEASE, LEGACY_DEBUG, UNKNOWN }

    data class Update(
        val version: String,
        val url: String,
        val legacyBridge: Boolean = false,
        val requiresReinstall: Boolean = false,
    )

    /** Returns the newer release, or null for "current, unreachable, or unsure". */
    suspend fun check(
        currentVersion: String,
        sdkInt: Int,
        signingTrack: SigningTrack,
    ): Update? = withContext(Dispatchers.IO) {
        checkBlocking(currentVersion, sdkInt, signingTrack)
    }

    private suspend fun checkBlocking(
        currentVersion: String,
        sdkInt: Int,
        signingTrack: SigningTrack,
    ): Update? = runCatching {
        val request = Request.Builder()
            .url(API)
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        LlmHttp.await(LlmHttp.client.newCall(request)).use { resp ->
            if (!resp.isSuccessful) return null
            val body = JSONObject(resp.body?.string() ?: return null)
            updateFromRelease(body, currentVersion, sdkInt, signingTrack)
        }
    }.getOrNull()

    /** Pure release-response routing, kept separate so the signing migration is testable. */
    internal fun updateFromRelease(
        body: JSONObject,
        currentVersion: String,
        sdkInt: Int,
        signingTrack: SigningTrack,
    ): Update? {
        val latest = numeric(body.optString("tag_name"))
        val releaseUrl = body.optString("html_url").ifBlank { LATEST_URL }
        val assets = assetUrls(body.optJSONArray("assets") ?: JSONArray())
        return updateForAssets(latest, releaseUrl, currentVersion, sdkInt, signingTrack, assets)
    }

    /** Pure asset selection; Android's stub JSON classes do not leak into local tests. */
    internal fun updateForAssets(
        latest: String,
        releaseUrl: String,
        currentVersion: String,
        sdkInt: Int,
        signingTrack: SigningTrack,
        assets: Map<String, String>,
    ): Update? {
        if (latest.isEmpty() || !isNewer(latest, currentVersion)) return null

        if (signingTrack == SigningTrack.LEGACY_DEBUG && sdkInt >= 28) {
            val bridge = assets[LEGACY_BRIDGE_APK]
            if (bridge != null) {
                return Update(latest, bridge, legacyBridge = true)
            }
        }

        val primary = assets[PRIMARY_APK] ?: releaseUrl
        return Update(
            version = latest,
            url = primary,
            requiresReinstall = signingTrack != SigningTrack.RELEASE,
        )
    }

    /** Identifies the installed APK itself; version names cannot prove signing compatibility. */
    fun installedSigningTrack(context: Context): SigningTrack = runCatching {
        val packageInfo = if (Build.VERSION.SDK_INT >= 28) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val signature = if (Build.VERSION.SDK_INT >= 28) {
            packageInfo.signingInfo?.apkContentsSigners?.singleOrNull()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures?.singleOrNull()
        }
        signingTrack(signature?.toByteArray()?.let(::sha256))
    }.getOrDefault(SigningTrack.UNKNOWN)

    internal fun signingTrack(certificateSha256: String?): SigningTrack = when (certificateSha256?.lowercase()) {
        RELEASE_CERT_SHA256 -> SigningTrack.RELEASE
        LEGACY_DEBUG_CERT_SHA256 -> SigningTrack.LEGACY_DEBUG
        else -> SigningTrack.UNKNOWN
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private fun assetUrls(assets: JSONArray): Map<String, String> = buildMap {
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (name.isNotBlank() && url.isNotBlank()) put(name, url)
        }
    }

    /** "v0.9.1", "mangalens-v0.9.1", "0.9.1-debug" → "0.9.1". */
    fun numeric(version: String): String =
        Regex("""\d+(?:\.\d+)*""").find(version)?.value ?: ""

    /**
     * Numeric segment-by-segment comparison — "0.10.0" is newer than "0.9.1",
     * which string comparison gets wrong. Unparseable input compares as
     * not-newer: a malformed tag must never produce an update banner.
     */
    fun isNewer(latest: String, current: String): Boolean {
        val a = numeric(latest).split('.').map { it.toIntOrNull() ?: return false }
        val b = numeric(current).split('.').map { it.toIntOrNull() ?: return false }
        if (a.isEmpty() || b.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
