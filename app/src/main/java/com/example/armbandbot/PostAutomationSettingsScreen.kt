package com.heyheyon.armbandbot

import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.heyheyon.armbandbot.ui.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import org.jsoup.Jsoup

/** Drafts are local to this page. Closing the page never publishes unfinished edits. */
@Composable
internal fun PostAutomationSettingsScreen(p: SharedPreferences, colors: BotColorScheme, page: AutomationSettingsPage = AutomationSettingsPage.BUMP, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var showHelp by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var bumpEnabled by remember { mutableStateOf(p.getBoolean(BUMP_ENABLED_KEY, false)) }
    var moveEnabled by remember { mutableStateOf(p.getBoolean(MOVE_ENABLED_KEY, false)) }
    var remoteEnabled by remember { mutableStateOf(p.getBoolean(REMOTE_ENABLED_KEY, false)) }
    var bumps by remember { mutableStateOf(if(page==AutomationSettingsPage.BUMP) runCatching { parseBumpRules(p.getString(BUMP_RULES_KEY,"[]")!!) }.getOrElse { error=it.message; emptyList() } else emptyList()) }
    var moves by remember { mutableStateOf(if(page==AutomationSettingsPage.TAB) runCatching { parseTabMoveRules(p.getString(MOVE_RULES_KEY,"[]")!!) }.getOrElse { error=it.message; emptyList() } else emptyList()) }
    var bumpUrl by remember { mutableStateOf("") }
    var bumpTimes by remember { mutableStateOf("09:00") }
    var bumpEditId by remember { mutableStateOf<String?>(null) }
    var moveKeyword by remember { mutableStateOf("") }
    var moveEditId by remember { mutableStateOf<String?>(null) }
    val galleries = remember(p) { configuredAutomationGalleries(p).toList() }
    var gallery by remember { mutableStateOf(galleries.firstOrNull()) }
    var categories by remember { mutableStateOf<List<GalleryCategory>>(emptyList()) }
    var destination by remember { mutableStateOf<GalleryCategory?>(null) }
    var sourceKind by remember { mutableStateOf(runCatching { RemoteSourceKind.valueOf(p.getString("remote_lists_kind","GITHUB")!!) }.getOrDefault(RemoteSourceKind.GITHUB)) }
    var remoteUrl by remember { mutableStateOf(p.getString("remote_lists_url","")!!) }
    var interval by remember { mutableStateOf(p.getInt("remote_lists_interval_minutes",15).toString()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(p.getString("remote_lists_status","아직 수신하지 않았습니다.")!!) }
    fun attempt(action: () -> Unit) { try { action(); error=null } catch(e: Exception) { error=e.message ?: "설정을 확인하세요." } }
    BackHandler(onBack=onBack)
    Column(Modifier.fillMaxSize().background(colors.bg).testTag("automation-page")) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack,modifier=Modifier.testTag("automation-back")) { Icon(Icons.Default.ArrowBack,"뒤로",tint=colors.text) }
            Text(page.title, color=colors.text,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
            IconButton(onClick={showHelp=true},modifier=Modifier.testTag("automation-help")) { Icon(Icons.Default.HelpOutline,"도움말",tint=colors.text) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            error?.let { Text(it, color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("automation-error")) }
            if(page==AutomationSettingsPage.BUMP) {
            ModernSettingsBlock("예약 목록","매일 지정한 시각에 실행",Icons.Default.Schedule,colors,trailing={
                ModernSettingsSwitch(bumpEnabled,{ next -> attempt {
                    if(next) require(bumps.isNotEmpty()) { "예약을 먼저 저장하세요." }
                    check(p.edit().putBoolean(BUMP_ENABLED_KEY,next).commit()); bumpEnabled=next
                } },colors,Modifier.testTag("bump-enabled"))
            }) {

                bumps.forEach { rule ->
                    Text("${strictAutomationPost(rule.url).key.postNo} · ${rule.minutes.joinToString { formatMinuteOfDay(it) }}",color=colors.text)
                    Row {
                        TextButton(onClick={ bumpEditId=rule.id; bumpUrl=rule.url; bumpTimes=rule.minutes.joinToString { formatMinuteOfDay(it) } }) { Text("수정") }
                        TextButton(onClick={ attempt { val next=bumps.filterNot { it.id==rule.id };check(p.edit().putString(BUMP_RULES_KEY,encodeBumpRules(next)).commit());bumps=next } }) { Text("삭제") }
                    }
                }
                AutomationField(bumpUrl,{bumpUrl=it},"게시글 주소",colors,"bump-url")
                AutomationField(bumpTimes,{bumpTimes=it},"시각 (09:00, 18:30)",colors,"bump-times")
                Row {
                    TextButton(onClick={ attempt {
                        val key=strictAutomationPost(bumpUrl.trim()).key
                        require(key.gallType to key.gallId in configuredAutomationGalleries(p)) { "봇이 관리하는 갤러리의 글만 예약할 수 있습니다." }
                        val rule=BumpRule(bumpEditId ?: UUID.randomUUID().toString(),bumpUrl.trim(),parseBumpTimes(bumpTimes))
                        val next=if(bumpEditId==null) bumps+rule else bumps.map { if(it.id==rule.id)rule else it }
                        require(next.size<=64);check(p.edit().putString(BUMP_RULES_KEY,encodeBumpRules(next)).commit()); bumps=next; bumpEditId=null; bumpUrl=""
                    } },modifier=Modifier.testTag("bump-save")) { Text(if(bumpEditId==null)"예약 추가" else "수정 저장") }
                    if(bumpEditId!=null)TextButton(onClick={bumpEditId=null;bumpUrl=""}) { Text("취소") }
                }
                Text(p.getString("automation_last_status","").orEmpty(),color=colors.subText)
            }
            }
            if(page==AutomationSettingsPage.TAB) {
            ModernSettingsBlock("분류 규칙","제목·본문의 키워드로 분류",Icons.Default.SwapHoriz,colors,trailing={
                ModernSettingsSwitch(moveEnabled,{next->attempt {
                    if(next)require(moves.isNotEmpty()){ "이동 규칙을 먼저 저장하세요." }
                    check(p.edit().putBoolean(MOVE_ENABLED_KEY,next).commit());moveEnabled=next
                }},colors,Modifier.testTag("move-enabled"))
            }) {

                moves.forEach { rule ->
                    Text("${rule.gallId} · ${rule.keyword} → ${rule.label}",color=colors.text)
                    Row {
                        TextButton(onClick={moveEditId=rule.id;moveKeyword=rule.keyword;gallery=rule.gallType to rule.gallId;categories=emptyList();destination=null}) { Text("수정") }
                        TextButton(onClick={attempt{val next=moves.filterNot{it.id==rule.id};check(p.edit().putString(MOVE_RULES_KEY,encodeTabMoveRules(next)).commit());moves=next}}) { Text("삭제") }
                    }
                }
                if(galleries.isEmpty()) Text("관리할 마이너·미니갤을 먼저 저장하세요.",color=colors.subText)
                galleries.forEach { g -> Row(verticalAlignment=Alignment.CenterVertically) {
                    RadioButton(gallery==g,{gallery=g;categories=emptyList();destination=null},colors=RadioButtonDefaults.colors(selectedColor=PastelNavy,unselectedColor=colors.subText))
                    Text(g.second,color=colors.text)
                } }
                TextButton(enabled=gallery!=null && !busy,onClick={
                    val target=gallery ?: return@TextButton
                    val cookie=p.getString("saved_cookie","").orEmpty()
                    busy=true
                    scope.launch {
                        try {
                            val loaded=withContext(Dispatchers.IO) {
                                val doc=Jsoup.connect(automationGalleryUrl(target)).userAgent("Mozilla/5.0").header("Cookie",cookie).followRedirects(false).timeout(15000).maxBodySize(524288).get()
                                require(automationManagerConfirmed(doc)) { "갤러리 관리 권한을 확인하세요." }
                                automationTabs(doc).also { require(it.isNotEmpty()) { "사용 가능한 탭을 찾지 못했습니다." } }
                            }
                            if(gallery==target){categories=loaded;destination=null};error=null
                        } catch(e:kotlinx.coroutines.CancellationException){throw e} catch(e:Exception){error=e.message ?: "탭 조회 실패"} finally {busy=false}
                    }
                }) { Text("갤러리 탭 불러오기") }
                categories.forEach { category -> Row(verticalAlignment=Alignment.CenterVertically) {
                    RadioButton(destination==category,{destination=category},colors=RadioButtonDefaults.colors(selectedColor=PastelNavy,unselectedColor=colors.subText))
                    Text(category.label,color=colors.text)
                } }
                if(destination?.obstruct==true)Text("이 탭으로 이동하면 디시의 개념글 지정이 해제될 수 있습니다.",color=MaterialTheme.colorScheme.error)
                AutomationField(moveKeyword,{moveKeyword=it},"키워드",colors,"move-keyword")
                Row {
                    TextButton(onClick={attempt{
                        val g=gallery ?: error("관리 갤러리를 선택하세요.")
                        val d=destination ?: error("탭을 불러온 뒤 대상 탭을 선택하세요.")
                        val rule=TabMoveRule(moveEditId ?: UUID.randomUUID().toString(),g.first,g.second,moveKeyword.trim(),d.id,d.label,d.obstruct)
                        val next=if(moveEditId==null)moves+rule else moves.map{if(it.id==rule.id)rule else it}
                        require(next.size<=64);check(p.edit().putString(MOVE_RULES_KEY,encodeTabMoveRules(next)).commit());moves=next;moveEditId=null;moveKeyword=""
                    }},modifier=Modifier.testTag("move-save")) { Text(if(moveEditId==null)"규칙 추가" else "수정 저장") }
                    if(moveEditId!=null)TextButton(onClick={moveEditId=null;moveKeyword=""}) { Text("취소") }
                }
            }
            }
            if(page==AutomationSettingsPage.REMOTE) {
            ModernSettingsBlock("목록 연결","받은 목록을 로컬 목록과 함께 사용",Icons.Default.CloudDownload,colors,trailing={
                ModernSettingsSwitch(remoteEnabled,{next->attempt{
                    if(next)resolveRemoteUrl(p.getString("remote_lists_url","").orEmpty(),RemoteSourceKind.valueOf(p.getString("remote_lists_kind","GITHUB")!!))
                    check(p.edit().putBoolean(REMOTE_ENABLED_KEY,next).commit());remoteEnabled=next
                }},colors,Modifier.testTag("remote-enabled"))
            }) {
                RemoteSourceKind.entries.forEach { k -> Row(verticalAlignment=Alignment.CenterVertically) {
                    RadioButton(sourceKind==k,{sourceKind=k},colors=RadioButtonDefaults.colors(selectedColor=PastelNavy,unselectedColor=colors.subText))
                    Text(when(k){RemoteSourceKind.GITHUB->"GitHub";RemoteSourceKind.SHEETS->"Google Sheets";RemoteSourceKind.JSON->"Apps Script"},color=colors.text)
                } }
                AutomationField(remoteUrl,{remoteUrl=it},"목록 주소",colors,"remote-url")
                AutomationField(interval,{interval=it},"갱신 간격 (5~1440분)",colors,"remote-interval")


                Row {
                    TextButton(onClick={attempt{
                        resolveRemoteUrl(remoteUrl,sourceKind);val minutes=interval.toIntOrNull() ?: error("갱신 간격을 확인하세요.");require(minutes in 5..1440){"갱신 간격은 5~1440분입니다."}
                        check(p.edit().putString("remote_lists_url",remoteUrl.trim()).putString("remote_lists_kind",sourceKind.name).putInt("remote_lists_interval_minutes",minutes).commit())
                        status="주소 저장됨 · 동기화 후 적용"
                    }},modifier=Modifier.testTag("remote-save")) { Text("주소 저장") }
                    TextButton(enabled=remoteEnabled && !busy,onClick={
                        busy=true;scope.launch { try { RemoteListClient().sync(p,force=true);status=p.getString("remote_lists_status","").orEmpty() } finally {busy=false} }
                    },modifier=Modifier.testTag("remote-refresh")) { Text(if(busy)"확인 중" else "지금 갱신") }
                }
                Text(status,color=colors.subText,modifier=Modifier.testTag("remote-status"))
                Spacer(Modifier.height(12.dp))
            }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if(showHelp) AutomationSettingsHelp(page,colors) { showHelp=false }
}
@Composable
private fun AutomationField(value:String,onChange:(String)->Unit,label:String,colors:BotColorScheme,tag:String) {
    OutlinedTextField(value,onChange,label={Text(label)},modifier=Modifier.fillMaxWidth().padding(vertical=4.dp).testTag(tag),singleLine=true,
        colors=OutlinedTextFieldDefaults.colors(focusedTextColor=colors.text,unfocusedTextColor=colors.text,focusedLabelColor=colors.text,unfocusedLabelColor=colors.subText,focusedBorderColor=PastelNavy,unfocusedBorderColor=colors.subText))
}
