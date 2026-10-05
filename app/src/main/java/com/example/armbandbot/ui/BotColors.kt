package com.heyheyon.armbandbot.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PastelNavy = Color(0xFF4A6583)
val PastelNavyLight = Color(0xFFE8ECEF)
val DarkTerminal = Color(0xFF161B22)

data class BotColorScheme(
    val bg: Color,
    val topBar: Color,
    val card: Color,
    val dialogBg: Color,
    val text: Color,
    val subText: Color,
    val divider: Color,
    val warningRed: Color,
    val iconTint: Color,
    val switchUncheckedThumb: Color,
    val switchUncheckedTrack: Color,
    val blockCard: Color,
    /** Brand accent for selected states, links and primary actions. */
    val accent: Color = PastelNavy,
    /** Soft tinted surface behind accent icons and chips. */
    val accentContainer: Color = PastelNavyLight,
    /** Positive state (running, enabled summaries). */
    val success: Color = Color(0xFF2E7D5B),
    val successContainer: Color = Color(0xFFE3F2EA),
    /** Waiting / scheduled state. */
    val pending: Color = Color(0xFFB26A00),
    val pendingContainer: Color = Color(0xFFFFF1DC),
    /** Neutral filled surface inside cards (inputs, previews). */
    val surfaceMuted: Color = Color(0xFFF2F4F7),
)

@Composable
fun botColors(isDarkMode: Boolean) = BotColorScheme(
    bg              = if (isDarkMode) Color(0xFF0F1318) else Color(0xFFF3F5F8),
    topBar          = if (isDarkMode) Color(0xFF1A2028) else Color.White,
    card            = if (isDarkMode) Color(0xFF1A2028) else Color.White,
    dialogBg        = if (isDarkMode) Color(0xFF222A33) else Color.White,
    text            = if (isDarkMode) Color(0xFFE6E9ED) else Color(0xFF1C2733),
    subText         = if (isDarkMode) Color(0xFFA0A8B3) else Color(0xFF66717E),
    divider         = if (isDarkMode) Color(0xFF2A323C) else Color(0xFFE9ECF0),
    warningRed      = if (isDarkMode) Color(0xFFEF6B6B) else Color(0xFFD32F2F),
    iconTint        = if (isDarkMode) Color(0xFF9DB4CF) else PastelNavy,
    switchUncheckedThumb = if (isDarkMode) Color(0xFFB8BEC6) else Color.White,
    switchUncheckedTrack = if (isDarkMode) Color(0xFF3A434E) else Color(0xFFD5DAE0),
    blockCard       = if (isDarkMode) Color(0xFF3A2427) else Color(0xFFFFF0F0),
    accent          = if (isDarkMode) Color(0xFF9DB4CF) else PastelNavy,
    accentContainer = if (isDarkMode) Color(0xFF26313E) else Color(0xFFE7EDF4),
    success         = if (isDarkMode) Color(0xFF6FCF97) else Color(0xFF2E7D5B),
    successContainer = if (isDarkMode) Color(0xFF1C3328) else Color(0xFFE3F2EA),
    pending         = if (isDarkMode) Color(0xFFF2B562) else Color(0xFFB26A00),
    pendingContainer = if (isDarkMode) Color(0xFF3A2E1C) else Color(0xFFFFF1DC),
    surfaceMuted    = if (isDarkMode) Color(0xFF232A33) else Color(0xFFF2F4F7),
)
