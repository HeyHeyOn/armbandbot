package com.heyheyon.armbandbot

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.heyheyon.armbandbot.ui.BotColorScheme
import com.heyheyon.armbandbot.ui.LocalIsDarkMode
import com.heyheyon.armbandbot.ui.botColors

internal fun dashboardRecordFilterSummary(gallery: String, scope: DashboardRecordScope, options: List<DashboardScopeOption>): String? = buildList {
    if (gallery != "ALL") add(gallery)
    if (scope != DashboardRecordScope.All) add(options.firstOrNull { it.scope == scope }?.label ?: (scope as DashboardRecordScope.Exact).scopeId)
}.takeIf { it.isNotEmpty() }?.joinToString(", ")

internal class DashboardRecordFilterUiState {
    var open by mutableStateOf(false)
}

@Composable
internal fun DashboardRecordFilterDialog(
    gallery: String,
    scope: DashboardRecordScope,
    galleries: List<String>,
    options: List<DashboardScopeOption>,
    scopeEditable: Boolean,
    onDismiss: () -> Unit,
    onApply: (String, DashboardRecordScope) -> Unit,
) {
    val colors = botColors(LocalIsDarkMode.current)
    var draftGallery by remember { mutableStateOf(gallery) }
    var draftScope by remember { mutableStateOf(scope) }
    AlertDialog(
        modifier = Modifier.testTag("record-filter-dialog"),
        onDismissRequest = onDismiss,
        containerColor = colors.dialogBg,
        titleContentColor = colors.text,
        textContentColor = colors.text,
        title = { Text("기록 필터") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("갤러리")
                Column(Modifier.selectableGroup()) {
                    (listOf("ALL") + galleries).distinct().forEach { id ->
                        RecordFilterRadio(if (id == "ALL") "전체 갤러리" else id,
                            "record-filter-gallery-$id", draftGallery == id, colors) { draftGallery = id }
                    }
                }
                Text("검사 범위")
                if (scopeEditable) Column(Modifier.selectableGroup()) {
                    options.forEach { option ->
                        val id = when (val value = option.scope) {
                            DashboardRecordScope.All -> "ALL"
                            is DashboardRecordScope.Exact -> value.scopeId
                        }
                        RecordFilterRadio(option.label, "record-filter-scope-$id", draftScope == option.scope, colors) { draftScope = option.scope }
                    }
                } else Text(options.firstOrNull { it.scope == scope }?.label ?: (scope as DashboardRecordScope.Exact).scopeId)
            }
        },
        confirmButton = { TextButton(modifier = Modifier.testTag("record-filter-apply"), colors = ButtonDefaults.textButtonColors(contentColor = colors.iconTint), onClick = { onApply(draftGallery, if (scopeEditable) draftScope else scope) }) { Text("적용") } },
        dismissButton = { TextButton(modifier = Modifier.testTag("record-filter-cancel"), colors = ButtonDefaults.textButtonColors(contentColor = colors.subText), onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun RecordFilterRadio(label: String, tag: String, selected: Boolean, colors: BotColorScheme, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag).selectable(selected, role = Role.RadioButton, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = colors.iconTint, unselectedColor = colors.subText))
        Text(label, color = colors.text)
    }
}
