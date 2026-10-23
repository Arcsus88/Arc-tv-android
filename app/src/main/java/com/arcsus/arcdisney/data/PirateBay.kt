package com.arcsus.arcdisney.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** A release search could not be done: the site is blocked, down, or answering with something else. */
class SearchUnavailableException(message: String) : IOException(message)

/** The Pirate Bay's JSON API (apibay.org), filtered to single films worth streaming. */
class PirateBay(
    private val client: OkHttpClient,
    private val base: String = "https://apibay.org",
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(query: String): List<Release> = withContext(Dispatchers.IO) {
        val url = "$base/q.php".toHttpUrl().newBuilder().addQueryParameter("q", query).build()
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (compatible; ArcDisney/1.0)")
            .header("Accept", "application/json")
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw SearchUnavailableException("The Pirate Bay answered HTTP ${response.code}.")
            response.body?.string().orEmpty()
        }
        // A block page (HTML) must read as a failure, never as "no seeds".
        val list = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull()
            ?: throw SearchUnavailableException("The Pirate Bay sent something other than search results.")
        list.mapNotNull { (it as? JsonObject)?.toRelease() }
    }

    private fun JsonObject.toRelease(): Release? {
        val name = str("name") ?: return null
        val hash = str("info_hash")?.lowercase() ?: return null
        // "No results" comes back as a single row with an all-zero hash.
        if (!HASH.matches(hash) || ZEROS.matches(hash)) return null
        if (str("category") !in MOVIE_CATEGORIES) return null
        if (tvShaped(name) || isPack(name) || isCam(name) || isExtras(name)) return null
        return Release(
            name = name,
            hash = hash,
            size = str("size")?.toLongOrNull() ?: 0,
            seeds = str("seeders")?.toIntOrNull() ?: 0,
        )
    }

    private fun JsonObject.str(key: String): String? = runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

    private companion object {
        val HASH = Regex("^[0-9a-f]{40}$")
        val ZEROS = Regex("^0+$")
    }
}
