package com.arcsus.arcdisney.debrid

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class AllDebridTest {

    private val server = MockWebServer()
    private val calls = CopyOnWriteArrayList<RecordedRequest>()
    private var pinChecks = 0
    private var cached = true
    private var blocked = false

    private fun ok(data: String) = MockResponse().setBody("""{"status":"success","data":$data}""")

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls += request
                val path = request.requestUrl!!.encodedPath
                if (blocked) {
                    return MockResponse().setBody(
                        """{"status":"error","error":{"code":"AUTH_BLOCKED","message":"Confirm this <b>new location</b> in the email we sent you."}}""",
                    )
                }
                return when (path) {
                    "/v4/pin/get" -> ok(
                        """{"pin":"ABCD","check":"chk","expires_in":600,"user_url":"https://alldebrid.com/pin/?pin=ABCD","base_url":"https://alldebrid.com/pin/"}""",
                    )
                    "/v4/pin/check" -> if (++pinChecks < 3) ok("""{"activated":false,"expires_in":590}""") else ok("""{"activated":true,"apikey":"KEY123"}""")
                    "/v4/user" -> ok("""{"user":{"username":"richard","isPremium":true,"premiumUntil":1830000000}}""")
                    "/v4/magnet/upload" -> ok("""{"magnets":[{"id":"42","ready":$cached,"name":"Coco"}]}""")
                    "/v4/magnet/files" -> ok(
                        """{"magnets":[{"id":"42","files":[{"n":"Coco.2017.1080p","e":[
                            {"n":"Coco.2017.1080p.mkv","s":1900000000,"l":"https://alldebrid.com/f/film"},
                            {"n":"Coco.srt","s":90000,"l":"https://alldebrid.com/f/subs"}]}]}]}""",
                    )
                    "/v4/link/unlock" -> ok("""{"link":"https://cdn.alldebrid.com/dl/Coco.2017.1080p.mkv","filename":"Coco.2017.1080p.mkv"}""")
                    "/v4/magnet/delete" -> ok("""{"message":"deleted"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private val ad get() = AllDebrid(OkHttpClient(), server.url("/v4").toString().trimEnd('/'))

    @Test
    fun signsInWithAPin() = runTest {
        val pin = ad.startPin()
        assertEquals("ABCD", pin.pin)
        assertEquals("https://alldebrid.com/pin/?pin=ABCD", pin.qrUrl)
        assertEquals("KEY123", ad.awaitKey(pin, pollMs = 1))
        assertTrue(calls.all { it.requestUrl!!.queryParameter("agent") == "arcDisney" })

        val account = ad.account("KEY123")
        assertEquals("richard", account.username)
        assertTrue(account.premium)
        assertEquals("Bearer KEY123", calls.last().getHeader("Authorization"))
    }

    @Test
    fun anExpiredPinGivesNoKey() = runTest {
        pinChecks = -1_000_000 // never activated
        assertNull(ad.awaitKey(AllDebridPin("ABCD", "chk", "", "", expiresIn = 0), pollMs = 1))
    }

    @Test
    fun aCachedReleaseStreamsItsLargestFile() = runTest {
        val stream = ad.resolve("KEY123", "magnet:?xt=urn:btih:abc")
        assertEquals("https://cdn.alldebrid.com/dl/Coco.2017.1080p.mkv", stream.url)
        assertEquals("Coco.2017.1080p.mkv", stream.fileName)
        val upload = calls.first { it.requestUrl!!.encodedPath == "/v4/magnet/upload" }
        assertEquals("magnets%5B%5D=magnet%3A%3Fxt%3Durn%3Abtih%3Aabc", upload.body.readUtf8())
        val unlock = calls.first { it.requestUrl!!.encodedPath == "/v4/link/unlock" }
        assertEquals("https://alldebrid.com/f/film", unlock.requestUrl!!.queryParameter("link"))
    }

    @Test
    fun anUncachedReleaseIsRemovedAgain() = runTest {
        cached = false
        val error = runCatching { ad.resolve("KEY123", "magnet:?xt=urn:btih:abc") }.exceptionOrNull()
        assertTrue(error is NotCachedException)
        assertTrue(calls.any { it.requestUrl!!.encodedPath == "/v4/magnet/delete" && it.requestUrl!!.queryParameter("id") == "42" })
    }

    @Test
    fun accountProblemsCarryAllDebridsReason() = runTest {
        blocked = true
        val error = runCatching { ad.resolve("KEY123", "magnet:?xt=urn:btih:abc") }.exceptionOrNull() as AllDebridException
        assertEquals("AUTH_BLOCKED", error.code)
        assertEquals("Confirm this new location in the email we sent you.", error.message)
        assertTrue(error !is NotCachedException)
    }

    @Test
    fun keepsAReleaseAllDebridHasAndRemovesOneItHasnt() = runTest {
        assertEquals("42", ad.cachedId("KEY", "magnet:?xt=urn:btih:abc"))
        cached = false
        assertNull(ad.cachedId("KEY", "magnet:?xt=urn:btih:def"))
        val paths = calls.map { it.requestUrl!!.encodedPath }
        assertEquals(listOf("/v4/magnet/upload", "/v4/magnet/upload", "/v4/magnet/delete"), paths)

        val stream = ad.streamOf("KEY", "42")
        assertEquals("https://cdn.alldebrid.com/dl/Coco.2017.1080p.mkv", stream.url)
        assertEquals(1_900_000_000L, stream.size)
    }
}
