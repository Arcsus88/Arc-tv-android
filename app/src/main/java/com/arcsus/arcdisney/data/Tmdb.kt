package com.arcsus.arcdisney.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalDate

class TmdbException(message: String) : IOException(message)

/**
 * TMDB, for the list of Disney films. Takes either kind of TMDB credential:
 * a v3 API key (sent as ?api_key=) or a v4 read access token (a JWT, sent as
 * a bearer token).
 */
class Tmdb(
    private val client: OkHttpClient,
    private val key: String,
    private val base: String = "https://api.themoviedb.org/3",
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Every Disney-branded and Pixar film of 40 minutes or more (see
     * [COMPANIES]), out by [today], most popular first.
     */
    suspend fun disneyFilms(today: LocalDate = LocalDate.now()): List<Film> = withContext(Dispatchers.IO) {
        val byId = LinkedHashMap<Int, Film>()
        var page = 1
        var pages: Int
        do {
            val d = get("/discover/movie") {
                addQueryParameter("with_companies", COMPANIES)
                addQueryParameter("primary_release_date.gte", "1935-01-01")
                addQueryParameter("primary_release_date.lte", today.toString())
                addQueryParameter("with_runtime.gte", "40")
                addQueryParameter("include_adult", "false")
                addQueryParameter("sort_by", "popularity.desc")
                addQueryParameter("page", page.toString())
            }
            pages = (d.int("total_pages") ?: 1).coerceAtMost(500)
            for (x in (d["results"] as? JsonArray).orEmpty()) {
                val o = x as? JsonObject ?: continue
                val id = o.int("id") ?: continue
                byId[id] = Film(
                    id = id,
                    title = o.str("title").orEmpty(),
                    year = o.str("release_date").orEmpty().take(4),
                    poster = o.str("poster_path").orEmpty(),
                    popularity = o.str("popularity")?.toDoubleOrNull() ?: 0.0,
                )
            }
            page++
        } while (page <= pages)
        byId.values.toList()
    }

    suspend fun imdbId(id: Int): String? = withContext(Dispatchers.IO) {
        get("/movie/$id/external_ids").str("imdb_id")?.takeIf { it.startsWith("tt") }
    }

    private fun get(path: String, params: HttpUrl.Builder.() -> Unit = {}): JsonObject {
        val url = "$base$path".toHttpUrl().newBuilder().apply {
            addQueryParameter("language", "en-US")
            if (!isBearer) addQueryParameter("api_key", key)
            params()
        }.build()
        val request = Request.Builder().url(url).apply { if (isBearer) header("Authorization", "Bearer $key") }.build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401) throw TmdbException("TMDB didn't accept the API key.")
            if (!response.isSuccessful) throw TmdbException("TMDB answered HTTP ${response.code}.")
            return runCatching { json.parseToJsonElement(response.body?.string().orEmpty()) as JsonObject }
                .getOrElse { throw TmdbException("TMDB sent an unreadable answer.") }
        }
    }

    private val isBearer get() = key.startsWith("eyJ")

    private fun JsonObject.str(k: String): String? =
        runCatching { this[k]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it != "null" }

    private fun JsonObject.int(k: String): Int? = str(k)?.toDoubleOrNull()?.toInt()

    private companion object {
        /**
         * Walt Disney Pictures | Walt Disney Productions | Walt Disney Animation
         * Studios (and its second entry) | Pixar (TMDB credits Pixar's features
         * to Pixar alone) | Walt Disney Feature Animation (TMDB's home for The
         * Little Mermaid to Brother Bear) | Walt Disney Animation (two entries) |
         * DisneyToon Studios | Disney Television Animation | Walt Disney Television
         * | Disney Channel | Disney Branded Television | Disneynature. Not
         * Touchstone, Marvel, Lucasfilm or 20th Century.
         */
        const val COMPANIES = "2|3166|6125|158526|3|171656|112779|177000|5391|3475|670|240533|241784|4436"
    }
}
