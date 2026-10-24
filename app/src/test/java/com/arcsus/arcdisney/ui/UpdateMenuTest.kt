package com.arcsus.arcdisney.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arcsus.arcdisney.data.CatalogueStore
import com.arcsus.arcdisney.data.Film
import com.arcsus.arcdisney.ui.theme.ArcDisneyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** The update button's long-press menu, opened again and again. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class UpdateMenuTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var wifi = 0
    private var debrid = 0
    private var tmdb = 0
    private var played = 0

    private fun wall(films: List<Film> = emptyList()) = compose.setContent {
        ArcDisneyTheme {
            PosterWall(
                films = films,
                query = "",
                onQuery = {},
                update = DisneyViewModel.Update.Idle,
                message = null,
                online = true,
                onUpdate = {},
                onOpenWifi = { wifi++ },
                onAllDebrid = { debrid++ },
                onTmdbKey = { tmdb++ },
                onPlay = { played++ },
            )
        }
    }

    private fun longPress() {
        compose.onNodeWithContentDescription("Look for new films").performTouchInput { longClick() }
        compose.waitForIdle()
    }

    @Test
    fun opensEveryTime() {
        wall()
        repeat(3) {
            longPress()
            compose.onNodeWithText("Wi-Fi").performClick()
            compose.waitForIdle()
            longPress()
            compose.onNodeWithText("AllDebrid account").performClick()
            compose.waitForIdle()
            longPress()
            compose.onNodeWithText("TMDB key").performClick()
            compose.waitForIdle()
        }
        assertEquals(listOf(3, 3, 3), listOf(wifi, debrid, tmdb))
    }

    private fun menuShown() = compose.onAllNodesWithText("AllDebrid account").fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aTapElsewhereClosesItWithoutPlayingAFilm() {
        wall(CatalogueStore.decode(File("src/main/assets/disney.json").readText()).playable.take(12))
        repeat(2) {
            longPress()
            assertTrue(menuShown())
            compose.onRoot().performTouchInput { click(Offset(width * 0.3f, height * 0.6f)) } // over a poster
            compose.waitForIdle()
            assertFalse(menuShown())
        }
        assertEquals(0, played)
    }

    @Test
    fun backClosesIt() {
        wall()
        longPress()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertFalse(menuShown())
        longPress()
        assertTrue(menuShown())
    }
}
