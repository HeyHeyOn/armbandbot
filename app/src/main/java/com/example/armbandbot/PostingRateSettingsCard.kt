package com.heyheyon.armbandbot

import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heyheyon.armbandbot.ui.*

internal fun parsePostingRateValue(text: String, seconds: Boolean): Int? =
    text.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toIntOrNull()
        ?.takeIf { it in if (seconds) 10..3600 else 2..100 }

@Composable
internal fun PostingRateSettingsCard(p: SharedPreferences, colors: BotColorScheme, masterEnabled: Boolean) {
    var settings by remember(p) { mutableStateOf(postingRateSettings(p)) }
    var action by remember(p) { mutableStateOf(postingRateActionView(p)) }
    var editing by remember { mutableStateOf<String?>(null) }
    var help by remember { mutableStateOf(false) }
    fun refresh() { settings = postingRateSettings(p); action = postingRateActionView(p) }
    fun save(edit: SharedPreferences.Editor.() -> Unit) { p.edit().apply(edit).repeatSpamChanged().apply(); refresh() }
    val active = masterEnabled && settings.enabled
    Spacer(Modifier.height(16.dp))
    ModernSettingsBlock("연속 작성 감지", "내용이 달라도 짧은 시간에 여러 글을 올리면 감지합니다.", Icons.Filled.Speed, colors,
        modifier = Modifier.testTag("rate-master"), trailing = {
            ModernSettingsSwitch(settings.enabled, { save { putBoolean(RATE_ENABLED_KEY, it) } }, colors,
                Modifier.testTag("rate-enabled"), enabled = masterEnabled)
        })
    Column(Modifier.fillMaxWidth().alpha(if (active) 1f else .4f).testTag("rate-options")) {
        Text(if (!settings.yudong && !settings.kkang) "검사 대상이 모두 꺼져 있어 연속 작성을 감지하지 않습니다."
            else when(action.mode) {
                "HOLD" -> "기준을 넘으면 글은 그대로 두고 보류 기록을 남깁니다."
                "BLOCK" -> if(action.deletePost) "기준을 넘으면 작성자를 ${repeatDurationLabel(action.durationHours)} 차단하고 첫 글까지 삭제합니다."
                    else "기준을 넘으면 작성자를 ${repeatDurationLabel(action.durationHours)} 차단합니다. 글은 지우지 않습니다."
                else -> "기준을 넘으면 시간 안에 모인 첫 글까지 모두 삭제합니다."
            }, color = colors.text, fontSize = 14.sp, modifier = Modifier.padding(12.dp).testTag("rate-summary"))
        RateTargetSection("유동 · 같은 표시 IP", settings.yudong, active, "rate-ip", colors,
            { save { putBoolean(RATE_YUDONG_KEY, it) } }, settings.ipSeconds, settings.ipCount,
            { editing = RATE_IP_SECONDS_KEY }, { editing = RATE_IP_COUNT_KEY })
        Spacer(Modifier.height(8.dp))
        RateTargetSection("깡계 · 같은 계정", settings.kkang, active, "rate-uid", colors,
            { save { putBoolean(RATE_KKANG_KEY, it) } }, settings.uidSeconds, settings.uidCount,
            { editing = RATE_UID_SECONDS_KEY }, { editing = RATE_UID_COUNT_KEY })
        Text("깡계는 깡계 필터의 판정 기준을 따릅니다. 같은 표시 IP여도 다른 사람일 수 있습니다.",
            color = colors.subText, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
        FilterActionSettingsSection(p, RATE_ACTION_PREFIX, "연속 작성 감지", colors, active, customDefault = true,
            initialMode = postingRateCustomMode(p), onChanged = { refresh() }, editorTransform = { repeatSpamChanged() })
        Text("처음에는 보류로 결과를 확인해 보세요. 기준을 바꾸면 모아 둔 감지 기록을 새로 시작합니다.",
            color = colors.subText, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
    }
    ModernSettingItem("연속 작성 감지 도움말", "표시 IP·검사 주기·처리 범위", Icons.Filled.HelpOutline, colors) { help = true }
    editing?.let { key ->
        val seconds = key == RATE_IP_SECONDS_KEY || key == RATE_UID_SECONDS_KEY
        val ip = key == RATE_IP_SECONDS_KEY || key == RATE_IP_COUNT_KEY
        val value = if (seconds) if(ip) settings.ipSeconds else settings.uidSeconds else if(ip) settings.ipCount else settings.uidCount
        RateNumberDialog(value, seconds, (if(ip) "유동" else "깡계") + if(seconds) " 확인 시간" else " 작성 개수", colors,
            onDismiss = { editing = null }, onSave = { n -> save { putInt(key, n) }; editing = null })
    }
    if(help) AlertDialog(onDismissRequest = { help = false }, containerColor = colors.dialogBg,
        titleContentColor = colors.text, textContentColor = colors.subText,
        title = { Text("연속 작성 감지") }, text = { Text(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), text = "같은 갤러리에서 유동은 같은 표시 IP, 깡계는 같은 계정으로 작성한 글을 셉니다. 제목·본문·최소 글자 수와 관계없이 정한 시간 안의 서로 다른 글이 기준 개수에 도달하면 첫 글부터 처리합니다. 같은 페이지의 남은 글은 이어서 확인하고, 이미 감지한 글은 최대 15분 동안 처리 대상으로 유지합니다. 봇을 끄거나 관련 설정을 바꾸면 대기 대상을 초기화합니다.\n\n일반 회원, 댓글, 보호된 작성자와 차단 예외 글은 제외합니다. 같은 글을 재검사해도 한 개로 셉니다. 반복 내용 감지와 함께 걸리면 한 번만 처리하며 보류가 다른 삭제·차단을 막지 않습니다.\n\n표시 IP는 여러 사람이 공유할 수 있습니다. 봇이 실제로 탐색한 글만 세므로 검사 주기보다 빠른 감지를 보장하지 않습니다. IP·계정을 바꾸는 도배는 놓칠 수 있습니다.") },
        confirmButton = { TextButton(onClick = { help = false }) { Text("닫기",color=colors.accent) } })
}

@Composable
private fun RateTargetSection(title: String, checked: Boolean, enabled: Boolean, tag: String, colors: BotColorScheme,
    toggle: (Boolean)->Unit, seconds: Int, count: Int, editSeconds: ()->Unit, editCount: ()->Unit) {
    SettingsGroup(colors) {
        Row(Modifier.fillMaxWidth().toggleable(checked, enabled=enabled, role=Role.Switch, onValueChange=toggle)
            .testTag("$tag-target").padding(16.dp), verticalAlignment=Alignment.CenterVertically) {
            Text(title, color=colors.text,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
            ModernSettingsSwitch(checked,null,colors,enabled=enabled)
        }
        RateValueRow("확인 시간", "${seconds}초 안에", enabled && checked,"$tag-seconds",colors,editSeconds)
        RateValueRow("작성 개수", "${count}개 이상", enabled && checked,"$tag-count",colors,editCount)
    }
}

@Composable
private fun RateValueRow(label:String,value:String,enabled:Boolean,tag:String,colors:BotColorScheme,edit:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled=enabled,onClick=edit).testTag(tag).padding(horizontal=16.dp,vertical=14.dp),
        verticalAlignment=Alignment.CenterVertically) {
        Text(label,color=colors.text,modifier=Modifier.weight(1f))
        Text(value,color=colors.accent,fontWeight=FontWeight.Bold)
        Spacer(Modifier.width(8.dp));Icon(Icons.Filled.Edit,null,tint=colors.subText,modifier=Modifier.size(18.dp))
    }
}

