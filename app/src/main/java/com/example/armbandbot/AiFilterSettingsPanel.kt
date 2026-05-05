package com.heyheyon.armbandbot

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heyheyon.armbandbot.ui.LocalIsDarkMode
import com.heyheyon.armbandbot.ui.PastelNavy
import com.heyheyon.armbandbot.ui.PastelNavyLight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiFilterSettingsPanel(botId: String) {
    val context = LocalContext.current
    val botPref = remember(botId) { context.getSharedPreferences("bot_prefs_$botId", Context.MODE_PRIVATE) }
    val isDarkMode = LocalIsDarkMode.current
    val cardColor = if (isDarkMode) Color(0xFF1E2329) else Color.White
    val dialogBgColor = if (isDarkMode) Color(0xFF2C323A) else Color.White
    val textColor = if (isDarkMode) Color(0xFFE0E0E0) else Color(0xFF2C3E50)
    val subTextColor = if (isDarkMode) Color(0xFFAAAEB3) else Color.Gray
    val dividerColor = if (isDarkMode) Color(0xFF333333) else Color(0xFFEEEEEE)

    val buttonContainerColor = if (isDarkMode) Color(0xFF37474F) else PastelNavyLight
    val buttonContentColor = if (isDarkMode) Color.White else PastelNavy

    var isEnabled by remember { mutableStateOf(botPref.getBoolean("is_ai_filter_mode", false)) }
    val providerOptions = linkedMapOf(
        "gemini_direct" to "Gemini direct",
        "groq" to "Groq",
        "lm_studio" to "LM Studio (로컬 LLM)",
        "openai_compatible" to "OpenAI-compatible",
        "custom_openai" to "기타(OpenAI 호환 직접 입력)",
    )
    var providerExpanded by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf(botPref.getString("ai_filter_provider", "gemini_direct") ?: "gemini_direct") }
    var customProviderLabel by remember { mutableStateOf(botPref.getString("ai_filter_provider_custom_label", "") ?: "") }
    var endpoint by remember { mutableStateOf(botPref.getString("ai_filter_endpoint", "") ?: "") }
    var apiKey by remember { mutableStateOf(botPref.getString("ai_filter_api_key", "") ?: "") }
    var model by remember { mutableStateOf(botPref.getString("ai_filter_model", "gemini-2.5-flash") ?: "gemini-2.5-flash") }
    var useCustomEndpoint by remember { mutableStateOf(botPref.getBoolean("ai_filter_use_custom_endpoint", false)) }
    var useCustomModel by remember { mutableStateOf(botPref.getBoolean("ai_filter_use_custom_model", false)) }
    var prompt by remember { mutableStateOf(botPref.getString("ai_filter_user_prompt", "") ?: "") }
    var maxPosts by remember { mutableStateOf(botPref.safeInt("ai_filter_batch_max_posts", 5).toString()) }
    var maxWaitSec by remember { mutableStateOf(botPref.safeInt("ai_filter_batch_max_wait_sec", 5).toString()) }
    var maxWeight by remember { mutableStateOf(botPref.safeInt("ai_filter_batch_max_weight", 20000).toString()) }
    var timeoutSec by remember { mutableStateOf(botPref.safeInt("ai_filter_timeout_sec", 20).toString()) }

    var useCustomAction by remember { mutableStateOf(botPref.getBoolean("ai_use_custom_action_config", false)) }
    var actionExpanded by remember { mutableStateOf(false) }
    var durationExpanded by remember { mutableStateOf(false) }
    var actionMode by remember { mutableStateOf(if (botPref.getBoolean("ai_delete_only_mode", false)) "delete" else (botPref.getString("ai_block_process_mode", "BLOCK") ?: "BLOCK").lowercase()) }
    if (actionMode == "block") actionMode = "block"
    var blockDuration by remember { mutableStateOf(botPref.safeInt("ai_block_duration_hours", botPref.safeInt("block_duration_hours", 6))) }
    var deletePost by remember { mutableStateOf(if (botPref.contains("ai_delete_post_on_block")) botPref.getBoolean("ai_delete_post_on_block", true) else botPref.getBoolean("delete_post_on_block", true)) }
    var blockReason by remember { mutableStateOf(botPref.getString("ai_block_reason_text", botPref.getString("block_reason_text", "커뮤니티 규칙 위반") ?: "커뮤니티 규칙 위반") ?: "커뮤니티 규칙 위반") }

    fun saveString(key: String, value: String) = botPref.edit().putString(key, value).apply()
    fun saveIntText(key: String, value: String, fallback: Int, min: Int? = null): String {
        val parsed = value.trim().toIntOrNull() ?: fallback
        val saved = min?.let { parsed.coerceAtLeast(it) } ?: parsed
        botPref.edit().putInt(key, saved).apply()
        return saved.toString()
    }

    Column {
        Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("AI 필터", fontWeight = FontWeight.Bold, color = textColor)
                    Text("LLM 기반 2차 보조 필터입니다.", fontSize = 12.sp, color = subTextColor)
                }
                Switch(checked = isEnabled, onCheckedChange = { isEnabled = it; botPref.edit().putBoolean("is_ai_filter_mode", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy))
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("기본 설정", fontWeight = FontWeight.Bold, color = textColor)
                Text("큰 글은 생략하지 않고 단독 전체 검사로 전환됩니다.", fontSize = 12.sp, color = subTextColor)

                Text("AI 제공자", fontWeight = FontWeight.Bold, color = textColor)
                ExposedDropdownMenuBox(expanded = providerExpanded, onExpandedChange = { providerExpanded = !providerExpanded }) {
                    OutlinedTextField(value = providerOptions[provider] ?: provider, onValueChange = {}, readOnly = true, label = { Text("AI 제공자") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor))
                    ExposedDropdownMenu(expanded = providerExpanded, onDismissRequest = { providerExpanded = false }) {
                        providerOptions.forEach { (key, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                providerExpanded = false
                                provider = key
                                botPref.edit().putString("ai_filter_provider", key).apply()
                                if (key in setOf("gemini_direct", "groq", "lm_studio")) {
                                    useCustomEndpoint = false
                                    botPref.edit().putBoolean("ai_filter_use_custom_endpoint", false).apply()
                                }
                                if (!useCustomModel) {
                                    model = when (key) {
                                        "groq" -> "llama-3.3-70b-versatile"
                                        "lm_studio" -> "local-model"
                                        else -> "gemini-2.5-flash"
                                    }
                                    saveString("ai_filter_model", model)
                                }
                            })
                        }
                    }
                }
                if (provider == "custom_openai") {
                    OutlinedTextField(value = customProviderLabel, onValueChange = { customProviderLabel = it; saveString("ai_filter_provider_custom_label", it) }, label = { Text("제공자 표시 이름") }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor))
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Endpoint 직접 입력", fontWeight = FontWeight.Bold, color = textColor)
                        Text(if (provider == "lm_studio") "PC IP만 입력해도 자동 보정됩니다." else "필요할 때만 켜세요.", fontSize = 12.sp, color = subTextColor)
                    }
                    Switch(checked = useCustomEndpoint, onCheckedChange = { useCustomEndpoint = it; botPref.edit().putBoolean("ai_filter_use_custom_endpoint", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy))
                }
                if (useCustomEndpoint) {
                    OutlinedTextField(value = endpoint, onValueChange = { endpoint = it; saveString("ai_filter_endpoint", it.trim()) }, label = { Text(if (provider == "lm_studio") "PC IP 또는 Endpoint" else "Endpoint") }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor))
                }

                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it; saveString("ai_filter_api_key", it.trim()) }, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor))

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("모델 직접 입력", fontWeight = FontWeight.Bold, color = textColor)
                    Switch(checked = useCustomModel, onCheckedChange = { useCustomModel = it; botPref.edit().putBoolean("ai_filter_use_custom_model", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy))
                }
                OutlinedTextField(value = model, onValueChange = { model = it; saveString("ai_filter_model", it.trim()) }, label = { Text("모델") }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor))

                OutlinedTextField(value = prompt, onValueChange = { prompt = it; saveString("ai_filter_user_prompt", it.trim()) }, label = { Text("사용자 프롬프트") }, minLines = 5, modifier = Modifier.fillMaxWidth().height(180.dp), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor))
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("배치 기준", fontWeight = FontWeight.Bold, color = textColor)
                NumberField("최대 글 수", maxPosts, textColor) { maxPosts = it; if (it.isNotBlank()) maxPosts = saveIntText("ai_filter_batch_max_posts", it, 5) }
                NumberField("최대 대기 시간(초)", maxWaitSec, textColor) { maxWaitSec = it; if (it.isNotBlank()) maxWaitSec = saveIntText("ai_filter_batch_max_wait_sec", it, 5) }
                NumberField("최대 누적 용량", maxWeight, textColor) { maxWeight = it; if (it.isNotBlank()) maxWeight = saveIntText("ai_filter_batch_max_weight", it, 20000) }
                NumberField("호출 타임아웃(초)", timeoutSec, textColor) { timeoutSec = it; if (it.isNotBlank()) timeoutSec = saveIntText("ai_filter_timeout_sec", it, 20, min = 5) }
                Text("LM Studio/로컬 LLM은 60~180초 권장", fontSize = 12.sp, color = subTextColor)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("개별 차단 설정", fontWeight = FontWeight.Bold, color = textColor)
                        Text("AI block 결과에만 적용됩니다.", fontSize = 12.sp, color = subTextColor)
                    }
                    Switch(checked = useCustomAction, onCheckedChange = { useCustomAction = it; botPref.edit().putBoolean("ai_use_custom_action_config", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy))
                }
                Divider(color = dividerColor)
                Box {
                    OutlinedButton(onClick = { actionExpanded = true }) {
                        Text(mapOf("block" to "차단", "delete" to "삭제만", "hold" to "보류")[actionMode] ?: "차단", color = textColor)
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy)
                    }
                    DropdownMenu(expanded = actionExpanded, onDismissRequest = { actionExpanded = false }, modifier = Modifier.background(dialogBgColor)) {
                        listOf("block" to "차단", "delete" to "삭제만", "hold" to "보류").forEach { (mode, label) ->
                            DropdownMenuItem(text = { Text(label, color = textColor) }, onClick = {
                                actionMode = mode
                                botPref.edit()
                                    .putString("ai_block_process_mode", mode.uppercase())
                                    .putBoolean("ai_delete_only_mode", mode == "delete")
                                    .apply()
                                actionExpanded = false
                            })
                        }
                    }
                }
                if (actionMode == "block") {
                    Box {
                        OutlinedButton(onClick = { durationExpanded = true }) {
                            Text("${blockDuration}시간", color = textColor)
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy)
                        }
                        DropdownMenu(expanded = durationExpanded, onDismissRequest = { durationExpanded = false }, modifier = Modifier.background(dialogBgColor)) {
                            listOf(1, 6, 12, 24, 72, 168, 720).forEach { hours ->
                                DropdownMenuItem(text = { Text("${hours}시간", color = textColor) }, onClick = { blockDuration = hours; botPref.edit().putInt("ai_block_duration_hours", hours).apply(); durationExpanded = false })
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("차단 시 글/댓글 함께 삭제", color = textColor)
                        Switch(checked = deletePost, onCheckedChange = { deletePost = it; botPref.edit().putBoolean("ai_delete_post_on_block", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy))
                    }
                    OutlinedTextField(value = blockReason, onValueChange = { blockReason = it; saveString("ai_block_reason_text", it) }, label = { Text("차단 사유") }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor))
                }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, textColor: Color, onChange: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = FontWeight.Bold, color = textColor)
        OutlinedTextField(
            value = value,
            onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) onChange(it) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(0.45f),
            textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor),
        )
    }
}
