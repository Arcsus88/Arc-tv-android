package com.arcsus.arcdisney.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Knaben (api.knaben.org), a search over many trackers' uploads (The Pirate
 * Bay and YTS among them), filtered to single films worth streaming. It finds
 * films The Pirate Bay doesn't have, and keeps the search going when
 * apibay is down. Title searches only: it doesn't know IMDb ids.
 */
class Knaben(
    private val client: OkHttpClient,
    private val base: String = "https://api.knaben.org",
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(query: String): List<Release> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("search_type", "100%")
            put("search_field", "title")
            put("query", query)
            put("order_by", "seeders")
            put("order_direction", "desc")
            putJsonArray("categories") { add(MOVIES) }
            put("from", 0)
            put("size", 50)
            put("hide_unsafe", true)
            put("hide_xxx", true)
        }.toString()
        val request = Request.Builder().url("$base/v1")
            .header("User-Agent", "ArcDisney/1.0")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val text = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw SearchUnavailableException("Knaben answered HTTP ${response.code}.")
            response.body?.string().orEmpty()
        }
        val hits = runCatching { (json.parseToJsonElement(text) as JsonObject)["hits"] as JsonArray }.getOrNull()
            ?: throw SearchUnavailableException("Knaben sent something other than search results.")
        hits.mapNotNull { (it as? JsonObject)?.toRelease() }
    }

    private fun JsonObject.toRelease(): Release? {
        val name = str("title") ?: return null
        val hash = str("hash")?.lowercase() ?: return null
        if (!HASH.matches(hash)) return null
        val categories = (this["categoryId"] as? JsonArray).orEmpty().mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
        if (MOVIES.toString() !in categories) return null
        if (tvShaped(name) || isPack(name) || isCam(name) || isExtras(name)) return null
        // "seeders" is as last scraped (YTS caps it at 100); "liveSeeders", when there, is fresher.
        val seeds = maxOf(str("seeders")?.toIntOrNull() ?: 0, str("liveSeeders")?.toIntOrNull() ?: 0)
        return Release(name = name, hash = hash, size = str("bytes")?.toLongOrNull() ?: 0, seeds = seeds)
    }

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it != "null" }

    private companion object {
        /** Knaben's Movies category (its subcategories carry it too). */
        const val MOVIES = 3000000
        val HASH = Regex("^[0-9a-f]{40}$")
    }
}
