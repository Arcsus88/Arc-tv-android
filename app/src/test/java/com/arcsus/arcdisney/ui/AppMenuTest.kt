package com.arcsus.arcdisney.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arcsus.arcdisney.ui.theme.ArcDisneyTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The whole app (not MainActivity, which would start libtorrent for real):
 * first-run setup, then the update button's menu, used again and again.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class AppMenuTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        // Merged into the real manifest by androidx.core; Robolectric doesn't grant it on its own.
        shadowOf(compose.activity.application).grantPermissions("com.arcsus.arcdisney.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val vm = DisneyViewModel(compose.activity.application)
        compose.setContent { ArcDisneyTheme { App(vm) } }
    }

    private fun waitFor(text: String) =
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun openMenu() {
        compose.onNodeWithContentDescription("Look for new films").performTouchInput { longClick() }
        compose.waitForIdle()
        waitFor("Wi-Fi")
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun menuOpensAfterEachScreenItLeadsTo() {
        waitFor("Skip for now")
        compose.onNodeWithText("Skip for now").performClick() // Wi-Fi setup
        waitFor("Sign in with AllDebrid")
        compose.onNodeWithText("Skip for now").performClick() // AllDebrid offer
        waitFor("Search Disney films")

        repeat(2) {
            openMenu()
            compose.onNodeWithText("Wi-Fi").performClick()
            waitFor("Wi-Fi")
            back()
            compose.mainClock.advanceTimeBy(1_000)

            openMenu()
            compose.onNodeWithText("AllDebrid account").performClick()
            waitFor("Sign in with AllDebrid")
            back()
            compose.mainClock.advanceTimeBy(1_000)

            openMenu()
            compose.onNodeWithText("TMDB key").performClick()
            compose.waitForIdle()
            back()
            compose.mainClock.advanceTimeBy(1_000)
        }
        openMenu()
        assertTrue(compose.onAllNodesWithText("AllDebrid account").fetchSemanticsNodes().isNotEmpty())
    }
}
