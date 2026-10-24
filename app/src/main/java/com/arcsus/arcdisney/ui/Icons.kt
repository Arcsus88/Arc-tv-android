package com.arcsus.arcdisney.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The few Material icons outside material-icons-core, which is all the app
 * ships (the extended set adds ~10 MB of dex to an unminified APK).
 */
object AppIcons {
    val Pause: ImageVector = icon("Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z")

    val Subtitles: ImageVector = icon(
        "Subtitles",
        "M20,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2z" +
            "M4,12h4v2H4v-2zM14,18H4v-2h10v2zM20,18h-4v-2h4v2zM20,14H10v-2h10v2z",
    )

    val AspectRatio: ImageVector = icon(
        "AspectRatio",
        "M19,12h-2v3h-3v2h5v-5zM7,9h3V7H5v5h2V9zM21,3H3c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h18" +
            "c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM21,19.01H3V4.99h18v14.02z",
    )

    /** A circular arrow, for the ten-second skips (mirrored for back). */
    val Skip: ImageVector = icon(
        "Skip",
        "M12,5V1L17,6l-5,5V7c-3.31,0 -6,2.69 -6,6s2.69,6 6,6 6,-2.69 6,-6h2c0,4.42 -3.58,8 -8,8" +
            "s-8,-3.58 -8,-8 3.58,-8 8,-8z",
    )

    val Bolt: ImageVector = icon(
        "Bolt",
        "M11,21h-1l1,-7H7.5c-0.88,0 -0.33,-0.75 -0.31,-0.78C8.48,10.94 10.42,7.54 13.01,3h1l-1,7h3.51" +
            "c0.4,0 0.62,0.19 0.4,0.66C12.97,17.55 11,21 11,21z",
    )

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}
