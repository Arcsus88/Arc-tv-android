package com.arcsus.arcdisney.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

@Serializable
data class ReleaseAsset(val id: Long, val name: String, val size: Long = 0)

@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tag: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<ReleaseAsset> = emptyList(),
)

data class AppUpdate(val version: String, val asset: ReleaseAsset)

/**
 * The newest release above [current], with the APK built for this device's
 * CPU ([abis], best first, as Build.SUPPORTED_ABIS lists them) or else the
 * universal one. Newest by version, not by date, so re-publishing an old
 * release never offers it as an update.
 */
fun pickUpdate(releases: List<GithubRelease>, current: String, abis: List<String>): AppUpdate? {
    val release = releases
        .filter { !it.draft && !it.prerelease && it.assets.any { a -> a.name.endsWith(".apk") } }
        .maxWithOrNull { a, b -> compareVersions(a.tag.removePrefix("v"), b.tag.removePrefix("v")) }
        ?: return null
    val version = release.tag.removePrefix("v")
    if (compareVersions(version, current) <= 0) return null
    val apks = release.assets.filter { it.name.endsWith(".apk") }
    val asset = abis.firstNotNullOfOrNull { abi -> apks.firstOrNull { it.name.endsWith("-$abi.apk") } }
        ?: apks.firstOrNull { it.name.endsWith("-universal.apk") }
        ?: apks.first()
    return AppUpdate(version, asset)
}

/** Dotted numeric versions: "1.2.10" > "1.2.9" > "1.1.0"; missing parts count as 0. */
fun compareVersions(a: String, b: String): Int {
    val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
    val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
        val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    return 0
}

/**
 * This private repo's releases, read with a fine-grained token that can only
 * read it. Asset downloads redirect to GitHub's storage, and OkHttp drops the
 * token on that cross-host redirect, so it never leaves api.github.com.
 */
class ReleaseClient(
    private val http: OkHttpClient,
    private val token: String,
    private val repo: String,
    private val api: String = "https://api.github.com",
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun releases(): List<GithubRelease> = withContext(Dispatchers.IO) {
        val request = authorized("$api/repos/$repo/releases?per_page=10")
            .header("Accept", "application/vnd.github+json")
            // /releases/latest sits behind a cache that can lag a fresh publish.
            .header("Cache-Control", "no-cache")
            .build()
        http.newCall(request).execute().use { r ->
            if (r.code == 401 || r.code == 404) throw IOException("GitHub refused the update token (HTTP ${r.code}).")
            if (!r.isSuccessful) throw IOException("GitHub answered HTTP ${r.code}.")
            json.decodeFromString(ListSerializer(GithubRelease.serializer()), r.body?.string().orEmpty())
        }
    }

    /** Downloads [asset] to [to], replacing it only once the whole file has arrived. */
    suspend fun download(asset: ReleaseAsset, to: File) = withContext(Dispatchers.IO) {
        val request = authorized("$api/repos/$repo/releases/assets/${asset.id}")
            .header("Accept", "application/octet-stream")
            .build()
        val part = File(to.path + ".part")
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Update download failed (HTTP ${r.code}).")
            part.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
        }
        if (asset.size > 0 && part.length() != asset.size) {
            part.delete()
            throw IOException("Update download was cut short.")
        }
        to.delete()
        if (!part.renameTo(to)) throw IOException("Couldn't save the update.")
    }

    private fun authorized(url: String) = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $token")
        .header("X-GitHub-Api-Version", "2022-11-28")
}
