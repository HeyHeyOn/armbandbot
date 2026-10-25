package com.heyheyon.armbandbot

import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heyheyon.armbandbot.ui.*

/** Stepper choices. Values outside these lists (old or imported settings) stay as they are until changed. */
internal val REPEAT_MINUTE_STEPS = (1..10).toList() + (15..60 step 5).toList()
internal val REPEAT_COUNT_STEPS = (2..20).toList()
internal val REPEAT_SIMILARITY_STEPS = listOf(85, 90, 95, 100)

internal fun repeatStep(current: Int, steps: List<Int>, up: Boolean): Int =
    if (up) steps.firstOrNull { it > current } ?: current else steps.lastOrNull { it < current } ?: current

internal fun repeatSimilarityMeaning(percent: Int): String = when {
    percent >= 100 -> "정리 후 똑같은 글만"
    percent >= 95 -> "거의 같은 글만"
    percent >= 90 -> "조금 바꾼 글까지"
    else -> "표현을 바꾼 글까지"
}

internal fun repeatDurationLabel(hours: Int): String = when {
    hours % 24 == 0 && hours >= 24 -> "${hours / 24}일"
    else -> "${hours}시간"
}

/** One sentence that tells the operator what the current combination actually does. */
internal fun repeatSpamSummary(s: RepeatSpamSettings, action: RepeatActionView): String {
    val result = when (action.mode) {
        "HOLD" -> "지우지 않고 보류 기록으로 남깁니다."
        "BLOCK" -> if (action.deletePost) "작성자를 ${repeatDurationLabel(action.durationHours)} 차단하고 첫 글까지 모두 삭제합니다."
                   else "작성자를 ${repeatDurationLabel(action.durationHours)} 차단합니다. 글은 지우지 않습니다."
        else -> "첫 글까지 모두 삭제합니다."
    }
    return "같은 작성자가 ${s.windowMinutes}분 안에 제목이나 본문이 비슷한 글을 ${s.count}개 이상 올리면 $result"
}

internal fun repeatSpamTargets(s: RepeatSpamSettings): String? =
    listOfNotNull("유동".takeIf { s.yudong }, "깡계".takeIf { s.kkang }).takeIf { it.isNotEmpty() }?.joinToString(" · ")

private fun kkangCriteria(p: SharedPreferences): String {
    fun int(key: String, default: Int) = when (val v = p.all[key]) { is Int -> v; is Long -> v.toInt(); is String -> v.toIntOrNull() ?: default; else -> default }
    return when ((p.all["kkang_detection_mode"] as? String)?.takeIf { it in setOf("total", "separate", "dc_mark") } ?: "separate") {
        "total" -> "글+댓글 수 ${int("kkang_total_min", 15)} 미만"
        "dc_mark" -> "신규 고정닉 표시"
        else -> "글 수 ${int("kkang_post_min", 5)} 미만 또는 댓글 수 ${int("kkang_comment_min", 10)} 미만"
    }
}