@Composable
private fun RateNumberDialog(value:Int,seconds:Boolean,title:String,colors:BotColorScheme,onDismiss:()->Unit,onSave:(Int)->Unit) {
    var draft by remember { mutableStateOf(value.toString()) }
    val parsed=parsePostingRateValue(draft,seconds)
    AlertDialog(onDismissRequest=onDismiss,modifier=Modifier.testTag("rate-number-dialog"),containerColor=colors.dialogBg,
        titleContentColor=colors.text,textContentColor=colors.subText,title={Text(title)},text={
            OutlinedTextField(value=draft,onValueChange={draft=it},singleLine=true,label={Text(if(seconds)"시간" else "개수")},
                suffix={Text(if(seconds)"초" else "개")},isError=parsed==null,
                supportingText={Text(if(seconds)"10~3600초 · 검사 주기에 따라 감지가 늦을 수 있습니다." else "2~100개 · 첫 글 포함")},
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth().testTag("rate-number-input"),
                colors=OutlinedTextFieldDefaults.colors(focusedTextColor=colors.text,unfocusedTextColor=colors.text,
                    focusedBorderColor=colors.accent,cursorColor=colors.accent,focusedLabelColor=colors.accent,unfocusedLabelColor=colors.subText,
                    focusedSuffixColor=colors.subText,unfocusedSuffixColor=colors.subText,
                    focusedSupportingTextColor=colors.subText,unfocusedSupportingTextColor=colors.subText))
        },confirmButton={TextButton(enabled=parsed!=null,onClick={parsed?.let(onSave)},modifier=Modifier.testTag("rate-number-save")){Text("저장",color=if(parsed!=null)colors.accent else colors.subText)}},
        dismissButton={TextButton(onClick=onDismiss){Text("취소",color=colors.subText)}})
}
