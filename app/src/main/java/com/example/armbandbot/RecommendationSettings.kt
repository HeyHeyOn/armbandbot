package com.heyheyon.armbandbot

import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heyheyon.armbandbot.ui.*

@Composable
private fun recommendationSettingsRevision(p: SharedPreferences): Int {
    var revision by remember(p) { mutableIntStateOf(0) }
    DisposableEffect(p) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in setOf(RECOMMEND_GUARD_ENABLED, RECOMMEND_GUARD_SECONDS, RECOMMEND_GUARD_COUNT)) revision++
        }
        p.registerOnSharedPreferenceChangeListener(listener)
        onDispose { p.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return revision
}

internal fun recommendationTimeLabel(seconds: Int): String = when {
    seconds % 60 == 0 -> "${seconds / 60}분"
    seconds < 60 -> "${seconds}초"
    else -> "${seconds / 60}분 ${seconds % 60}초"
}

@Composable
internal fun RecommendationSettingsEntry(p: SharedPreferences, colors: BotColorScheme, onOpen: (String) -> Unit) {
    val revision = recommendationSettingsRevision(p)
    val policy = remember(p, revision) { runCatching { recommendationPolicy(p) }.getOrDefault(RecommendationPolicy()) }
    val enabled = remember(p, revision) { p.getBoolean(RECOMMEND_GUARD_ENABLED, false) }
    Box(Modifier.testTag("recommend-guard-entry")) {
        ModernSettingItem("추천수 조작 방지", "${recommendationTimeLabel(policy.seconds)} 이내 +${policy.increase}개 → 개념글 해제",
            Icons.Default.TrendingUp, colors, enabled, { p.edit().putBoolean(RECOMMEND_GUARD_ENABLED, it).apply() }) { onOpen(RECOMMEND_GUARD_ROUTE) }
    }
}

@Composable
internal fun RecommendationSettingsScreen(p: SharedPreferences, colors: BotColorScheme, onBack: () -> Unit) {
    val revision = recommendationSettingsRevision(p)
    val policy = remember(p, revision) { runCatching { recommendationPolicy(p) }.getOrDefault(RecommendationPolicy()) }
    val enabled = remember(p, revision) { p.getBoolean(RECOMMEND_GUARD_ENABLED, false) }
    var editing by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(colors.bg).testTag("recommend-guard-page")) {
        SettingsDetailHeader("추천수 조작 방지", colors, onBack) {
            IconButton(onClick = { help = true }, modifier = Modifier.testTag("recommend-help")) {
                Icon(Icons.Default.HelpOutline, "도움말", tint = colors.accent)
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ModernSettingsBlock("추천수 조작 방지", "추천 급증을 감지하면 개념글을 해제합니다.", Icons.Default.TrendingUp, colors,
                trailing = { ModernSettingsSwitch(enabled, { p.edit().putBoolean(RECOMMEND_GUARD_ENABLED, it).apply() }, colors, Modifier.testTag("recommend-enabled")) }) {
                Column(Modifier.alpha(if (enabled) 1f else 0.45f)) {
                    Text("${recommendationTimeLabel(policy.seconds)} 이내에 추천이 ${policy.increase}개 이상 늘면 감지", color = colors.text, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("감지 기준 설정", color = colors.accent, modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = enabled) { editing = true }.padding(vertical = 12.dp).testTag("recommend-edit"))
                    HorizontalDivider(color = colors.divider)
                    Text("추천 수는 약 ${policy.pollMs / 1000}초 간격으로 별도 확인합니다.", color = colors.subText, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                    Text("탐색 범위가 넓거나 연결이 느리면 관측 간격이 길어질 수 있습니다. 실제 관측 시각으로 판단합니다.", color = colors.subText, fontSize = 12.sp)
                }
            }
            Text("현재 탐색 범위 · 기존 화이트리스트와 차단 예외 적용", color = colors.subText, fontSize = 12.sp)
        }
    }
    if (editing) RecommendationCriteriaDialog(policy, colors, onDismiss = { editing = false }) { next ->
        check(p.edit().putInt(RECOMMEND_GUARD_SECONDS, next.seconds).putInt(RECOMMEND_GUARD_COUNT, next.increase).commit())
        editing = false
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, containerColor = colors.dialogBg,
        titleContentColor = colors.text, textContentColor = colors.subText,
        title = { Text("추천수 조작 방지 도움말") }, text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Text("기준 시간 안에 관측한 추천 증가량으로 판단합니다. 예를 들어 3분·10개이면 2분 동안 10개가 늘어도 감지합니다. 4분 동안의 증가를 3분으로 환산하지 않습니다.\n\n처음 발견한 추천 수는 기준점으로만 사용합니다. 기준 시간을 넘긴 관측 공백, 추천 감소, 설정 변경 후에는 새로 관찰합니다.\n\n일반 글도 관찰하고, 감지 후 기준 시간 안에 개념글로 올라오면 해제합니다. 해제 성공한 글은 다시 해제하지 않으며, 결과 미확인 시 중복 요청을 보류합니다.\n\n봇의 계정·탐색 범위·운영 시간대를 공유합니다. 봇 중지나 기능 OFF 시 보조 작업도 멈춥니다. 개념글 전용 목록만 감시하면 진입 전 증가량은 알 수 없습니다.\n\n추천 급증만으로 조작을 확정할 수는 없습니다. 갤러리에 맞게 기준을 정하고 활동 로그에서 감지 근거와 해제 결과를 확인해 주세요.")
            }
        }, confirmButton = { TextButton(onClick = { help = false }) { Text("닫기", color = colors.accent) } })
}

@Composable
private fun RecommendationCriteriaDialog(initial: RecommendationPolicy, colors: BotColorScheme, onDismiss: () -> Unit, onSave: (RecommendationPolicy) -> Unit) {
    var seconds by remember { mutableStateOf(initial.seconds.toString()) }
    var count by remember { mutableStateOf(initial.increase.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = colors.dialogBg,
        titleContentColor = colors.text, textContentColor = colors.subText, modifier = Modifier.testTag("recommend-criteria"),
        title = { Text("감지 기준 설정") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("기준 시간 안에 늘어난 추천 수를 비교합니다.")
                OutlinedTextField(seconds, { seconds = it }, label = { Text("기준 시간 (초)") }, supportingText = { Text("10~3,600초 · 180초 = 3분") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.testTag("recommend-seconds"))
                OutlinedTextField(count, { count = it }, label = { Text("추천 증가량 (개 이상)") }, supportingText = { Text("1~1,000,000개") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.testTag("recommend-count"))
                error?.let { Text(it, color = colors.warningRed) }
            }
        }, confirmButton = { TextButton(onClick = {
            try {
                val time = seconds.trim().toIntOrNull() ?: error("기준 시간을 숫자로 입력해 주세요.")
                val increase = count.trim().toIntOrNull() ?: error("추천 증가량을 숫자로 입력해 주세요.")
                onSave(RecommendationPolicy(time, increase))
            } catch (e: Exception) { error = e.message ?: "입력값을 확인해 주세요." }
        }, modifier = Modifier.testTag("recommend-save")) { Text("저장", color = colors.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소", color = colors.text) } })
}
