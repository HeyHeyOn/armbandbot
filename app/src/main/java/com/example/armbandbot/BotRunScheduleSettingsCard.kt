package com.heyheyon.armbandbot

import android.app.TimePickerDialog
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One observable reference; do not expand the large detail-screen closure's captures. */
internal class BotRunScheduleEditorState(initial: BotRunScheduleLoadResult, private val persist: (BotRunSchedule) -> Unit) {
    var enabled by mutableStateOf(when(initial) {
        is BotRunScheduleLoadResult.Valid -> initial.schedule.enabled
        is BotRunScheduleLoadResult.Error -> initial.enabled
    }); private set
    var legacyEditor by mutableStateOf((initial as? BotRunScheduleLoadResult.Valid)?.legacyEditor); private set
    var windows by mutableStateOf((initial as? BotRunScheduleLoadResult.Valid)?.schedule?.windows ?: emptyList()); private set
    var error by mutableStateOf((initial as? BotRunScheduleLoadResult.Error)?.message); private set
    var needsRepair by mutableStateOf(initial is BotRunScheduleLoadResult.Error); private set

    private fun save(nextEnabled: Boolean = enabled, next: List<BotRunWindow> = windows) {
        if (needsRepair) return
        try {
            val schedule = BotRunSchedule(nextEnabled, next)
            persist(schedule)
            enabled = nextEnabled
            windows = next
            error = null
        } catch (e: IllegalArgumentException) { error = e.message ?: "시간대 설정을 확인하세요." }
    }
    fun changeEnabled(value: Boolean) {
        if (legacyEditor != null) { error = "시작과 종료 시각을 다르게 수정한 뒤 켜세요."; return }
        save(nextEnabled = value)
    }
    fun addWindow() {
        if (legacyEditor == null && windows.size < 64) save(next = windows + BotRunWindow(540, 1080))
    }
    fun removeWindow(index: Int) {
        if (windows.size > 1) save(next = windows.filterIndexed { i, _ -> i != index })
    }
    fun editWindow(index: Int, start: Int, end: Int) {
        if (start == end) { error = "시작과 종료 시각은 달라야 합니다."; return }
        try {
            save(next = windows.toMutableList().apply { this[index] = BotRunWindow(start, end) })
            if (error == null) legacyEditor = null
        }
        catch (e: IllegalArgumentException) { error = e.message }
    }
    fun repair() {
        // Explicit replacement only: merely opening this screen never overwrites corrupt settings.
        needsRepair = false
        save(next = listOf(BotRunWindow(540,1080)))
        if (windows.isEmpty()) needsRepair = true
    }
}

@Composable
internal fun BotRunScheduleSettingsCard(preferences: SharedPreferences, cardColor: Color, textColor: Color) {
    val state = remember(preferences) { BotRunScheduleEditorState(loadBotRunSchedule(preferences)) { saveBotRunSchedule(preferences, it) } }
    BotRunScheduleSettingsCard(state, cardColor, textColor)
}

@Composable
internal fun BotRunScheduleSettingsCard(state: BotRunScheduleEditorState, cardColor: Color = MaterialTheme.colorScheme.surface, textColor: Color = MaterialTheme.colorScheme.onSurface) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("schedule-card"), colors = CardDefaults.cardColors(containerColor = cardColor)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("작동 시간대", Modifier.weight(1f), fontWeight = FontWeight.Bold, color = textColor)
                Switch(checked = state.enabled, onCheckedChange = state::changeEnabled, enabled = !state.needsRepair && state.legacyEditor == null, modifier = Modifier.testTag("schedule-enabled"))
            }
            Text("설정한 시간대 중 하나에 해당하면 작동합니다. 끄더라도 시간대 목록은 유지됩니다.", color = textColor)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("schedule-error")) }
            if (state.needsRepair) {
                Text("저장된 시간대가 손상되었습니다. 복구 전에는 설정을 사용할 수 없습니다.", color = textColor)
                TextButton(onClick = state::repair, modifier = Modifier.testTag("schedule-repair")) { Text("09:00–18:00 시간대로 복구") }
            } else {
                state.legacyEditor?.let {
                    Text("이전 시간대의 시작과 종료가 같습니다. 시각을 수정한 뒤 켤 수 있습니다.", color = textColor)
                    ScheduleWindowRow(state, 0, it.startMinute, it.endMinute, textColor)
                } ?: state.windows.forEachIndexed { index, window ->
                    ScheduleWindowRow(state, index, window.startMinuteOfDay, window.endMinuteOfDay, textColor)
                }
                TextButton(onClick = state::addWindow, enabled = state.legacyEditor == null && state.windows.size < 64, modifier = Modifier.testTag("schedule-add")) { Text("시간대 추가") }
                Text("${state.windows.size}/64 시간대 · 최대 64개", color = textColor)
            }
        }
    }
}

@Composable
private fun ScheduleWindowRow(state: BotRunScheduleEditorState, index: Int, start: Int, end: Int, textColor: Color) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().testTag("schedule-row-$index")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("시간대${index + 1}", Modifier.weight(1f), color = textColor)
            TextButton(onClick = { state.removeWindow(index) }, enabled = state.windows.size > 1, modifier = Modifier.testTag("schedule-remove-$index")) { Text("삭제") }
        }
        Text(scheduleRangeLabel(start, end), color = textColor)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                TimePickerDialog(context, { _, h, m -> state.editWindow(index, h * 60 + m, end) }, start / 60, start % 60, true).show()
            }, modifier = Modifier.testTag("schedule-start-$index")) { Text("시작 ${formatMinuteOfDay(start)}") }
            OutlinedButton(onClick = {
                TimePickerDialog(context, { _, h, m -> state.editWindow(index, start, h * 60 + m) }, end / 60, end % 60, true).show()
            }, modifier = Modifier.testTag("schedule-end-$index")) { Text("종료 ${formatMinuteOfDay(end)}") }
        }
    }
}
