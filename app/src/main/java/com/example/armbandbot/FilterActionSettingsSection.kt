package com.heyheyon.armbandbot

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heyheyon.armbandbot.ui.*

internal val FILTER_ACTION_MODE_LABELS = linkedMapOf("DELETE" to "삭제", "BLOCK" to "차단", "HOLD" to "보류")
internal val FILTER_BLOCK_DURATION_LABELS = linkedMapOf(1 to "1시간", 6 to "6시간", 24 to "24시간 (1일)", 168 to "168시간 (7일)", 336 to "336시간 (14일)", 744 to "744시간 (31일)")

/** Keys of one filter's 개별 차단 설정, following the shared `<prefix>_...` convention. */
internal class FilterActionKeys(prefix: String) {
    val custom = "${prefix}_use_custom_action_config"
    val mode = "${prefix}_block_process_mode"
    val deleteOnly = "${prefix}_delete_only_mode"
    val duration = "${prefix}_block_duration_hours"
    val deletePost = "${prefix}_delete_post_on_block"
    val reason = "${prefix}_block_reason_text"
}

/**
 * The same "개별 차단 설정" block the other filter pages show: a per-filter switch, then 처리 방식
 * (삭제·차단·보류), and for 차단 the period, "차단 시 글/댓글 함께 삭제" and the user-facing reason.
 * Everything is saved immediately. [onChanged] runs after each save (e.g. to restart pending evidence).
 */
@Composable
internal fun FilterActionSettingsSection(
    p: SharedPreferences,
    prefix: String,
    filterName: String,
    colors: BotColorScheme,
    enabled: Boolean,
    customDefault: Boolean,
    initialMode: String,
    onChanged: () -> Unit = {},
    editorTransform: SharedPreferences.Editor.() -> SharedPreferences.Editor = { this },
) {
    val k = remember(prefix) { FilterActionKeys(prefix) }
    val baseDuration = p.getInt("block_duration_hours", 6)
    val baseDeletePost = p.getBoolean("delete_post_on_block", true)
    val baseReason = p.getString("block_reason_text", "커뮤니티 규칙 위반") ?: "커뮤니티 규칙 위반"
    var useCustom by remember(p, prefix) { mutableStateOf(p.getBoolean(k.custom, customDefault)) }
    var mode by remember(p, prefix) { mutableStateOf(initialMode) }
    var duration by remember(p, prefix) { mutableStateOf(if (p.contains(k.duration)) p.getInt(k.duration, baseDuration) else baseDuration) }
    var deletePost by remember(p, prefix) { mutableStateOf(if (p.contains(k.deletePost)) p.getBoolean(k.deletePost, true) else baseDeletePost) }
    var reason by remember(p, prefix) { mutableStateOf(p.getString(k.reason, null) ?: baseReason) }
    var modeMenu by remember { mutableStateOf(false) }
    var durationMenu by remember { mutableStateOf(false) }
    var editingReason by remember { mutableStateOf(false) }
    val active = enabled && useCustom
    fun save(edit: SharedPreferences.Editor.() -> Unit) { p.edit().apply(edit).editorTransform().apply(); onChanged() }

    Spacer(Modifier.height(12.dp))
    Text("개별 차단 설정", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = colors.accent, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = colors.card), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("개별 차단 설정 사용", fontWeight = FontWeight.Bold, color = colors.text)
                Text("끄면 기본 차단 설정을 따릅니다.", fontSize = 12.sp, color = colors.subText)
            }
            Switch(checked = useCustom, enabled = enabled, onCheckedChange = {
                useCustom = it; save { putBoolean(k.custom, it) }
            }, colors = modernSwitchColors(colors), modifier = Modifier.testTag("$prefix-custom-action"))
        }
    }
    Column(Modifier.fillMaxWidth().alpha(if (useCustom) 1f else .4f)) {
        Card(colors = CardDefaults.cardColors(containerColor = colors.card), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("처리 방식", fontWeight = FontWeight.Bold, color = colors.text)
                    Box {
                        OutlinedButton(enabled = active, onClick = { modeMenu = true }, modifier = Modifier.testTag("$prefix-action-mode")) {
                            Text(FILTER_ACTION_MODE_LABELS[mode] ?: "삭제", color = colors.text)
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = colors.accent)
                        }
                        DropdownMenu(expanded = active && modeMenu, onDismissRequest = { modeMenu = false }, modifier = Modifier.background(colors.dialogBg)) {
                            FILTER_ACTION_MODE_LABELS.forEach { (value, label) ->
                                DropdownMenuItem(text = { Text(label, color = colors.text) }, modifier = Modifier.testTag("$prefix-action-mode-$value"), onClick = {
                                    mode = value; modeMenu = false
                                    save { putString(k.mode, value).putBoolean(k.deleteOnly, value == "DELETE") }
                                })
                            }
                        }
                    }
                }
                if (mode == "BLOCK") {
                    HorizontalDivider(color = colors.divider, modifier = Modifier.padding(bottom = 8.dp))
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("차단 기간", fontWeight = FontWeight.Bold, color = colors.text)
                        Box {
                            OutlinedButton(enabled = active, onClick = { durationMenu = true }, modifier = Modifier.testTag("$prefix-block-duration")) {
                                Text(FILTER_BLOCK_DURATION_LABELS[duration] ?: "${duration}시간", color = colors.text)
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = colors.accent)
                            }
                            DropdownMenu(expanded = active && durationMenu, onDismissRequest = { durationMenu = false }, modifier = Modifier.background(colors.dialogBg)) {
                                FILTER_BLOCK_DURATION_LABELS.forEach { (hours, label) ->
                                    DropdownMenuItem(text = { Text(label, color = colors.text) }, onClick = {
                                        duration = hours; durationMenu = false; save { putInt(k.duration, hours) }
                                    })
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = colors.divider, modifier = Modifier.padding(bottom = 8.dp))
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("차단 시 글/댓글 함께 삭제", color = colors.text)
                        Switch(enabled = active, checked = deletePost, onCheckedChange = {
                            deletePost = it; save { putBoolean(k.deletePost, it) }
                        }, colors = modernSwitchColors(colors), modifier = Modifier.testTag("$prefix-delete-post"))
                    }
                }
            }
        }
        if (mode == "BLOCK") {
            ReadOnlyTextCard("차단 사유 (유저에게 표시됨)", reason, colors, enabled = active, modifier = Modifier.testTag("$prefix-block-reason")) { editingReason = true }
        }
    }
    if (editingReason) {
        var draft by remember { mutableStateOf(reason) }
        AlertDialog(
            onDismissRequest = { editingReason = false }, modifier = Modifier.testTag("$prefix-block-reason-dialog"),
            containerColor = colors.dialogBg, titleContentColor = colors.text, textContentColor = colors.text,
            title = { Text("$filterName 차단 사유 설정", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(draft, { draft = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = colors.text, unfocusedTextColor = colors.text,
                        focusedBorderColor = colors.accent, unfocusedBorderColor = colors.divider, cursorColor = colors.accent))
            },
            confirmButton = {
                Button(onClick = {
                    reason = draft.trim().ifBlank { baseReason }
                    save { if (draft.isBlank()) remove(k.reason) else putString(k.reason, draft.trim()) }
                    editingReason = false
                }, colors = ButtonDefaults.buttonColors(containerColor = PastelNavy)) { Text("저장", color = androidx.compose.ui.graphics.Color.White) }
            },
            dismissButton = { TextButton(onClick = { editingReason = false }) { Text("취소", color = colors.subText) } },
        )
    }
}
