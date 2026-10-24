package dev.shipyu.mindverse.core.updates

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * GitHub Releases update check (ticket 08 / spec R2-2): `releases/latest` of
 * `warrior-pk/mindverse`, compared against the running versionCode, surfacing the APK
 * asset for the sideload installer. No store APIs, no background poller — the caller owns
 * scheduling (cold-start debounce, spec R6-3) and the 24h stamp.
 *
 * Release metadata convention (repo's release recipe):
 *   - release body carries `versionCode: <N>` (authoritative), or
 *   - an asset is named `mindverse-<N>-...apk`, or
 *   - the tag `v<versionName>` compares against the running versionName as a fallback.
 */
@Serializable
data class ReleaseAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
)

@Serializable
data class ReleaseInfo(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val body: String? = null,
    val prerelease: Boolean = false,
    val assets: List<ReleaseAsset> = emptyList(),
)

sealed interface UpdateOutcome {
    data class Available(
        val versionCode: Int,
        val versionName: String,
        /** APK asset to hand to the installer (null when the release ships no APK). */
        val apkUrl: String?,
        val releaseUrl: String,
    ) : UpdateOutcome

    data object UpToDate : UpdateOutcome

    data class Failed(val reason: String) : UpdateOutcome
}

class ReleaseUpdateChecker(
    private val client: OkHttpClient,
    private val apiUrl: String = DEFAULT_API_URL,
    private val currentVersionCode: Int,
    private val currentVersionName: String,
) {

    suspend fun check(): UpdateOutcome = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        val release = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext UpdateOutcome.Failed("HTTP ${response.code}")
                }
                val body = response.body?.string()
                    ?: return@withContext UpdateOutcome.Failed("empty response")
                runCatching { json.decodeFromString<ReleaseInfo>(body) }
                    .getOrElse { return@withContext UpdateOutcome.Failed("malformed release payload") }
            }
        } catch (io: IOException) {
            return@withContext UpdateOutcome.Failed("unreachable: ${io.message.orEmpty().take(120)}")
        }

        if (release.prerelease) return@withContext UpdateOutcome.UpToDate

        val remoteCode = versionCodeOf(release) ?: return@withContext UpdateOutcome.Failed("no versionCode in release")
        if (remoteCode <= currentVersionCode) return@withContext UpdateOutcome.UpToDate

        val apkUrl = release.assets.firstOrNull { it.name.endsWith(".apk") }?.browserDownloadUrl
        UpdateOutcome.Available(
            versionCode = remoteCode,
            versionName = versionNameOf(release),
            apkUrl = apkUrl?.takeUnless { it.isBlank() },
            releaseUrl = release.htmlUrl.takeUnless { it.isBlank() }
                ?: release.assets.firstOrNull()?.browserDownloadUrl.orEmpty(),
        )
    }

    /** Body marker → asset name → tag fallback; null when nothing parses. */
    internal fun versionCodeOf(release: ReleaseInfo): Int? {
        BODY_VERSION_CODE.find(release.body.orEmpty())?.let { match ->
            match.groupValues[1].toIntOrNull()?.let { return it }
        }
        for (asset in release.assets) {
            ASSET_VERSION_CODE.find(asset.name)?.let { match ->
                match.groupValues[1].toIntOrNull()?.let { return it }
            }
        }
        // Tag fallback: v<versionName>; same version → same code, otherwise compare parts.
        val tag = release.tagName.removePrefix("v")
        if (tag.isEmpty()) return null
        return if (tag == currentVersionName) {
            currentVersionCode
        } else if (isNewerVersionName(tag, currentVersionName)) {
            currentVersionCode + 1
        } else {
            currentVersionCode - 1
        }
    }

    private fun versionNameOf(release: ReleaseInfo): String =
        release.tagName.removePrefix("v").ifEmpty { release.name }.ifEmpty { "unknown" }

    /** Numeric-segment versionName comparison (0.4.0 < 0.4.1 < 0.10.0). */
    internal fun isNewerVersionName(candidate: String, current: String): Boolean {
        val candidateParts = candidate.split('.').mapNotNull { it.toIntOrNull() }
        val currentParts = current.split('.').mapNotNull { it.toIntOrNull() }
        val size = maxOf(candidateParts.size, currentParts.size)
        for (index in 0 until size) {
            val a = candidateParts.getOrElse(index) { 0 }
            val b = currentParts.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    companion object {
        const val DEFAULT_API_URL = "https://api.github.com/repos/warrior-pk/mindverse/releases/latest"

        private val json = Json { ignoreUnknownKeys = true }
        private val BODY_VERSION_CODE = Regex("""versionCode[:\s]+(\d+)""", RegexOption.IGNORE_CASE)
        private val ASSET_VERSION_CODE = Regex("""mindverse-(\d+)""")
    }
}
