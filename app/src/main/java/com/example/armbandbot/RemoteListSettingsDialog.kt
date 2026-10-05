package com.heyheyon.armbandbot

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.heyheyon.armbandbot.ui.*
import kotlinx.coroutines.launch

internal fun remoteListLabel(channel:String)=when(channel) {
    "normal"->"일반 금지어";"bypass"->"우회 금지어";"user_blacklist"->"ID/IP 블랙리스트";"user_whitelist"->"ID/IP 화이트리스트"
    "nickname_blacklist"->"닉네임 블랙리스트";"nickname_bypass_blacklist"->"닉네임 우회 블랙리스트";"nickname_whitelist"->"닉네임 화이트리스트";else->error("알 수 없는 목록")
}

/** The cloud consumes its own click; the remaining card still opens the local pencil editor. */
@Composable
internal fun RemoteListTextCard(title:String,content:String,colors:BotColorScheme,p:SharedPreferences,channel:String,onEdit:()->Unit) {
    remember(p) {ensureRemoteListSettings(p);true}
    var open by remember {mutableStateOf(false)}
    var revision by remember {mutableIntStateOf(0)}
    DisposableEffect(p) {
        val listener=SharedPreferences.OnSharedPreferenceChangeListener {_,key->if(key?.startsWith("remote_channel_")==true)revision++}
        p.registerOnSharedPreferenceChangeListener(listener);onDispose {p.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    val active=remember(p,channel,revision) {p.getBoolean(remoteListPrefKey(channel,"enabled"),false)}
    ReadOnlyTextCard(title,content,colors,headerAction={
        IconButton(onClick={open=true},modifier=Modifier.testTag("remote-cloud-$channel")) {
            Icon(Icons.Default.Cloud,"${remoteListLabel(channel)} 원격 목록${if(active)" 사용 중" else " 설정"}",tint=if(active)PastelNavy else colors.subText)
        }
    },onClick=onEdit)
    if(open)RemoteListSettingsDialog(p,channel,colors) {open=false}
}

@Composable
internal fun RemoteListSettingsDialog(p:SharedPreferences,channel:String,colors:BotColorScheme,onDismiss:()->Unit) {
    remember(p) {ensureRemoteListSettings(p);true}
    fun k(field:String)=remoteListPrefKey(channel,field)
    var enabled by remember(p,channel) {mutableStateOf(p.getBoolean(k("enabled"),false))}
    var kind by remember(p,channel) {mutableStateOf(runCatching {RemoteSourceKind.valueOf(p.getString(k("kind"),"GITHUB")!!)}.getOrDefault(RemoteSourceKind.GITHUB))}
    var url by remember(p,channel) {mutableStateOf(p.getString(k("url"),"").orEmpty())}
    var minutes by remember(p,channel) {mutableStateOf(p.getInt(k("interval_minutes"),15).toString())}
    var error by remember {mutableStateOf<String?>(null)}
    var help by remember {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var status by remember {mutableStateOf(p.getString(k("status"),"").orEmpty())}
    val scope=rememberCoroutineScope()
    fun save():Boolean=try {
        if(enabled || url.isNotBlank())resolveRemoteUrl(url.trim(),kind)
        val interval=minutes.toIntOrNull() ?: error("갱신 간격을 확인해 주세요.")
        require(interval in 5..1440) {"갱신 간격은 5~1440분입니다."}
        val format=if(p.getString(k("format"),"SIMPLE")=="LEGACY" && p.getString(k("url"),"")==url.trim() && p.getString(k("kind"),"")==kind.name)"LEGACY" else "SIMPLE"
        check(p.edit().putBoolean(k("enabled"),enabled).putString(k("kind"),kind.name).putString(k("url"),url.trim()).putString(k("format"),format)
            .putInt(k("interval_minutes"),interval).putLong(k("generation"),p.getLong(k("generation"),0)+1).commit()) {"설정을 저장하지 못했습니다."}
        error=null;true
    } catch(e:Exception) {error=e.message ?: "설정을 확인해 주세요.";false}
    AlertDialog(onDismissRequest=onDismiss,modifier=Modifier.testTag("remote-list-dialog"),containerColor=colors.card,
        titleContentColor=colors.text,textContentColor=colors.subText,
        title={Row(verticalAlignment=Alignment.CenterVertically) {
            Text("${remoteListLabel(channel)} · 원격 목록",modifier=Modifier.weight(1f))
            IconButton(onClick={help=true},modifier=Modifier.testTag("remote-list-help")) {Icon(Icons.Default.HelpOutline,"도움말",tint=colors.text)}
        }},
        text={Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("원격 목록 사용",modifier=Modifier.weight(1f),color=colors.text)
                ModernSettingsSwitch(enabled,{enabled=it},colors,Modifier.testTag("remote-list-enabled"))
            }
            RemoteSourceKind.entries.forEach {value->Row(verticalAlignment=Alignment.CenterVertically) {
                RadioButton(kind==value,{kind=value},colors=RadioButtonDefaults.colors(selectedColor=PastelNavy,unselectedColor=colors.subText))
                Text(when(value) {RemoteSourceKind.GITHUB->"GitHub";RemoteSourceKind.SHEETS->"Google Sheets";RemoteSourceKind.JSON->"Apps Script"},color=colors.text)
            }}
            RemoteInput(url,{url=it},"목록 주소",colors,"remote-list-url")
            RemoteInput(minutes,{minutes=it},"갱신 간격 (분)",colors,"remote-list-interval")
            if(p.getString(k("format"),"SIMPLE")=="LEGACY")Text("기존 통합 목록 연결 유지",color=colors.subText)
            error?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("remote-list-error"))}
            if(status.isNotBlank())Text(status,color=colors.subText,modifier=Modifier.testTag("remote-list-status"))
            TextButton(enabled=enabled && !busy,onClick={if(save()) {
                busy=true;scope.launch {try {RemoteListClient().syncList(p,channel,force=true);status=p.getString(k("status"),"").orEmpty()}finally {busy=false}}
            }},modifier=Modifier.testTag("remote-list-refresh")) {Text(if(busy)"확인 중" else "저장 후 지금 갱신",color=PastelNavy)}
        }},
        confirmButton={TextButton(enabled=!busy,onClick={if(save())onDismiss()},modifier=Modifier.testTag("remote-list-save")) {Text("저장",color=PastelNavy)}},
        dismissButton={TextButton(onClick=onDismiss) {Text("취소",color=colors.text)}})
    if(help)AlertDialog(onDismissRequest={help=false},modifier=Modifier.testTag("remote-list-help-dialog"),containerColor=colors.card,titleContentColor=colors.text,textContentColor=colors.subText,
        title={Text("원격 목록 도움말")},text={Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState())) {
            Text("이 목록에 적용할 문자열만 한 줄에 하나씩 적어 주세요. Google Sheets는 제목 없이 한 열에 작성합니다. GitHub는 텍스트 목록, Apps Script는 텍스트 목록 또는 문자열 배열을 반환하면 됩니다.\n\n각 목록은 서로 다른 주소를 사용할 수 있습니다. 직접 입력한 목록과 함께 적용되며, 해당 필터가 켜져 있어야 작동합니다.\n\n로그인 없이 읽을 수 있는 전용 목록만 지원합니다. 수신 실패 시 같은 원본의 마지막 정상 목록을 유지하며, 끄면 직접 입력한 목록만 사용합니다. 기존 통합 연결은 주소를 바꾸기 전까지 그대로 유지됩니다.")
        }},confirmButton={TextButton(onClick={help=false}) {Text("닫기",color=PastelNavy)}})
}

@Composable
private fun RemoteInput(value:String,onChange:(String)->Unit,label:String,colors:BotColorScheme,tag:String) {
    OutlinedTextField(value,onChange,label={Text(label)},singleLine=true,modifier=Modifier.fillMaxWidth().testTag(tag),
        colors=OutlinedTextFieldDefaults.colors(focusedTextColor=colors.text,unfocusedTextColor=colors.text,focusedLabelColor=colors.text,unfocusedLabelColor=colors.subText,focusedBorderColor=PastelNavy,unfocusedBorderColor=colors.subText))
}
