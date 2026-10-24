package com.arcsus.arcdisney.debrid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** AllDebrid said no, with its reason (bad key, needs confirming by email, not premium…). */
open class AllDebridException(message: String, val code: String? = null) : IOException(message) {
    /** The account (key, premium, location) rather than one film's release or link. */
    val isAccountProblem: Boolean
        get() = code != null && ACCOUNT_CODES.any { code.startsWith(it) }

    private companion object {
        val ACCOUNT_CODES = listOf("AUTH_", "MUST_BE_PREMIUM", "USER_", "FREE_TRIAL", "NO_SERVER")
    }
}

/** The release isn't in AllDebrid's cache, so it can't stream from there right now. */
class NotCachedException : AllDebridException("Not ready on AllDebrid.")

/** A PIN to enter at [url] (or [qrUrl], which has the PIN in it) from another phone or computer. */
data class AllDebridPin(val pin: String, val check: String, val url: String, val qrUrl: String, val expiresIn: Int)

data class AllDebridAccount(val username: String, val premium: Boolean, val premiumUntil: Long)

/** A film's file as a link VLC can play straight away (only from the IP that asked for it). */
data class DebridStream(val url: String, val fileName: String, val size: Long = 0)

/**
 * AllDebrid API v4, called from the device itself: AllDebrid refuses magnet
 * requests from server IPs, and its links only play from the IP that asked
 * for them. Ported from Arc TV's client.
 */
