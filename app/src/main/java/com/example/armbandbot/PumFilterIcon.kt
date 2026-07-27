package com.heyheyon.armbandbot

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Retweet-style opposing arrows used for the PUM filter setting. */
val PumFilterIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "PumFilterOpposingArrows",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(4f, 7f)
            curveTo(4f, 5.9f, 4.9f, 5f, 6f, 5f)
            lineTo(16.2f, 5f)
            lineTo(14.1f, 2.9f)
            lineTo(15.5f, 1.5f)
            lineTo(20f, 6f)
            lineTo(15.5f, 10.5f)
            lineTo(14.1f, 9.1f)
            lineTo(16.2f, 7f)
            lineTo(6f, 7f)
            lineTo(6f, 11f)
            lineTo(4f, 11f)
            close()

            moveTo(20f, 13f)
            lineTo(20f, 17f)
            curveTo(20f, 18.1f, 19.1f, 19f, 18f, 19f)
            lineTo(7.8f, 19f)
            lineTo(9.9f, 21.1f)
            lineTo(8.5f, 22.5f)
            lineTo(4f, 18f)
            lineTo(8.5f, 13.5f)
            lineTo(9.9f, 14.9f)
            lineTo(7.8f, 17f)
            lineTo(18f, 17f)
            lineTo(18f, 13f)
            close()
        }
    }.build()
}
