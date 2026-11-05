package com.arcsus.arcdisney.ui

import android.content.pm.ActivityInfo
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The player's window on Android 9: no status or navigation bar, and still
 * none after Android brings them back (the lock button, a dialog).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class PlayerWindowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var inPlayer by mutableStateOf(true)

    private val decor: View get() = compose.activity.window.decorView

    private fun barsHidden(): Boolean {
        val flags = decor.systemUiVisibility
        return flags and View.SYSTEM_UI_FLAG_FULLSCREEN != 0 && flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0
    }

    /** What Android does as the window loses focus: the bars come back. */
    private fun barsBroughtBack() = compose.runOnUiThread {
        decor.dispatchWindowFocusChanged(false)
        decor.systemUiVisibility = 0
    }

    @Before
    fun player() {
        compose.setContent { if (inPlayer) PlayerWindow() }
        compose.waitForIdle()
    }

    @Test
    fun noBarsInThePlayer() {
        assertTrue(barsHidden())
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, compose.activity.requestedOrientation)
    }

    @Test
    fun stillNoBarsAfterTheLockButton() {
        barsBroughtBack()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED) // screen off
        compose.waitForIdle()
        assertFalse(barsHidden())

        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) // back on, unlocked
        compose.runOnUiThread { decor.dispatchWindowFocusChanged(true) }
        compose.waitForIdle()
        assertTrue(barsHidden())
    }

    @Test
    fun hiddenOnResumeEvenBeforeTheWindowHasFocus() {
        barsBroughtBack()
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertTrue(barsHidden())
    }

    @Test
    fun stillNoBarsAfterADialog() {
        barsBroughtBack() // the audio and subtitles dialog takes focus
        compose.waitForIdle()
        compose.runOnUiThread { decor.dispatchWindowFocusChanged(true) } // and gives it back
        compose.waitForIdle()
        assertTrue(barsHidden())
    }

    @Test
    fun barsAndRotationBackOnTheWayOut() {
        inPlayer = false
        compose.waitForIdle()
        assertFalse(barsHidden())
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, compose.activity.requestedOrientation)
    }
}
