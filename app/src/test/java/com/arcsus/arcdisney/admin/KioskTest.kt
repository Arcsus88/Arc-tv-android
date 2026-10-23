package com.arcsus.arcdisney.admin

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arcsus.arcdisney.update.AutoUpdater
import com.arcsus.arcdisney.update.ReleaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Device-owner kiosk mode and the silent self-update, on Robolectric's Android 9. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class KioskTest {

    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val dpm = activity.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(activity, AdminReceiver::class.java)
    private val home = ComponentName(activity.packageName, "${activity.packageName}.KioskHome")

    @Test
    fun anOrdinaryInstallIsLeftAlone() {
        Kiosk.setUp(activity)
        assertFalse(Kiosk.isDeviceOwner(activity))
        assertTrue(activity.packageManager.getComponentEnabledSetting(home) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
    }

    @Test
    fun asDeviceOwnerItLocksTheDeviceToItself() {
        shadowOf(dpm).setDeviceOwner(admin)
        Kiosk.setUp(activity)

        assertArrayEquals(arrayOf(activity.packageName), dpm.getLockTaskPackages(admin))
        val features = dpm.getLockTaskFeatures(admin)
        assertTrue(features and DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS != 0)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, activity.packageManager.getComponentEnabledSetting(home))
        assertEquals(
            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            dpm.getPermissionGrantState(admin, activity.packageName, Manifest.permission.ACCESS_FINE_LOCATION),
        )
    }

    @Test
    fun anUpdateWaitsForTheFilmThenInstalls() = runBlocking {
        val apk = ByteArray(4_096) { it.toByte() }
        val api = MockWebServer().apply {
            enqueue(
                MockResponse().setBody(
                    """[{"tag_name":"v1.2.7","assets":[
                        {"id":1,"name":"arc-disney-1.2.7-arm64-v8a.apk","size":${apk.size}},
                        {"id":2,"name":"arc-disney-1.2.7-universal.apk","size":${apk.size}}]}]""",
                ),
            )
            enqueue(MockResponse().setBody(Buffer().write(apk)))
            start()
        }
        val updater = AutoUpdater(
            activity,
            ReleaseClient(OkHttpClient(), "token", "Arcsus88/Arc-tv-android", api.url("").toString().trimEnd('/')),
            currentVersion = "1.2.6",
            abis = listOf("arm64-v8a"),
        )
        updater.busy.value = true // a film is playing

        val round = launch(Dispatchers.IO) { updater.checkAndInstall() }
        // It downloads straight away but doesn't install mid-film.
        api.takeRequest()
        assertEquals("/repos/Arcsus88/Arc-tv-android/releases/assets/1", api.takeRequest().path)
        Thread.sleep(300)
        assertTrue(activity.packageManager.packageInstaller.allSessions.isEmpty())

        updater.busy.value = false // the film ends
        round.join()
        val session = activity.packageManager.packageInstaller.allSessions.single()
        assertEquals(activity.packageName, session.appPackageName)
        api.shutdown()
    }
}