@Composable
internal fun RepeatSpamSettingsCard(p: SharedPreferences, colors: BotColorScheme, enabled: Boolean, onEnabled: (Boolean) -> Unit) {
    var settings by remember(p) { mutableStateOf(repeatSpamSettings(p)) }
    var action by remember(p) { mutableStateOf(repeatActionView(p)) }
    var help by remember { mutableStateOf(false) }
    var editMinimum by remember { mutableStateOf(false) }
    fun refresh() { settings = repeatSpamSettings(p); action = repeatActionView(p) }
    fun save(edit: SharedPreferences.Editor.() -> Unit) {
        p.edit().apply(edit).repeatSpamChanged().apply()
        refresh()
    }
    val defaults = RepeatSpamSettings()
    val isDefaultCriteria = settings.windowMinutes == defaults.windowMinutes && settings.count == defaults.count &&
        settings.similarityPercent == defaults.similarityPercent && settings.minimumLength == defaults.minimumLength
    val targets = repeatSpamTargets(settings)

    ModernSettingsBlock("도배 방지 사용", "끄면 설정을 보관하고 감지를 멈춥니다.", Icons.Filled.Repeat, colors,
        modifier = Modifier.testTag("repeat-master"), trailing = {
            ModernSettingsSwitch(enabled, onEnabled, colors, Modifier.testTag("repeat-enabled"))
        })
    Spacer(Modifier.height(8.dp))
    RepeatSwitchRow(Icons.Filled.ContentCopy, "유사 내용 반복 감지", "제목 또는 본문이 비슷한 글을 감지합니다.", settings.contentEnabled,
        enabled, "repeat-content-enabled", colors) { save { putBoolean(REPEAT_CONTENT_ENABLED_KEY, it) } }
    val contentActive = enabled && settings.contentEnabled
    Column(Modifier.fillMaxWidth().alpha(if (contentActive) 1f else .4f).testTag("repeat-options")) {
        // What the current combination does, in one sentence.
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(if (targets == null) colors.blockCard else colors.accentContainer)
                .padding(16.dp).semantics(mergeDescendants = true) {}.testTag("repeat-summary"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (targets == null) {
                Text("검사 대상이 모두 꺼져 있어 반복 내용을 감지하지 않습니다.", color = colors.warningRed, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("아래 ‘검사 대상’에서 유동이나 깡계를 하나 이상 켜 주세요.", color = colors.text, fontSize = 13.sp)
            } else {
                Text(repeatSpamSummary(settings, action), color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp)
                Text("대상: $targets · 내용 비슷한 정도 ${settings.similarityPercent}% 이상" +
                    if (action.followsDefault) " · 처리: 차단 기본 설정" else "", color = colors.subText, fontSize = 12.sp)
            }
        }

        RepeatSectionLabel("감지 기준", colors, trailing = {
            TextButton(onClick = {
                save { putInt(REPEAT_WINDOW_KEY, defaults.windowMinutes).putInt(REPEAT_COUNT_KEY, defaults.count).putInt(REPEAT_SIMILARITY_KEY, defaults.similarityPercent).putInt(REPEAT_MIN_LENGTH_KEY, defaults.minimumLength) }
            }, enabled = contentActive && !isDefaultCriteria, modifier = Modifier.testTag("repeat-defaults")) {
                Text("기본값으로", color = if (contentActive && !isDefaultCriteria) colors.accent else colors.subText, fontSize = 13.sp)
            }
        })
        SettingsGroup(colors) {
            RepeatMinimumLengthRow(settings.minimumLength, contentActive, colors) { editMinimum = true }
            RepeatStepperRow(Icons.Filled.Schedule, "확인 시간", "이 시간 안의 글끼리 비교", "${settings.windowMinutes}분",
                "repeat-minutes", contentActive, settings.windowMinutes > 1, settings.windowMinutes < 60, colors,
                onMinus = { save { putInt(REPEAT_WINDOW_KEY, repeatStep(settings.windowMinutes, REPEAT_MINUTE_STEPS, false)) } },
                onPlus = { save { putInt(REPEAT_WINDOW_KEY, repeatStep(settings.windowMinutes, REPEAT_MINUTE_STEPS, true)) } })
            RepeatStepperRow(Icons.Filled.ContentCopy, "반복 횟수", "첫 글 포함, 이만큼이면 도배", "${settings.count}개",
                "repeat-count", contentActive, settings.count > 2, settings.count < 20, colors,
                onMinus = { save { putInt(REPEAT_COUNT_KEY, repeatStep(settings.count, REPEAT_COUNT_STEPS, false)) } },
                onPlus = { save { putInt(REPEAT_COUNT_KEY, repeatStep(settings.count, REPEAT_COUNT_STEPS, true)) } })
            RepeatStepperRow(Icons.Filled.Compare, "내용 비슷한 정도", repeatSimilarityMeaning(settings.similarityPercent), "${settings.similarityPercent}%",
                "repeat-similarity", contentActive, settings.similarityPercent > 85, settings.similarityPercent < 100, colors,
                onMinus = { save { putInt(REPEAT_SIMILARITY_KEY, repeatStep(settings.similarityPercent, REPEAT_SIMILARITY_STEPS, false)) } },
                onPlus = { save { putInt(REPEAT_SIMILARITY_KEY, repeatStep(settings.similarityPercent, REPEAT_SIMILARITY_STEPS, true)) } })
        }
        Text("기본값: 최소 8자 · 10분 · 4개 · 85%. 바꾸면 바로 저장되고 그동안 모은 반복 기록은 새로 시작합니다.",
            color = colors.subText, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp))

        RepeatSectionLabel("검사 대상", colors)
        SettingsGroup(colors) {
            RepeatSwitchRow(Icons.Filled.Lock, "유동", "비로그인 작성자. 같은 표시 IP의 글을 묶어 비교합니다.", settings.yudong, contentActive, "repeat-yudong", colors) {
                save { putBoolean(REPEAT_YUDONG_KEY, it) }
            }
            RepeatSwitchRow(Icons.Filled.Info, "깡계", "깡계 필터 기준을 따릅니다. (현재: ${kkangCriteria(p)})", settings.kkang, contentActive, "repeat-kkang", colors) {
                save { putBoolean(REPEAT_KKANG_KEY, it) }
            }
        }
        Text("일반 회원의 글과 댓글은 검사하지 않습니다. 화이트리스트와 차단 예외 글도 제외합니다.",
            color = colors.subText, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp))

        FilterActionSettingsSection(p, REPEAT_ACTION_PREFIX, "유사 내용 반복 감지", colors, contentActive, customDefault = true,
            initialMode = repeatCustomProcessMode(p), onChanged = { refresh() }, editorTransform = { repeatSpamChanged() })
        Text(when (action.mode) {
                "HOLD" -> "보류는 글을 그대로 두고 기록만 남깁니다. 같은 글이 다른 필터에도 걸리면 그 필터의 처리 방식을 따릅니다. 기준을 처음 정할 때 보류로 결과를 먼저 확인해 보세요."
                "BLOCK" -> "첫 글을 포함해 반복된 글마다 차단을 요청합니다. 처음에는 보류로 결과를 확인한 뒤 바꾸는 것을 권장합니다."
                else -> "삭제는 작성자 차단 없이 글만 지웁니다. 삭제한 글은 되돌릴 수 없습니다."
            }, color = colors.subText, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, end = 6.dp).testTag("repeat-action-note"))
    }
    Spacer(Modifier.height(16.dp))
    SettingsGroup(colors) {
        Box(Modifier.testTag("repeat-help")) {
            ModernSettingItem("감지 방식과 주의사항", "어떤 글을 묶는지, 놓칠 수 있는 경우", Icons.Filled.HelpOutline, colors) { help = true }
        }
    }
    PostingRateSettingsCard(p, colors, enabled)
    Spacer(Modifier.height(24.dp))
    if (editMinimum) RepeatMinimumLengthDialog(settings.minimumLength, colors, onDismiss = { editMinimum = false }) {
        save { putInt(REPEAT_MIN_LENGTH_KEY, it) }
        editMinimum = false
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, modifier = Modifier.testTag("repeat-help-dialog"),
        containerColor = colors.dialogBg, titleContentColor = colors.text, textContentColor = colors.subText,
        title = { Text("유사 내용 반복 감지 도움말") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HelpParagraph("어떤 글을 묶나요?", "같은 갤러리에서 회원은 같은 ID, 유동은 같은 표시 IP로 올린 게시글을 묶습니다. 정한 시간 안에 제목 또는 본문 중 어느 한쪽이 비슷한 글이 반복 횟수만큼 쌓이면 도배로 봅니다. 제목끼리, 본문끼리 따로 세며 둘 다 같을 필요는 없습니다.", colors)
                HelpParagraph("무엇을 하나요?", "첫 글을 포함해 반복된 글을 ‘개별 차단 설정’의 처리 방식(처음에는 삭제)대로 처리합니다. 다른 필터처럼 삭제·차단·보류 중에서 고르고, 끄면 차단 기본 설정을 따릅니다. 같은 페이지에서 먼저 검사한 글도 이어서 확인합니다. 이미 감지된 글은 최대 15분 동안 처리 대상으로 유지하며, 봇을 끄거나 관련 설정을 바꾸면 초기화합니다.", colors)
                HelpParagraph("비슷한 정도는 어떻게 보나요?", "공백·기호·보이지 않는 문자를 정리한 뒤 비교합니다. 숫자나 ‘안·않·없’ 같은 부정 표현이 다르면 다른 글로 봅니다. 설정한 최소 글자 수는 제목과 본문에 각각 적용합니다. 짧은 글에도 선택한 유사도를 적용하고, 100%면 정리한 뒤 같아야 합니다.", colors)
                HelpParagraph("최소 글자 수가 0이면?", "길이 제한 없이 비교합니다. 기호만 있는 내용도 공백을 정리한 뒤 같으면 감지합니다. 단, 빈 본문끼리 같다는 이유로 도배로 판단하지는 않습니다. 이미지 속 글자는 읽지 않지만 제목이 반복되면 감지할 수 있습니다.", colors)
                HelpParagraph("검사하지 않는 글", "댓글, 일반 회원의 글, 화이트리스트로 보호된 작성자, 차단 예외 글은 제외합니다. 봇이 탐색하는 페이지와 작동 시간 안에서 본 글만 셉니다.", colors)
                HelpParagraph("놓치거나 잘못 볼 수 있는 경우", "표시 IP가 같아도 다른 사람일 수 있습니다. IP를 바꾸거나 내용을 크게 바꾼 도배, 이미지 속 글자로만 반복하는 도배는 놓칠 수 있습니다. 처음에는 처리 방식을 ‘보류’로 두고 결과를 확인해 보세요.", colors)
            }
        },
        confirmButton = { TextButton(onClick = { help = false }) { Text("닫기", color = colors.accent) } })
}

