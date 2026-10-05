package com.heyheyon.armbandbot

import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.heyheyon.armbandbot.ui.*

@Composable
internal fun BotRunScheduleSettingsScreen(preferences: SharedPreferences, colors: BotColorScheme, onBack: () -> Unit) {
    val state = remember(preferences) { BotRunScheduleEditorState(loadBotRunSchedule(preferences)) { saveBotRunSchedule(preferences, it) } }
    BotRunScheduleSettingsScreen(state, colors, onBack)
}

@Composable
internal fun BotRunScheduleSettingsScreen(state: BotRunScheduleEditorState, colors: BotColorScheme, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(colors.bg).testTag("schedule-page")) {
        SettingsDetailHeader("작동 시간대", colors, onBack, backModifier = Modifier.testTag("schedule-back"))
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { BotRunScheduleSettingsPage(state, colors) }
    }
}

@Composable
internal fun BotRunScheduleSettingsCard(preferences: SharedPreferences, colors: BotColorScheme, onOpenSettings: () -> Unit) {
    val state = remember(preferences) { BotRunScheduleEditorState(loadBotRunSchedule(preferences)) { saveBotRunSchedule(preferences, it) } }
    BotRunScheduleSettingsCard(state, colors, onOpenSettings)
}

@Composable
internal fun BotRunScheduleSettingsCard(state: BotRunScheduleEditorState, colors: BotColorScheme, onOpenSettings: (() -> Unit)? = null) {
    var editing by remember { mutableStateOf(false) }
    if (editing) {
        BotRunScheduleSettingsScreen(state, colors) { editing = false }
    } else {
        ModernSettingsBlock(
            title = "작동 시간대",
            subtitle = if (state.enabled) "시간대 ${state.windows.size}개 적용 중" else "꺼져 있으면 시간 제한 없이 작동",
            icon = Icons.Filled.Schedule, colors = colors,
            modifier = Modifier.testTag("schedule-card").clickable { onOpenSettings?.invoke() ?: run { editing = true } },
            trailing = {
                Row {
                    Box(Modifier.width(1.dp).height(40.dp).background(colors.divider).testTag("schedule-divider"))
                    Spacer(Modifier.width(16.dp))
                    ModernSettingsSwitch(checked = state.enabled, onCheckedChange = state::changeEnabled,
                        colors = colors, enabled = !state.needsRepair && state.legacyEditor == null,
                        modifier = Modifier.testTag("schedule-enabled"))
                }
            },
        )
    }
}
