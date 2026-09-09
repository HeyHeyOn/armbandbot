package com.heyheyon.armbandbot

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.heyheyon.armbandbot.ui.*

/** A DB is a stable logical bot scope. Enabling never copies or resets its rows. */
@Composable
internal fun IndependentDbSettingsCard(botId: String, preferences: SharedPreferences, running: Boolean, colors: BotColorScheme) {
    require(botId.isNotBlank() && botId != GLOBAL_SCAN_SCOPE)
    var enabled by remember(preferences) { mutableStateOf(preferences.getBoolean("independent_scan_state_enabled", false)) }
    ModernSettingsBlock(
        title = "개별 DB 사용",
        subtitle = "검사 기록을 다른 봇과 분리하여 처리합니다.",
        icon = Icons.Filled.Storage, colors = colors,
        modifier = Modifier.testTag("independent-db-card"),
        trailing = {
            Row {
                Box(Modifier.width(1.dp).height(40.dp).background(colors.divider).testTag("independent-db-divider"))
                Spacer(Modifier.width(16.dp))
            ModernSettingsSwitch(checked = enabled, colors = colors,
                enabled = !running && !preferences.getBoolean("is_running", false),
                modifier = Modifier.testTag("independent-db-enabled"),
                onCheckedChange = { next ->
                    GlobalBotState.withDatabaseMaintenanceLock {
                        if (!running && !preferences.getBoolean("is_running", false) && !BotService.hasUnfinishedDatabaseWork(botId)) {
                            preferences.edit().putBoolean("independent_scan_state_enabled", next).apply {
                                if (next) putBoolean("independent_scan_state_initialized", true)
                            }.apply()
                            enabled = next
                        }
                    }
                })
            }
        },
    )
}
