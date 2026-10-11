package com.heyheyon.armbandbot

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heyheyon.armbandbot.ui.*

@Composable
internal fun RepeatSpamSettingsCard(p: SharedPreferences, colors: BotColorScheme, enabled: Boolean, onEnabled: (Boolean) -> Unit) {
    var minutes by remember(p) { mutableStateOf(repeatSpamSettings(p).windowMinutes.toString()) }
    var count by remember(p) { mutableStateOf(repeatSpamSettings(p).count.toString()) }
    var similarity by remember(p) { mutableStateOf(repeatSpamSettings(p).similarityPercent.toString()) }
    var observe by remember(p) { mutableStateOf(p.getBoolean(REPEAT_OBSERVE_KEY, false)) }
    var yudong by remember(p) { mutableStateOf(p.getBoolean(REPEAT_YUDONG_KEY, true)) }
    var kkang by remember(p) { mutableStateOf(p.getBoolean(REPEAT_KKANG_KEY, true)) }
    var help by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val valid = minutes.toIntOrNull() in 1..60 && count.toIntOrNull() in 2..20 && similarity.toIntOrNull() in 85..100
    Card(colors = CardDefaults.cardColors(containerColor = colors.card), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("도배 방지", color = colors.text, fontWeight = FontWeight.Bold)
                    Text("같은 작성자 정보의 반복 글만 골라 최초 글부터 삭제합니다.", color = colors.subText, fontSize = 12.sp)
                }
                Switch(enabled, onEnabled, modifier = Modifier.testTag("repeat-enabled"), colors = modernSwitchColors(colors))
            }
            TextButton(onClick = { help = true }) { Text("감지 기준과 주의사항", color = colors.accent) }
        }
    }
    Spacer(Modifier.height(16.dp))
    Card(modifier = Modifier.alpha(if (enabled) 1f else .4f), colors = CardDefaults.cardColors(containerColor = colors.card), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("반복 기준", color = colors.text, fontWeight = FontWeight.Bold)
            RepeatNumberField("시간 범위(분)", minutes, "1~60분 · 기본 10분", "repeat-minutes", enabled, colors) { minutes = it; saved = false }
            RepeatNumberField("반복 횟수", count, "2~20회 · 기본 4회", "repeat-count", enabled, colors) { count = it; saved = false }
            RepeatNumberField("내용 유사도(%)", similarity, "85~100% · 100%는 정규화 후 일치", "repeat-similarity", enabled, colors) { similarity = it; saved = false }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = enabled && valid, onClick = {
                    p.edit().putInt(REPEAT_WINDOW_KEY, minutes.toInt()).putInt(REPEAT_COUNT_KEY, count.toInt())
                        .putInt(REPEAT_SIMILARITY_KEY, similarity.toInt()).repeatSpamChanged().apply(); saved = true
                }, modifier = Modifier.testTag("repeat-save")) { Text("기준 저장") }
                TextButton(enabled = enabled, onClick = { minutes = "10"; count = "4"; similarity = "85"; saved = false }) { Text("기본값 불러오기") }
            }
            Text(if (saved) "반복 기준을 저장했습니다." else "입력 후 기준 저장을 눌러 주세요.", color = colors.subText, fontSize = 12.sp)
            RepeatToggle("유동 대상", yudong, enabled, colors, "repeat-yudong") { yudong = it; p.edit().putBoolean(REPEAT_YUDONG_KEY, it).repeatSpamChanged().apply() }
            RepeatToggle("깡계 대상", kkang, enabled, colors, "repeat-kkang") { kkang = it; p.edit().putBoolean(REPEAT_KKANG_KEY, it).repeatSpamChanged().apply() }
            RepeatToggle("삭제 없이 감지 기록만", observe, enabled, colors, "repeat-observe") { observe = it; p.edit().putBoolean(REPEAT_OBSERVE_KEY, it).repeatSpamChanged().apply() }
            Text("깡계 판정은 깡계 필터의 기준을 따릅니다. 감지 기록만 켜면 보류 내역을 남깁니다.", color = colors.subText, fontSize = 12.sp)
        }
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, containerColor = colors.card, titleContentColor = colors.text, textContentColor = colors.subText,
        title = { Text("반복 글 감지") }, text = { Text(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), text = "같은 갤러리에서 계정 또는 표시 IP가 같고 제목·본문이 반복되는 글을 감지합니다. 설정한 시간·횟수에 도달하면 최초 글도 삭제 대상에 포함합니다. 앞서 검사한 글은 다음 검사에서 다시 확인한 뒤 처리합니다.\n\n화이트리스트와 글번호 예외는 보호합니다. 짧고 흔한 반응은 제외하고, 짧은 문장은 정규화 후 같은 내용일 때만 감지합니다. 일부 공백·특수문자 변형을 비교하지만 모든 우회 표현을 잡지는 못합니다.\n\n표시 IP가 같아도 다른 사람일 수 있습니다. IP를 바꾸거나 내용을 완전히 바꾸는 도배, 이미지로만 반복하는 도배는 놓칠 수 있습니다. 이전 버전의 전체 유동/깡계 신규 글 일괄 삭제는 사용하지 않습니다.") },
        confirmButton = { TextButton(onClick = { help = false }) { Text("확인", color = colors.accent) } })
}

@Composable
private fun RepeatNumberField(label: String, value: String, hint: String, tag: String, enabled: Boolean, colors: BotColorScheme, change: (String) -> Unit) {
    OutlinedTextField(value, { if (it.length <= 3 && it.all(Char::isDigit)) change(it) }, enabled = enabled,
        label = { Text(label) }, supportingText = { Text(hint) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().testTag(tag),
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = colors.text, unfocusedTextColor = colors.text,
            disabledTextColor = colors.subText, focusedLabelColor = colors.accent, unfocusedLabelColor = colors.subText,
            focusedSupportingTextColor = colors.subText, unfocusedSupportingTextColor = colors.subText,
            cursorColor = colors.accent, focusedBorderColor = colors.accent, unfocusedBorderColor = colors.divider))
}

@Composable
private fun RepeatToggle(label: String, value: Boolean, enabled: Boolean, colors: BotColorScheme, tag: String, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = colors.text, modifier = Modifier.weight(1f))
        Switch(value, change, enabled = enabled, colors = modernSwitchColors(colors), modifier = Modifier.testTag(tag))
    }
}
