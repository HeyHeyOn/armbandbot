package com.heyheyon.armbandbot.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.heyheyon.armbandbot.ui.botColors

/**
 * Material components (dialogs, menus, text fields) follow the app's own dark-mode toggle
 * and the navy palette instead of the system theme / wallpaper colors.
 */
@Composable
fun ArmbandMaterialTheme(isDarkMode: Boolean, content: @Composable () -> Unit) {
    val c = botColors(isDarkMode)
    val scheme = if (isDarkMode) {
        darkColorScheme(
            primary = c.accent, onPrimary = Color(0xFF0F1318),
            primaryContainer = c.accentContainer, onPrimaryContainer = c.text,
            secondary = c.accent, onSecondary = Color(0xFF0F1318),
            secondaryContainer = c.accentContainer, onSecondaryContainer = c.text,
            background = c.bg, onBackground = c.text,
            surface = c.card, onSurface = c.text,
            surfaceVariant = c.surfaceMuted, onSurfaceVariant = c.subText,
            surfaceContainer = c.card, surfaceContainerHigh = c.dialogBg, surfaceContainerHighest = c.dialogBg,
            surfaceContainerLow = c.card, surfaceContainerLowest = c.bg,
            outline = Color(0xFF4A535E), outlineVariant = c.divider,
            error = c.warningRed,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = Color.White,
            primaryContainer = c.accentContainer, onPrimaryContainer = c.text,
            secondary = c.accent, onSecondary = Color.White,
            secondaryContainer = c.accentContainer, onSecondaryContainer = c.text,
            background = c.bg, onBackground = c.text,
            surface = c.card, onSurface = c.text,
            surfaceVariant = c.surfaceMuted, onSurfaceVariant = c.subText,
            surfaceContainer = c.card, surfaceContainerHigh = c.dialogBg, surfaceContainerHighest = c.dialogBg,
            surfaceContainerLow = c.card, surfaceContainerLowest = Color.White,
            outline = Color(0xFFC3CAD3), outlineVariant = c.divider,
            error = c.warningRed,
        )
    }
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, content = content)
}