@Composable
private fun RepeatSectionLabel(title: String, colors: BotColorScheme, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp, bottom = if (trailing == null) 8.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = colors.accent, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun HelpParagraph(title: String, body: String, colors: BotColorScheme) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(body, color = colors.subText, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

/** Value with − / + buttons. Every tap is one valid value and is saved immediately. */
@Composable
private fun RepeatStepperRow(icon: ImageVector, title: String, subtitle: String, value: String, tag: String, enabled: Boolean,
                             canMinus: Boolean, canPlus: Boolean, colors: BotColorScheme, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        SettingIconBadge(icon, colors)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = colors.text)
            Text(subtitle, fontSize = 12.sp, color = colors.subText, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(8.dp))
        StepButton(Icons.Filled.Remove, "$title 줄이기", enabled && canMinus, colors, Modifier.testTag("$tag-minus"), onMinus)
        Text(value, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 52.dp).testTag(tag))
        StepButton(Icons.Filled.Add, "$title 늘리기", enabled && canPlus, colors, Modifier.testTag("$tag-plus"), onPlus)
    }
}

@Composable
private fun StepButton(icon: ImageVector, description: String, enabled: Boolean, colors: BotColorScheme, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(36.dp), shape = CircleShape,
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = colors.accentContainer, contentColor = colors.accent,
            disabledContainerColor = colors.surfaceMuted, disabledContentColor = colors.subText.copy(alpha = .5f))) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(18.dp))
    }
}

