package com.heyheyon.armbandbot

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heyheyon.armbandbot.ui.*

internal enum class AutomationSettingsPage(val route: String, val title: String) {
    BUMP("BUMP_SETTINGS", "예약 끌올"),
    TAB("TAB_CLASSIFICATION", "자동 탭 분류");
    companion object { fun forRoute(route:String?) = entries.firstOrNull { it.route == route } }
}

@Composable
internal fun ManagementAutomationSettings(
    p:SharedPreferences, colors:BotColorScheme, refreshEnabled:Boolean,
    onRefreshEnabledChange:(Boolean)->Unit, onOpen:(String)->Unit
) {
    Text("관리 자동화",fontWeight=FontWeight.Bold,fontSize=13.sp,color=PastelNavy,
        modifier=Modifier.padding(start=4.dp,bottom=4.dp))
    Box(Modifier.testTag("gallery-refresh-entry")) {
        ModernSettingItem("갤러리 설정 자동 갱신","VPN·통신사·첨부 제한 유지",Icons.Default.Refresh,
            colors,refreshEnabled,onRefreshEnabledChange) { onOpen("GALLERY_REFRESH") }
    }
    AutomationFeatureSettingItem(p,colors,AutomationSettingsPage.BUMP,onOpen)
    AutomationFeatureSettingItem(p,colors,AutomationSettingsPage.TAB,onOpen)
}

@Composable
private fun AutomationFeatureSettingItem(p:SharedPreferences,colors:BotColorScheme,page:AutomationSettingsPage,onOpen:(String)->Unit) {
    val key=when(page) { AutomationSettingsPage.BUMP->BUMP_ENABLED_KEY;AutomationSettingsPage.TAB->MOVE_ENABLED_KEY }
    val tag=when(page) { AutomationSettingsPage.BUMP->"bump-entry";AutomationSettingsPage.TAB->"move-entry" }
    var revision by remember(p) { mutableIntStateOf(0) }
    DisposableEffect(p) {
        val listener=SharedPreferences.OnSharedPreferenceChangeListener { _,_->revision++ }
        p.registerOnSharedPreferenceChangeListener(listener)
        onDispose { p.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val checked=remember(p,key,revision) { p.getBoolean(key,false) }
    val summary=remember(p,page,revision) { when(page) {
        AutomationSettingsPage.BUMP->runCatching { "예약 ${parseBumpRules(p.getString(BUMP_RULES_KEY,"[]")!!).size}개" }.getOrDefault("예약 설정 확인")
        AutomationSettingsPage.TAB->runCatching { "분류 규칙 ${parseTabMoveRules(p.getString(MOVE_RULES_KEY,"[]")!!).size}개" }.getOrDefault("분류 규칙 확인")
    } }
    var error by remember(p,page) { mutableStateOf<String?>(null) }
    val icon=when(page) { AutomationSettingsPage.BUMP->Icons.Default.Schedule;AutomationSettingsPage.TAB->Icons.Default.SwapHoriz }
    Box(Modifier.testTag(tag)) {
        ModernSettingItem(page.title,summary,icon,colors,checked,{next ->
            try {
                if(next) when(page) {
                    AutomationSettingsPage.BUMP->require(parseBumpRules(p.getString(BUMP_RULES_KEY,"[]")!!).isNotEmpty()) { "예약을 먼저 추가해 주세요." }
                    AutomationSettingsPage.TAB->require(parseTabMoveRules(p.getString(MOVE_RULES_KEY,"[]")!!).isNotEmpty()) { "분류 규칙을 먼저 추가해 주세요." }
                }
                check(p.edit().putBoolean(key,next).commit()) { "설정을 저장하지 못했습니다." }
            } catch(e:Exception) { error=e.message ?: "상세 설정을 확인해 주세요." }
        }) { onOpen(page.route) }
    }
    if(error!=null) AlertDialog(onDismissRequest={error=null},modifier=Modifier.testTag("automation-enable-error"),
        containerColor=colors.card,titleContentColor=colors.text,textContentColor=colors.subText,
        title={Text(page.title)},text={Text(error.orEmpty())},
        confirmButton={TextButton(onClick={error=null;onOpen(page.route)}) { Text("설정하기",color=PastelNavy) }},
        dismissButton={TextButton(onClick={error=null}) { Text("닫기",color=colors.text) }})
}

@Composable
internal fun AutomationSettingsHelp(page:AutomationSettingsPage,colors:BotColorScheme,onDismiss:()->Unit) {
    val guidance=when(page) {
        AutomationSettingsPage.BUMP->"관리하는 갤러리의 일반 글을 매일 지정한 시각에 끌올합니다. 시각 버튼을 눌러 고르거나 추가해 주세요.\n\n봇이 작동 중일 때만 실행하며, 15분 이내 늦어진 예약만 처리합니다. 앱 강제 종료·절전·로그인 만료 시 정각 실행은 보장되지 않습니다. 공지·고정글은 제외합니다."
        AutomationSettingsPage.TAB->"제목·본문에 키워드가 포함된 게시글을 선택한 탭으로 옮깁니다. 일반 키워드는 입력한 문자열 그대로, 우회 키워드는 글자 사이의 공백·특수문자를 무시하고 찾습니다. 목록을 눌러 줄바꿈으로 입력하고 규칙을 저장해 주세요. 두 목록 중 하나만 입력해도 됩니다.\n\n각 갤러리의 규칙을 따로 적용하며, 해당 갤러리에서 등록 순서의 첫 번째 규칙을 적용합니다. PC·모바일 주소를 모두 인식합니다.\n\n화이트리스트·예외 글은 건너뛰며, 삭제·차단·검토가 우선합니다. 댓글은 분류하지 않습니다. 탭 설정에 따라 개념글 지정이 해제될 수 있습니다."
    }
    AlertDialog(onDismissRequest=onDismiss,modifier=Modifier.testTag("automation-help-dialog"),containerColor=colors.card,
        titleContentColor=colors.text,textContentColor=colors.subText,title={Text("${page.title} 도움말")},
        text={androidx.compose.foundation.rememberScrollState().let { scroll ->
            Column(Modifier.heightIn(max=400.dp).then(Modifier.verticalScroll(scroll))) { Text(guidance) }
        }},confirmButton={TextButton(onClick=onDismiss) { Text("닫기",color=PastelNavy) }})
}
