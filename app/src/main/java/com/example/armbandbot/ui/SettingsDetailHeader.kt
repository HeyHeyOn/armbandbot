package com.heyheyon.armbandbot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One header for the existing filters and management automation detail pages. */
@Composable
internal fun SettingsDetailHeader(title: String, colors: BotColorScheme, onBack: () -> Unit,
    backModifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().background(colors.topBar).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.ArrowBack, "뒤로", tint = PastelNavy,
            modifier = backModifier.clickable(onClick = onBack).padding(end = 16.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.text, modifier = Modifier.weight(1f))
        trailing()
    }
}