class AllDebrid(
    private val http: OkHttpClient,
    private val base: String = "https://api.alldebrid.com/v4",
) {
    private val json = Json { ignoreUnknownKeys = true }

    // ---- Sign-in (PIN flow) ----

    suspend fun startPin(): AllDebridPin = withContext(Dispatchers.IO) {
        val d = dataOf(get(url("/pin/get")))
        val pin = d.str("pin") ?: throw AllDebridException("AllDebrid sent no PIN.")
        AllDebridPin(
            pin = pin,
            check = d.str("check") ?: throw AllDebridException("AllDebrid sent no check token."),
            url = d.str("base_url") ?: "https://alldebrid.com/pin/",
            qrUrl = d.str("user_url") ?: "https://alldebrid.com/pin/?pin=$pin",
            expiresIn = d.int("expires_in") ?: 600,
        )
    }

    /** Waits for the PIN to be entered and returns the account's API key, or null once it expires. */
    suspend fun awaitKey(pin: AllDebridPin, pollMs: Long = 5_000): String? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + pin.expiresIn * 1000L
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            val d = try {
                dataOf(get(url("/pin/check", "check" to pin.check, "pin" to pin.pin)))
            } catch (e: IOException) {
                null // a blip, or not entered yet: keep waiting
            }
            val key = d?.str("apikey")
            if (d?.bool("activated") == true && !key.isNullOrBlank()) return@withContext key
            delay(pollMs)
        }
        null
    }

    suspend fun account(key: String): AllDebridAccount = withContext(Dispatchers.IO) {
        val user = dataOf(get(url("/user"), key))["user"] as? JsonObject
            ?: throw AllDebridException("AllDebrid sent no account details.")
        AllDebridAccount(
            username = user.str("username").orEmpty(),
            premium = user.bool("isPremium") == true,
            premiumUntil = user.long("premiumUntil") ?: 0,
        )
    }

    // ---- Playback ----

    /**
     * The release's largest file as a link VLC can play, if AllDebrid already
     * has it. A release it doesn't have is removed from the account again.
     */
    suspend fun resolve(key: String, magnet: String): DebridStream = withContext(Dispatchers.IO) {
        val (id, ready) = upload(key, magnet)
        if (!ready) {
            delete(key, id)
            throw NotCachedException()
        }
        streamOf(key, id)
    }

    /**
     * The id of the release in the account if AllDebrid already has it (it's
     * kept, for [streamOf]); one it doesn't have is removed again and gives null.
     */
    suspend fun cachedId(key: String, magnet: String): String? = withContext(Dispatchers.IO) {
        val (id, ready) = try {
            upload(key, magnet)
        } catch (e: NotCachedException) {
            return@withContext null
        }
        if (ready) {
            id
        } else {
            delete(key, id)
            null
        }
    }

    /** The largest file of a release AllDebrid has, unlocked for this device. */
    suspend fun streamOf(key: String, id: String): DebridStream = withContext(Dispatchers.IO) {
        val files = mutableListOf<Triple<String, Long, String>>() // name, size, link
        fun walk(entries: JsonArray?) {
            for (e in entries ?: return) {
                val o = e as? JsonObject ?: continue
                val children = o["e"] as? JsonArray
                if (children != null) walk(children) else o.str("l")?.let { files += Triple(o.str("n").orEmpty(), o.long("s") ?: 0, it) }
            }
        }
        val filesData = dataOf(get(url("/magnet/files", "id[]" to id), key))
        walk((filesData.arr("magnets")?.firstOrNull() as? JsonObject)?.get("files") as? JsonArray)
        val best = files.maxByOrNull { it.second } ?: run {
            delete(key, id)
            throw AllDebridException("AllDebrid has no files for this release.")
        }
        val unlocked = dataOf(get(url("/link/unlock", "link" to best.third), key))
        val link = unlocked.str("link") ?: run {
            delete(key, id)
            throw AllDebridException("AllDebrid couldn't unlock the film.")
        }
        DebridStream(link, unlocked.str("filename") ?: best.first, unlocked.long("filesize") ?: best.second)
    }

    /** Adds the release to the account: its id there, and whether AllDebrid already has it. */
    private fun upload(key: String, magnet: String): Pair<String, Boolean> {
        val upload = dataOf(post(url("/magnet/upload"), key, FormBody.Builder().add("magnets[]", magnet).build()))
        val m = upload.arr("magnets")?.firstOrNull() as? JsonObject
            ?: throw AllDebridException("AllDebrid didn't take the release.")
        (m["error"] as? JsonObject)?.let {
            // One release it won't take (a bad magnet) is just one it doesn't have.
            if (it.str("code")?.startsWith("MAGNET_") == true) throw NotCachedException()
            throw AllDebridException(clean(it.str("message")) ?: "AllDebrid refused the release.", it.str("code"))
        }
        val id = m.str("id") ?: throw AllDebridException("AllDebrid sent no id for the release.")
        return id to (m.bool("ready") == true)
    }

    private fun delete(key: String, id: String) {
        runCatching { get(url("/magnet/delete", "id" to id), key) }
    }

    // ---- Plumbing ----

    private fun url(path: String, vararg params: Pair<String, String>): HttpUrl =
        "$base$path".toHttpUrl().newBuilder().addQueryParameter("agent", AGENT).apply {
            for ((k, v) in params) addQueryParameter(k, v)
        }.build()

    private fun get(url: HttpUrl, key: String? = null): String =
        execute(Request.Builder().url(url).apply { key?.let { header("Authorization", "Bearer $it") } }.build())

    private fun post(url: HttpUrl, key: String, body: FormBody): String =
        execute(Request.Builder().url(url).header("Authorization", "Bearer $key").post(body).build())

    private fun execute(request: Request): String = http.newCall(request).execute().use { r ->
        r.body?.string().orEmpty().ifBlank { throw AllDebridException("AllDebrid sent an empty answer (HTTP ${r.code}).") }
    }

    /** Unwraps AllDebrid's {status, data | error} envelope, throwing its error. */
    private fun dataOf(body: String): JsonObject {
        val root = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull()
            ?: throw AllDebridException("AllDebrid sent an unreadable answer.")
        if (root.str("status") != "success") {
            val err = root["error"] as? JsonObject
            throw AllDebridException(clean(err?.str("message")) ?: "AllDebrid said no.", err?.str("code"))
        }
        return root["data"] as? JsonObject ?: JsonObject(emptyMap())
    }

    private fun clean(message: String?) = message?.replace(Regex("<[^>]*>"), "")?.trim()?.ifBlank { null }

    private companion object {
        const val AGENT = "arcDisney"
    }
}

private fun JsonObject.str(k: String): String? = runCatching { this[k]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it != "null" }

private fun JsonObject.int(k: String): Int? = str(k)?.toDoubleOrNull()?.toInt()

private fun JsonObject.long(k: String): Long? = str(k)?.toDoubleOrNull()?.toLong()

private fun JsonObject.bool(k: String): Boolean? = str(k)?.toBooleanStrictOrNull()

private fun JsonObject.arr(k: String): JsonArray? = runCatching { this[k]?.jsonArray }.getOrNull()
