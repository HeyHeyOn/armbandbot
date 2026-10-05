package com.heyheyon.armbandbot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One header for every bot detail sub-page: back button, title and an optional trailing action. */
@Composable
internal fun SettingsDetailHeader(title: String, colors: BotColorScheme, onBack: () -> Unit,
    backModifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().background(colors.topBar)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = backModifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.ArrowBack, "뒤로", tint = colors.accent)
            }
            Spacer(Modifier.width(4.dp))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = colors.text, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            trailing()
            Spacer(Modifier.width(4.dp))
        }
        HorizontalDivider(color = colors.divider)
    }
}
