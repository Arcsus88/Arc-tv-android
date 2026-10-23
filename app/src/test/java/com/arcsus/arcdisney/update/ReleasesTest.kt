package com.arcsus.arcdisney.update

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

class ReleasesTest {

    private fun release(tag: String, vararg assets: String, draft: Boolean = false) =
        GithubRelease(tag, draft = draft, assets = assets.mapIndexed { i, n -> ReleaseAsset(i + 1L, n, 100) })

    private val v5 = release(
        "v1.2.5",
        "arc-disney-1.2.5-arm64-v8a.apk",
        "arc-disney-1.2.5-armeabi-v7a.apk",
        "arc-disney-1.2.5-universal.apk",
    )

    @Test
    fun comparesVersionsNumerically() {
        assertTrue(compareVersions("1.2.10", "1.2.9") > 0)
        assertTrue(compareVersions("1.2.1", "1.1.0") > 0)
        assertEquals(0, compareVersions("1.2", "1.2.0"))
        assertTrue(compareVersions("1.1.0", "1.2.0") < 0)
    }

    @Test
    fun picksTheNewestReleaseAndThisCpusApk() {
        val older = release("v1.2.4", "arc-disney-1.2.4-universal.apk")
        val draft = release("v1.2.9", "arc-disney-1.2.9-universal.apk", draft = true)
        val update = pickUpdate(listOf(older, v5, draft), "1.1.0", listOf("arm64-v8a", "armeabi-v7a", "armeabi"))!!
        assertEquals("1.2.5", update.version)
        assertEquals("arc-disney-1.2.5-arm64-v8a.apk", update.asset.name)

        // A 32-bit system on a 64-bit chip lists only the 32-bit ABIs.
        val arm32 = pickUpdate(listOf(v5), "1.1.0", listOf("armeabi-v7a", "armeabi"))!!
        assertEquals("arc-disney-1.2.5-armeabi-v7a.apk", arm32.asset.name)

        val unknownCpu = pickUpdate(listOf(v5), "1.1.0", listOf("riscv64"))!!
        assertEquals("arc-disney-1.2.5-universal.apk", unknownCpu.asset.name)
    }

    @Test
    fun nothingWhenAlreadyCurrentOrNoApk() {
        assertNull(pickUpdate(listOf(v5), "1.2.5", listOf("arm64-v8a")))
        assertNull(pickUpdate(listOf(v5), "1.2.6", listOf("arm64-v8a")))
        assertNull(pickUpdate(listOf(release("v9.0.0", "notes.txt")), "1.1.0", listOf("arm64-v8a")))
    }

    @Test
    fun readsReleasesWithTheTokenAndDownloadsWithoutLeakingIt() = runTest {
        val apk = ByteArray(5_000) { (it % 251).toByte() }
        val storage = MockWebServer().apply {
            enqueue(MockResponse().setBody(Buffer().write(apk)))
            start()
        }
        val api = MockWebServer().apply {
            enqueue(
                MockResponse().setBody(
                    """[{"tag_name":"v1.2.5","draft":false,"prerelease":false,
                        "assets":[{"id":77,"name":"arc-disney-1.2.5-universal.apk","size":${apk.size}}]}]""",
                ),
            )
            // GitHub answers an asset download with a redirect to its storage host.
            enqueue(MockResponse().setResponseCode(302).setHeader("Location", storage.url("/signed/blob").toString()))
            start()
        }
        val client = ReleaseClient(OkHttpClient(), "github_pat_test", "Arcsus88/Arc-tv-android", api.url("").toString().trimEnd('/'))

        val releases = client.releases()
        val listed = api.takeRequest()
        assertEquals("/repos/Arcsus88/Arc-tv-android/releases?per_page=10", listed.path)
        assertEquals("Bearer github_pat_test", listed.getHeader("Authorization"))

        val file = Files.createTempFile("update", ".apk").toFile()
        client.download(releases.single().assets.single(), file)
        val asked = api.takeRequest()
        assertEquals("/repos/Arcsus88/Arc-tv-android/releases/assets/77", asked.path)
        assertEquals("application/octet-stream", asked.getHeader("Accept"))
        val fetched = storage.takeRequest()
        assertNull("the token must not follow the redirect", fetched.getHeader("Authorization"))
        assertArrayEquals(apk, file.readBytes())

        api.shutdown()
        storage.shutdown()
        file.delete()
    }

    @Test
    fun aBadTokenIsAnErrorNotNoUpdate() = runTest {
        val api = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(404))
            start()
        }
        val client = ReleaseClient(OkHttpClient(), "expired", "Arcsus88/Arc-tv-android", api.url("").toString().trimEnd('/'))
        val error = runCatching { client.releases() }.exceptionOrNull()
        assertTrue(error is IOException && error.message!!.contains("token"))
        api.shutdown()
    }
}