/** Whole row toggles (project convention: one toggleable row, the Switch only displays the state). */
@Composable
private fun RepeatSwitchRow(icon: ImageVector, title: String, subtitle: String, value: Boolean, enabled: Boolean, tag: String,
                            colors: BotColorScheme, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = value, enabled = enabled, role = Role.Switch, onValueChange = change).testTag(tag)
        .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        SettingIconBadge(icon, colors)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = colors.text)
            Text(subtitle, fontSize = 12.sp, color = colors.subText, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(8.dp))
        ModernSettingsSwitch(value, null, colors, enabled = enabled)
    }
}

internal fun parseRepeatMinimumLength(text: String): Int? =
    text.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toIntOrNull()

@Composable
private fun RepeatMinimumLengthRow(value: Int, enabled: Boolean, colors: BotColorScheme, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).testTag("repeat-min-length")
        .padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        SettingIconBadge(Icons.Filled.TextFields, colors)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("최소 글자 수", color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text("제목·본문 각각 적용 · 눌러서 입력", color = colors.subText, fontSize = 12.sp)
        }
        Text(if (value == 0) "0자 · 제한 없음" else "${value}자", color = colors.accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Filled.Edit, contentDescription = null, tint = colors.subText, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun RepeatMinimumLengthDialog(value: Int, colors: BotColorScheme, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var draft by remember { mutableStateOf(value.toString()) }
    val parsed = parseRepeatMinimumLength(draft)
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("repeat-min-length-dialog"),
        containerColor = colors.dialogBg, titleContentColor = colors.text, textContentColor = colors.subText,
        title = { Text("최소 글자 수") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("공백·기호를 제외한 글자 수를 제목과 본문에 각각 적용합니다. 0이면 길이에 상관없이 검사합니다.", fontSize = 14.sp)
                OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true,
                    label = { Text("최소 글자 수") }, suffix = { Text("자") },
                    supportingText = { Text(if (parsed == null) "0 이상의 정수를 입력해 주세요." else "0 = 길이 제한 없음") },
                    isError = parsed == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("repeat-min-length-input"),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = colors.text, unfocusedTextColor = colors.text,
                        focusedBorderColor = colors.accent, cursorColor = colors.accent,
                        focusedLabelColor = colors.accent, unfocusedLabelColor = colors.subText,
                        focusedSuffixColor = colors.subText, unfocusedSuffixColor = colors.subText,
                        focusedSupportingTextColor = colors.subText, unfocusedSupportingTextColor = colors.subText))
            }
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onSave) }, enabled = parsed != null, modifier = Modifier.testTag("repeat-min-length-save")) {
            Text("저장", color = if (parsed != null) colors.accent else colors.subText)
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소", color = colors.subText) } })
}
