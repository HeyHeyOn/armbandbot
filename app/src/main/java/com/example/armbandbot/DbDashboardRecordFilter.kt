package com.heyheyon.armbandbot

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
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

internal fun dashboardRecordFilterSummary(gallery: String, scope: DashboardRecordScope, options: List<DashboardScopeOption>): String? =
    dashboardRecordFilterSummary(if (gallery == "ALL") null else setOf(gallery), scope, options)

internal fun dashboardRecordFilterSummary(galleries: Set<String>?, scope: DashboardRecordScope, options: List<DashboardScopeOption>): String? = buildList {
    galleries?.let { addAll(it.sorted()); if (it.isEmpty()) add("갤러리 선택 없음") }
    if (scope != DashboardRecordScope.All) {
        val ids = when (scope) {
            is DashboardRecordScope.Exact -> setOf(scope.scopeId)
            is DashboardRecordScope.Selected -> scope.scopeIds
            DashboardRecordScope.All -> emptySet()
        }
        if (ids.isEmpty()) add("DB 선택 없음")
        ids.forEach { id -> add(options.firstOrNull { it.scope == DashboardRecordScope.Exact(id) }?.label ?: id) }
    }
}.takeIf { it.isNotEmpty() }?.joinToString(", ")

internal class DashboardRecordFilterUiState(initialScope: DashboardRecordScope) {
    var open by mutableStateOf(false)
    var recordScope by mutableStateOf(initialScope)
    var retainedScopeIds by mutableStateOf<Set<String>>(emptySet())
    var galleries by mutableStateOf<List<String>>(emptyList())
    var selectedGall by mutableStateOf<Set<String>?>(null)
    var showResetChooser by mutableStateOf(false)
    var frozenReset by mutableStateOf<FrozenDashboardReset?>(null)
}

internal fun selectedDashboardScope(ids: Set<String>): DashboardRecordScope =
    if (ids.size == 1) DashboardRecordScope.Exact(ids.single()) else DashboardRecordScope.Selected(ids.toSet())

@Composable
internal fun DashboardRecordFilterDialog(
    gallery: Set<String>?,
    scope: DashboardRecordScope,
    galleries: List<String>,
    options: List<DashboardScopeOption>,
    scopeEditable: Boolean,
    onDismiss: () -> Unit,
    onApply: (Set<String>?, DashboardRecordScope) -> Unit,
) {
    val colors = botColors(LocalIsDarkMode.current)
    val galleryInventory = galleries.toSet() + gallery.orEmpty()
    val scopeInventory = options.mapNotNull { (it.scope as? DashboardRecordScope.Exact)?.scopeId }.toSet()
    var draftGallery by remember { mutableStateOf(gallery?.toSet()) }
    var draftScope by remember { mutableStateOf(scope) }
    AlertDialog(
        modifier = Modifier.testTag("record-filter-dialog"), onDismissRequest = onDismiss,
        containerColor = colors.dialogBg, titleContentColor = colors.text, textContentColor = colors.text,
        title = { Text("기록 필터") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("갤러리", color = colors.text)
                RecordFilterCheckbox("전체 갤러리", "record-filter-gallery-ALL", draftGallery == null, colors) { draftGallery = null }
                galleryInventory.sorted().forEach { id ->
                    RecordFilterCheckbox(id, "record-filter-gallery-$id", draftGallery?.contains(id) ?: true, colors) {
                        val current = draftGallery ?: galleryInventory
                        draftGallery = if (id in current) current - id else current + id
                    }
                }
                Text("DB", color = colors.text)
                if (scopeEditable) {
                    RecordFilterCheckbox("전체", "record-filter-scope-ALL", draftScope == DashboardRecordScope.All, colors) { draftScope = DashboardRecordScope.All }
                    options.forEach { option ->
                        val id = (option.scope as? DashboardRecordScope.Exact)?.scopeId ?: return@forEach
                        RecordFilterCheckbox(option.label, "record-filter-scope-$id", draftScope.includes(id), colors) {
                            val current = when (val value = draftScope) {
                                DashboardRecordScope.All -> scopeInventory
                                is DashboardRecordScope.Exact -> setOf(value.scopeId)
                                is DashboardRecordScope.Selected -> value.scopeIds
                            }
                            draftScope = selectedDashboardScope(if (id in current) current - id else current + id)
                        }
                    }
                } else Text(dashboardRecordFilterSummary(null, scope, options).orEmpty(), color = colors.text)
            }
        },
        confirmButton = { TextButton(modifier = Modifier.testTag("record-filter-apply"), colors = ButtonDefaults.textButtonColors(contentColor = colors.iconTint), onClick = { onApply(draftGallery?.toSet(), if (scopeEditable) draftScope else scope) }) { Text("적용") } },
        dismissButton = { TextButton(modifier = Modifier.testTag("record-filter-cancel"), colors = ButtonDefaults.textButtonColors(contentColor = colors.subText), onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
internal fun RecordFilterCheckbox(label: String, tag: String, selected: Boolean, colors: BotColorScheme, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag).toggleable(selected, role = Role.Checkbox, onValueChange = { onClick() }), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = selected, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = colors.iconTint, uncheckedColor = colors.subText, checkmarkColor = colors.dialogBg))
        Text(label, color = colors.text)
    }
}
