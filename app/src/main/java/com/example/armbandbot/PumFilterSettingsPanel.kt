package com.heyheyon.armbandbot

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heyheyon.armbandbot.ui.LocalIsDarkMode
import com.heyheyon.armbandbot.ui.PastelNavy

private fun SharedPreferences.readNormalizedPumSettings(): NormalizedPumSettings = normalizePumSettings(
    processMode = all["pum_block_process_mode"],
    blockDurationHours = all["pum_block_duration_hours"],
    legacyDeleteOnly = all["pum_delete_only_mode"] == true,
    processModePresent = contains("pum_block_process_mode"),
)

@Composable
fun PumFilterSettingsPanel(
    botId: String,
    onMasterEnabledChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val botPref = remember(botId) {
        context.getSharedPreferences("bot_prefs_$botId", Context.MODE_PRIVATE)
    }
    val isDarkMode = LocalIsDarkMode.current
    val cardColor = if (isDarkMode) Color(0xFF1E2329) else Color.White
    val dialogBgColor = if (isDarkMode) Color(0xFF2C323A) else Color.White
    val textColor = if (isDarkMode) Color(0xFFE0E0E0) else Color(0xFF2C3E50)
    val subTextColor = if (isDarkMode) Color(0xFFAAAEB3) else Color.Gray
    val dividerColor = if (isDarkMode) Color(0xFF333333) else Color(0xFFEEEEEE)
    val switchColors = SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = PastelNavy,
        uncheckedThumbColor = if (isDarkMode) Color.LightGray else Color.White,
        uncheckedTrackColor = if (isDarkMode) Color(0xFF555555) else Color.LightGray,
    )

    var masterEnabled by remember(botId) {
        mutableStateOf(botPref.getBoolean("is_pum_source_filter_mode", false))
    }
    var blockAllPosts by remember(botId) {
        mutableStateOf(botPref.getBoolean("pum_block_all_posts", false))
    }
    var recheckEveryCycle by remember(botId) {
        mutableStateOf(botPref.getBoolean("pum_recheck_every_cycle", false))
    }
    var useCustomAction by remember(botId) {
        mutableStateOf(botPref.getBoolean("pum_use_custom_action_config", false))
    }
    val initialPumSettings = remember(botId) { botPref.readNormalizedPumSettings() }
    var processMode by remember(botId) { mutableStateOf(initialPumSettings.processMode) }
    var blockDurationHours by remember(botId) {
        mutableStateOf(initialPumSettings.blockDurationHours)
    }
    var deletePostOnBlock by remember(botId) {
        mutableStateOf(
            if (botPref.contains("pum_delete_post_on_block")) {
                botPref.getBoolean("pum_delete_post_on_block", true)
            } else {
                botPref.getBoolean("delete_post_on_block", true)
            }
        )
    }
    var blockReason by remember(botId) {
        mutableStateOf(botPref.getString("pum_block_reason_text", "") ?: "")
    }
    var processMenuExpanded by remember { mutableStateOf(false) }
    var durationMenuExpanded by remember { mutableStateOf(false) }

    val durationOptions = linkedMapOf(
        1 to "1시간",
        6 to "6시간",
        24 to "24시간 (1일)",
        168 to "168시간 (7일)",
        336 to "336시간 (14일)",
        744 to "744시간 (31일)",
    )
    val childAlpha = if (masterEnabled) 1f else 0.4f

    Column {
        Card(
            colors = CardDefaults.cardColors(containerColor = cardColor),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            PumSwitchRow(
                title = "펌 필터 기능 사용",
                description = "펌 게시글의 원문까지 검사합니다. 원문을 추가로 불러오므로 외부·원문 요청이 늘 수 있습니다. 기능을 꺼도 '펌 게시글 모두 차단'과 개별 차단 설정은 유지되며, 다시 켜면 그대로 적용됩니다.",
                checked = masterEnabled,
                enabled = true,
                textColor = textColor,
                subTextColor = subTextColor,
                switchColors = switchColors,
                onCheckedChange = { enabled ->
                    masterEnabled = enabled
                    botPref.edit().putBoolean("is_pum_source_filter_mode", enabled).apply()
                    onMasterEnabledChange(enabled)
                },
            )
        }

        Text(
            "펌 게시글 검사",
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = PastelNavy,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp).alpha(childAlpha),
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = cardColor),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).alpha(childAlpha),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                PumSwitchRow(
                    title = "펌 게시글 모두 차단 (선택)",
                    description = "필요할 때만 켜는 선택 설정입니다. 현재 목록의 구조적으로 확인된 펌 글은 내용이 바뀌지 않아도 다시 검사해 모두 차단합니다. 제목에 펌 표시를 직접 입력한 것만으로는 펌 글로 판정하지 않습니다.",
                    checked = blockAllPosts,
                    enabled = masterEnabled,
                    textColor = textColor,
                    subTextColor = subTextColor,
                    switchColors = switchColors,
                    onCheckedChange = { enabled ->
                        blockAllPosts = enabled
                        botPref.edit().putBoolean("pum_block_all_posts", enabled).apply()
                    },
                )
                HorizontalDivider(color = dividerColor)
                PumSwitchRow(
                    title = "펌 글을 매 사이클마다 검사",
                    description = "설정한 현재 목록 범위 안의 펌 글만 추적하며, 매 사이클 원문을 다시 검사합니다.",
                    checked = recheckEveryCycle,
                    enabled = masterEnabled,
                    textColor = textColor,
                    subTextColor = subTextColor,
                    switchColors = switchColors,
                    onCheckedChange = { enabled ->
                        recheckEveryCycle = enabled
                        botPref.edit().putBoolean("pum_recheck_every_cycle", enabled).apply()
                    },
                )
            }
        }

        Text(
            "개별 차단 설정",
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = PastelNavy,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp).alpha(childAlpha),
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = cardColor),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).alpha(childAlpha),
        ) {
            PumSwitchRow(
                title = "개별 차단 설정 사용",
                description = "끄면 차단 기본 설정을 따릅니다.",
                checked = useCustomAction,
                enabled = masterEnabled,
                textColor = textColor,
                subTextColor = subTextColor,
                switchColors = switchColors,
                onCheckedChange = { enabled ->
                    useCustomAction = enabled
                    botPref.edit().putBoolean("pum_use_custom_action_config", enabled).apply()
                },
            )
        }

        val actionSettingsEnabled = masterEnabled && useCustomAction
        Card(
            colors = CardDefaults.cardColors(containerColor = cardColor),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                .alpha(if (actionSettingsEnabled) 1f else 0.4f),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = if (processMode == PUM_PROCESS_MODE_BLOCK) 12.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("처리 방식", fontWeight = FontWeight.Bold, color = textColor)
                    Box {
                        OutlinedButton(
                            enabled = actionSettingsEnabled,
                            onClick = { processMenuExpanded = true },
                        ) {
                            Text(
                                when (processMode) {
                                    PUM_PROCESS_MODE_DELETE -> "삭제"
                                    PUM_PROCESS_MODE_HOLD -> "보류"
                                    else -> "차단"
                                },
                                color = textColor,
                            )
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy)
                        }
                        DropdownMenu(
                            expanded = processMenuExpanded,
                            onDismissRequest = { processMenuExpanded = false },
                            modifier = Modifier.background(dialogBgColor),
                        ) {
                            listOf(
                                PUM_PROCESS_MODE_DELETE to "삭제",
                                PUM_PROCESS_MODE_BLOCK to "차단",
                                PUM_PROCESS_MODE_HOLD to "보류",
                            ).forEach { (mode, label) ->
                                DropdownMenuItem(
                                    text = { Text(label, color = textColor) },
                                    onClick = {
                                        processMode = mode
                                        botPref.edit()
                                            .putString("pum_block_process_mode", mode)
                                            .putBoolean("pum_delete_only_mode", mode == PUM_PROCESS_MODE_DELETE)
                                            .apply()
                                        processMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }

                if (processMode == PUM_PROCESS_MODE_BLOCK) {
                    HorizontalDivider(color = dividerColor, modifier = Modifier.padding(bottom = 8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("차단 기간", fontWeight = FontWeight.Bold, color = textColor)
                        Box {
                            OutlinedButton(
                                enabled = actionSettingsEnabled,
                                onClick = { durationMenuExpanded = true },
                            ) {
                                Text(durationOptions[blockDurationHours] ?: "${blockDurationHours}시간", color = textColor)
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy)
                            }
                            DropdownMenu(
                                expanded = durationMenuExpanded,
                                onDismissRequest = { durationMenuExpanded = false },
                                modifier = Modifier.background(dialogBgColor),
                            ) {
                                durationOptions.forEach { (hours, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label, color = textColor) },
                                        onClick = {
                                            blockDurationHours = hours
                                            botPref.edit().putInt("pum_block_duration_hours", hours).apply()
                                            durationMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = dividerColor, modifier = Modifier.padding(bottom = 8.dp))
                    PumSwitchRow(
                        title = "차단 시 글/댓글 함께 삭제",
                        description = null,
                        checked = deletePostOnBlock,
                        enabled = actionSettingsEnabled,
                        textColor = textColor,
                        subTextColor = subTextColor,
                        switchColors = switchColors,
                        onCheckedChange = { enabled ->
                            deletePostOnBlock = enabled
                            botPref.edit().putBoolean("pum_delete_post_on_block", enabled).apply()
                        },
                    )
                }
            }
        }

        if (processMode == PUM_PROCESS_MODE_BLOCK) {
            Card(
                colors = CardDefaults.cardColors(containerColor = cardColor),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    .alpha(if (actionSettingsEnabled) 1f else 0.4f),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("차단 사유 (유저에게 표시됨)", fontWeight = FontWeight.Bold, color = textColor)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("비워 두면 차단 기본 설정의 사유를 사용합니다.", fontSize = 12.sp, color = subTextColor)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = blockReason,
                        onValueChange = { value ->
                            blockReason = value
                            val editor = botPref.edit()
                            if (value.isBlank()) editor.remove("pum_block_reason_text")
                            else editor.putString("pum_block_reason_text", value)
                            editor.apply()
                        },
                        enabled = actionSettingsEnabled,
                        placeholder = { Text("차단 기본 설정 사유 사용") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = textColor,
                            unfocusedTextColor = textColor,
                            disabledTextColor = textColor,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun PumSwitchRow(
    title: String,
    description: String?,
    checked: Boolean,
    enabled: Boolean,
    textColor: Color,
    subTextColor: Color,
    switchColors: androidx.compose.material3.SwitchColors,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = textColor)
            if (description != null) {
                Text(description, fontSize = 12.sp, color = subTextColor)
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = null,
            colors = switchColors,
        )
    }
}
