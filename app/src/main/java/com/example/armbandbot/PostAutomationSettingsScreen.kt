package com.heyheyon.armbandbot

import android.app.TimePickerDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.heyheyon.armbandbot.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import org.jsoup.Jsoup

internal suspend fun loadManagedGalleryTabs(target:Pair<String,String>,cookie:String):List<GalleryCategory> = withContext(Dispatchers.IO) {
    require(cookie.isNotBlank()) {"갤러리 관리 계정으로 먼저 로그인해 주세요."}
    val doc=Jsoup.connect(automationGalleryUrl(target)).userAgent("Mozilla/5.0").header("Cookie",cookie).followRedirects(false).timeout(15000).maxBodySize(524288).get()
    require(automationManagerConfirmed(doc)) {"${target.second}의 관리 권한을 확인해 주세요."}
    automationTabs(doc).also {require(it.isNotEmpty()) {"사용 가능한 탭을 찾지 못했습니다."}}
}

/** Per-page drafts are not published by Back; each gallery has its own category identity. */
@Composable
internal fun PostAutomationSettingsScreen(p:SharedPreferences,colors:BotColorScheme,page:AutomationSettingsPage=AutomationSettingsPage.BUMP,
    loadTabs:suspend(Pair<String,String>,String)->List<GalleryCategory> = ::loadManagedGalleryTabs,onBack:()->Unit) {
    val context=LocalContext.current
    var showHelp by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var bumpEnabled by remember {mutableStateOf(p.getBoolean(BUMP_ENABLED_KEY,false))}
    var moveEnabled by remember {mutableStateOf(p.getBoolean(MOVE_ENABLED_KEY,false))}
    var bumps by remember {mutableStateOf(if(page==AutomationSettingsPage.BUMP)runCatching {parseBumpRules(p.getString(BUMP_RULES_KEY,"[]")!!)}.getOrElse {error=it.message;emptyList()}else emptyList())}
    var moves by remember {mutableStateOf(if(page==AutomationSettingsPage.TAB)runCatching {parseTabMoveRules(p.getString(MOVE_RULES_KEY,"[]")!!)}.getOrElse {error=it.message;emptyList()}else emptyList())}
    var bumpUrl by remember {mutableStateOf("")}
    var bumpTimes by remember {mutableStateOf(listOf(540))}
    var bumpEditId by remember {mutableStateOf<String?>(null)}
    var moveKeyword by remember {mutableStateOf("")}
    var moveEditId by remember {mutableStateOf<String?>(null)}
    var targetRevision by remember {mutableIntStateOf(0)}
    DisposableEffect(p) {
        val listener=SharedPreferences.OnSharedPreferenceChangeListener {_,key->if(key=="target_urls")targetRevision++}
        p.registerOnSharedPreferenceChangeListener(listener);onDispose {p.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    val galleries=remember(p,targetRevision) {configuredAutomationGalleries(p).toList()}
    var gallery by remember {mutableStateOf(galleries.firstOrNull())}
    var categories by remember {mutableStateOf<List<GalleryCategory>>(emptyList())}
    var destination by remember {mutableStateOf<GalleryCategory?>(null)}
    var busy by remember {mutableStateOf(false)}
    var refreshTabs by remember {mutableIntStateOf(0)}
    LaunchedEffect(galleries) {if(gallery !in galleries)gallery=galleries.firstOrNull()}
    LaunchedEffect(page,gallery,moveEditId,refreshTabs) {
        if(page!=AutomationSettingsPage.TAB)return@LaunchedEffect
        val target=gallery
        categories=emptyList();destination=null;error=null
        if(target==null)return@LaunchedEffect
        busy=true
        try {
            val loaded=loadTabs(target,p.getString("saved_cookie","").orEmpty())
            if(gallery==target) {
                categories=loaded
                destination=moves.firstOrNull {it.id==moveEditId && it.gallType==target.first && it.gallId==target.second}?.headtext?.let {id->loaded.firstOrNull {it.id==id}}
            }
        } catch(e:kotlinx.coroutines.CancellationException) {throw e}
        catch(e:Exception) {if(gallery==target)error=e.message ?: "탭 조회에 실패했습니다."}
        finally {busy=false}
    }
    fun attempt(action:()->Unit) {try {action();error=null}catch(e:Exception) {error=e.message ?: "설정을 확인해 주세요."}}
    fun pickTime(previous:Int?) {
        val initial=previous ?: 540
        TimePickerDialog(context,{_,hour,minute->attempt {bumpTimes=updateBumpTimeSelection(bumpTimes,previous,hour*60+minute)}},initial/60,initial%60,true).show()
    }
    BackHandler(onBack=onBack)
    Column(Modifier.fillMaxSize().background(colors.bg).testTag("automation-page")) {
        Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack,modifier=Modifier.testTag("automation-back")) {Icon(Icons.Default.ArrowBack,"뒤로",tint=colors.text)}
            Text(page.title,color=colors.text,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
            IconButton(onClick={showHelp=true},modifier=Modifier.testTag("automation-help")) {Icon(Icons.Default.HelpOutline,"도움말",tint=colors.text)}
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            error?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("automation-error"))}
            if(page==AutomationSettingsPage.BUMP)ModernSettingsBlock("예약 목록","매일 지정한 시각에 실행",Icons.Default.Schedule,colors,trailing={
                ModernSettingsSwitch(bumpEnabled,{next->attempt {
                    if(next)require(bumps.isNotEmpty()) {"예약을 먼저 저장해 주세요."}
                    check(p.edit().putBoolean(BUMP_ENABLED_KEY,next).commit());bumpEnabled=next
                }},colors,Modifier.testTag("bump-enabled"))
            }) {
                bumps.forEach {rule->
                    Text("${strictAutomationPost(rule.url).key.postNo} · ${rule.minutes.joinToString {formatMinuteOfDay(it)}}",color=colors.text)
                    Row {
                        TextButton(onClick={bumpEditId=rule.id;bumpUrl=rule.url;bumpTimes=rule.minutes}) {Text("수정",color=PastelNavy)}
                        TextButton(onClick={attempt {val next=bumps.filterNot {it.id==rule.id};check(p.edit().putString(BUMP_RULES_KEY,encodeBumpRules(next)).commit());bumps=next}}) {Text("삭제",color=colors.subText)}
                    }
                }
                AutomationField(bumpUrl,{bumpUrl=it},"게시글 주소",colors,"bump-url")
                Text("실행 시각",color=colors.subText)
                bumpTimes.forEach {minute->Row(verticalAlignment=Alignment.CenterVertically) {
                    OutlinedButton(onClick={pickTime(minute)},modifier=Modifier.weight(1f).testTag("bump-time-$minute")) {Text(formatMinuteOfDay(minute),color=colors.text)}
                    IconButton(onClick={bumpTimes=updateBumpTimeSelection(bumpTimes,minute,null)},modifier=Modifier.testTag("bump-time-remove-$minute")) {Icon(Icons.Default.Close,"시각 삭제",tint=colors.subText)}
                }}
                TextButton(enabled=bumpTimes.size<24,onClick={pickTime(null)},modifier=Modifier.testTag("bump-time-add")) {Text("시각 추가",color=PastelNavy)}
                Row {
                    TextButton(onClick={attempt {
                        val key=strictAutomationPost(bumpUrl.trim()).key
                        require(key.gallType to key.gallId in configuredAutomationGalleries(p)) {"봇이 관리하는 갤러리의 글만 예약할 수 있습니다."}
                        require(bumpTimes.isNotEmpty()) {"실행 시각을 하나 이상 선택해 주세요."}
                        val rule=BumpRule(bumpEditId ?: UUID.randomUUID().toString(),bumpUrl.trim(),bumpTimes)
                        val next=if(bumpEditId==null)bumps+rule else bumps.map {if(it.id==rule.id)rule else it}
                        require(next.size<=64);check(p.edit().putString(BUMP_RULES_KEY,encodeBumpRules(next)).commit())
                        bumps=next;bumpEditId=null;bumpUrl="";bumpTimes=listOf(540)
                    }},modifier=Modifier.testTag("bump-save")) {Text(if(bumpEditId==null)"예약 추가" else "수정 저장",color=PastelNavy)}
                    if(bumpEditId!=null)TextButton(onClick={bumpEditId=null;bumpUrl="";bumpTimes=listOf(540)}) {Text("취소",color=colors.text)}
                }
                Text(p.getString("automation_last_status","").orEmpty(),color=colors.subText)
            }
            if(page==AutomationSettingsPage.TAB)ModernSettingsBlock("분류 규칙","갤러리별 키워드로 분류",Icons.Default.SwapHoriz,colors,trailing={
                ModernSettingsSwitch(moveEnabled,{next->attempt {
                    if(next)require(moves.isNotEmpty()) {"분류 규칙을 먼저 저장해 주세요."}
                    check(p.edit().putBoolean(MOVE_ENABLED_KEY,next).commit());moveEnabled=next
                }},colors,Modifier.testTag("move-enabled"))
            }) {
                if(galleries.isEmpty())Text("관리할 갤러리 주소를 먼저 저장해 주세요.",color=colors.subText)
                else AutomationMenu("관리 갤러리",gallery?.let {galleryLabel(it)} ?: "갤러리 선택",galleries.map {it to galleryLabel(it)},colors,"move-gallery-menu") {next->gallery=next;categories=emptyList();destination=null}
                moves.filter {it.gallType to it.gallId==gallery}.forEach {rule->
                    Text("${rule.keyword} → ${rule.label}",color=colors.text)
                    Row {
                        TextButton(onClick={moveEditId=rule.id;moveKeyword=rule.keyword;destination=categories.firstOrNull {it.id==rule.headtext}}) {Text("수정",color=PastelNavy)}
                        TextButton(onClick={attempt {val next=moves.filterNot {it.id==rule.id};check(p.edit().putString(MOVE_RULES_KEY,encodeTabMoveRules(next)).commit());moves=next}}) {Text("삭제",color=colors.subText)}
                    }
                }
                if(busy)Text("갤러리 탭을 불러오는 중…",color=colors.subText)
                TextButton(enabled=gallery!=null && !busy,onClick={refreshTabs++}) {Text("탭 새로고침",color=PastelNavy)}
                if(categories.isNotEmpty())AutomationMenu("이동할 탭",destination?.label ?: "탭 선택",categories.map {it to it.label},colors,"move-target-menu") {destination=it}
                if(destination?.obstruct==true)Text("이 탭으로 이동하면 개념글 지정이 해제될 수 있습니다.",color=MaterialTheme.colorScheme.error)
                AutomationField(moveKeyword,{moveKeyword=it},"키워드",colors,"move-keyword")
                Row {
                    TextButton(enabled=!busy,onClick={attempt {
                        val g=gallery ?: error("관리 갤러리를 선택해 주세요.")
                        require(g in configuredAutomationGalleries(p)) {"관리할 갤러리 주소를 다시 확인해 주세요."}
                        val d=destination ?: error("이동할 탭을 선택해 주세요.")
                        require(d in categories)
                        val rule=TabMoveRule(moveEditId ?: UUID.randomUUID().toString(),g.first,g.second,moveKeyword.trim(),d.id,d.label,d.obstruct)
                        val next=if(moveEditId==null)moves+rule else moves.map {if(it.id==rule.id)rule else it}
                        require(next.size<=64);check(p.edit().putString(MOVE_RULES_KEY,encodeTabMoveRules(next)).commit());moves=next;moveEditId=null;moveKeyword=""
                    }},modifier=Modifier.testTag("move-save")) {Text(if(moveEditId==null)"규칙 추가" else "수정 저장",color=PastelNavy)}
                    if(moveEditId!=null)TextButton(onClick={moveEditId=null;moveKeyword=""}) {Text("취소",color=colors.text)}
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if(showHelp)AutomationSettingsHelp(page,colors) {showHelp=false}
}
private fun galleryLabel(g:Pair<String,String>)="${g.second} · ${if(g.first=="MI")"미니갤" else "마이너갤"}"
@Composable
private fun <T> AutomationMenu(label:String,value:String,options:List<Pair<T,String>>,colors:BotColorScheme,tag:String,onSelect:(T)->Unit) {
    var open by remember {mutableStateOf(false)}
    Text(label,color=colors.subText)
    Box {
        OutlinedButton(onClick={open=true},modifier=Modifier.fillMaxWidth().testTag(tag)) {Text(value,color=colors.text,modifier=Modifier.weight(1f));Icon(Icons.Default.ArrowDropDown,null,tint=colors.subText)}
        DropdownMenu(expanded=open,onDismissRequest={open=false},containerColor=colors.card) {
            options.forEach {(option,title)->DropdownMenuItem(text={Text(title,color=colors.text)},onClick={open=false;onSelect(option)})}
        }
    }
}
@Composable
private fun AutomationField(value:String,onChange:(String)->Unit,label:String,colors:BotColorScheme,tag:String) {
    OutlinedTextField(value,onChange,label={Text(label)},modifier=Modifier.fillMaxWidth().padding(vertical=4.dp).testTag(tag),singleLine=true,
        colors=OutlinedTextFieldDefaults.colors(focusedTextColor=colors.text,unfocusedTextColor=colors.text,focusedLabelColor=colors.text,unfocusedLabelColor=colors.subText,focusedBorderColor=PastelNavy,unfocusedBorderColor=colors.subText))
}
