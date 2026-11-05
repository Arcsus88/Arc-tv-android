package com.arcsus.arcdisney.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
 * The player's controls are out of a child's reach: tapping the film doesn't
 * bring up play/pause and the skips, only a finger held still on it does.
 * Wired as PlayerScreen wires it, over the real controls (VLC can't run here).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28], qualifiers = "w852dp-h393dp-land-xxhdpi")
class PlayerHoldTest {

    @get:Rule
    val compose = createComposeRule()

    private var controls by mutableStateOf(false)
    private var playPauses = 0
    private var skips = 0

    @Before
    fun film() = compose.setContent {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapOrHold(HOLD_FOR_CONTROLS_MS, onTap = { controls = false }, onHold = { controls = true })
                },
        ) {
            if (controls) {
                PlayerControls(
                    title = "Coco",
                    playing = true,
                    time = 2_412_000,
                    length = 6_300_000,
                    scrub = null,
                    detail = "Streaming from AllDebrid",
                    fill = false,
                    onBack = {},
                    onFill = {},
                    onTracks = {},
                    onPlayPause = { playPauses++ },
                    onSkip = { skips++ },
                    onScrub = {},
                    onScrubEnd = {},
                )
            }
        }
    }

    private fun shown() = compose.onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().isNotEmpty()

    /** Left of the skip buttons: film, with nothing over it even when the controls are up. */
    private val bare get() = compose.onRoot().fetchSemanticsNode().size.let { Offset(it.width * 0.15f, it.height * 0.5f) }

    @Test
    fun tapsDoNothing() {
        repeat(3) {
            compose.onRoot().performTouchInput { click(center) }
            compose.waitForIdle()
        }
        assertFalse(shown())
    }

    @Test
    fun anOrdinaryLongPressIsNotEnough() {
        compose.onRoot().performTouchInput { longClick(center) }
        compose.waitForIdle()
        assertFalse(shown())
    }

    @Test
    fun holdingAFingerOnTheFilmBringsThemUp() {
        compose.onRoot().performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(HOLD_FOR_CONTROLS_MS + 100)
        assertTrue("up while the finger is still down", shown())
        // The play button came up under the finger; lifting it doesn't press it.
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
        assertTrue(shown())
        assertEquals(0, playPauses)
    }

    @Test
    fun aFingerDraggedAroundIsNotAHold() {
        compose.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(200f, 0f))
        }
        compose.mainClock.advanceTimeBy(HOLD_FOR_CONTROLS_MS + 100)
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
        assertFalse(shown())
    }

    @Test
    fun onceUpTheButtonsWorkAndATapOnTheFilmHidesThem() {
        compose.onRoot().performTouchInput { longClick(center, durationMillis = HOLD_FOR_CONTROLS_MS + 100) }
        compose.waitForIdle()
        assertTrue(shown())

        compose.onNodeWithContentDescription("Pause").performClick()
        compose.onNodeWithContentDescription("Forward 10 seconds").performClick()
        compose.waitForIdle()
        assertEquals(1, playPauses)
        assertEquals(1, skips)
        assertTrue("pressing a button isn't a tap on the film", shown())

        compose.onRoot().performTouchInput { click(bare) }
        compose.waitForIdle()
        assertFalse(shown())
    }
}
