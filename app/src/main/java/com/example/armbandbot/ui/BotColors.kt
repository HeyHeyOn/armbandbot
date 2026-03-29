package com.example.armbandbot.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PastelNavy = Color(0xFF4A6583)
val PastelNavyLight = Color(0xFFE8ECEF)
val DarkTerminal = Color(0xFF1E2329)

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
    val blockCard: Color
)

@Composable
fun botColors(isDarkMode: Boolean) = BotColorScheme(
    bg              = if (isDarkMode) Color(0xFF121212) else Color(0xFFF8F9FA),
    topBar          = if (isDarkMode) Color(0xFF1E2329) else Color.White,
    card            = if (isDarkMode) Color(0xFF1E2329) else Color.White,
    dialogBg        = if (isDarkMode) Color(0xFF2C323A) else Color.White,
    text            = if (isDarkMode) Color(0xFFE0E0E0) else Color(0xFF2C3E50),
    subText         = if (isDarkMode) Color(0xFFAAAEB3) else Color.DarkGray,
    divider         = if (isDarkMode) Color(0xFF333333) else Color(0xFFEEEEEE),
    warningRed      = if (isDarkMode) Color(0xFFEF5350) else Color(0xFFD32F2F),
    iconTint        = if (isDarkMode) Color(0xFF90A4AE) else PastelNavy,
    switchUncheckedThumb = if (isDarkMode) Color.LightGray else Color.White,
    switchUncheckedTrack = if (isDarkMode) Color(0xFF555555) else Color.LightGray,
    blockCard       = if (isDarkMode) Color(0xFF3E2723) else Color(0xFFFFF0F0)
)
