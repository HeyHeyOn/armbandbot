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
import com.heyheyon.armbandbot.ui.ReadOnlyTextCard
import com.heyheyon.armbandbot.ui.botColors

private fun SharedPreferences.readNormalizedPumSettings(): NormalizedPumSettings = normalizePumSettings(
    processMode = all["pum_block_process_mode"],
    blockDurationHours = all["pum_block_duration_hours"],
    legacyDeleteOnly = all["pum_delete_only_mode"] == true,
    processModePresent = contains("pum_block_process_mode"),
)

@Composable
fun PumFilterSettingsPanel(
    botId: String,
    blockReason: String,
    onFilterEnabledChange: (Boolean) -> Unit = {},
    onEditBlockReason: () -> Unit,
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

    var blockAllPosts by remember(botId) {
        mutableStateOf(botPref.getBoolean("pum_block_all_posts", false))
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
    val childAlpha = if (blockAllPosts) 1f else 0.4f

    Column {
        Card(
            colors = CardDefaults.cardColors(containerColor = cardColor),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                PumSwitchRow(
                    title = "펌 게시글 모두 차단",
                    description = "구조적으로 확인된 펌 게시글을 내용과 관계없이 차단합니다.",
                    checked = blockAllPosts,
                    enabled = true,
                    textColor = textColor,
                    subTextColor = subTextColor,
                    switchColors = switchColors,
                    onCheckedChange = { enabled ->
                        blockAllPosts = enabled
                        botPref.edit().putBoolean("pum_block_all_posts", enabled).apply()
                        onFilterEnabledChange(enabled)
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
                description = null,
                checked = useCustomAction,
                enabled = blockAllPosts,
                textColor = textColor,
                subTextColor = subTextColor,
                switchColors = switchColors,
                onCheckedChange = { enabled ->
                    useCustomAction = enabled
                    botPref.edit().putBoolean("pum_use_custom_action_config", enabled).apply()
                },
            )
        }

        val actionSettingsEnabled = blockAllPosts && useCustomAction
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
            Box(modifier = Modifier.alpha(if (actionSettingsEnabled) 1f else 0.4f)) {
                ReadOnlyTextCard(
                    "차단 사유 (유저에게 표시됨)",
                    blockReason,
                    botColors(isDarkMode),
                ) {
                    if (actionSettingsEnabled) onEditBlockReason()
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
