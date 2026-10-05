package com.heyheyon.armbandbot

import android.app.TimePickerDialog
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.launch
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
    var normalKeywordsText by remember {mutableStateOf("")}
    var bypassKeywordsText by remember {mutableStateOf("")}
    var keywordEditor by remember {mutableStateOf<String?>(null)}
    val scroll=rememberScrollState()
    val scope=rememberCoroutineScope()
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
        SettingsDetailHeader(page.title,colors,onBack,Modifier.testTag("automation-back")) {
            IconButton(onClick={showHelp=true},modifier=Modifier.size(44.dp).testTag("automation-help")) {
                Icon(Icons.Default.HelpOutline,"도움말",tint=colors.accent)
            }
        }
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
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
                        TextButton(onClick={bumpEditId=rule.id;bumpUrl=rule.url;bumpTimes=rule.minutes}) {Text("수정",color=colors.accent)}
                        TextButton(onClick={attempt {val next=bumps.filterNot {it.id==rule.id};check(p.edit().putString(BUMP_RULES_KEY,encodeBumpRules(next)).commit());bumps=next}}) {Text("삭제",color=colors.subText)}
                    }
                }
                AutomationField(bumpUrl,{bumpUrl=it},"게시글 주소",colors,"bump-url")
                Text("실행 시각",color=colors.subText)
                bumpTimes.forEach {minute->Row(verticalAlignment=Alignment.CenterVertically) {
                    OutlinedButton(onClick={pickTime(minute)},modifier=Modifier.weight(1f).testTag("bump-time-$minute")) {Text(formatMinuteOfDay(minute),color=colors.text)}
                    IconButton(onClick={bumpTimes=updateBumpTimeSelection(bumpTimes,minute,null)},modifier=Modifier.testTag("bump-time-remove-$minute")) {Icon(Icons.Default.Close,"시각 삭제",tint=colors.subText)}
                }}
                TextButton(enabled=bumpTimes.size<24,onClick={pickTime(null)},modifier=Modifier.testTag("bump-time-add")) {Text("시각 추가",color=colors.accent)}
                Row {
                    TextButton(onClick={attempt {
                        val key=strictAutomationPost(bumpUrl.trim()).key
                        require(key.gallType to key.gallId in configuredAutomationGalleries(p)) {"봇이 관리하는 갤러리의 글만 예약할 수 있습니다."}
                        require(bumpTimes.isNotEmpty()) {"실행 시각을 하나 이상 선택해 주세요."}
                        val rule=BumpRule(bumpEditId ?: UUID.randomUUID().toString(),bumpUrl.trim(),bumpTimes)
                        val next=if(bumpEditId==null)bumps+rule else bumps.map {if(it.id==rule.id)rule else it}
                        require(next.size<=64);check(p.edit().putString(BUMP_RULES_KEY,encodeBumpRules(next)).commit())
                        bumps=next;bumpEditId=null;bumpUrl="";bumpTimes=listOf(540)
                    }},modifier=Modifier.testTag("bump-save")) {Text(if(bumpEditId==null)"예약 추가" else "수정 저장",color=colors.accent)}
                    if(bumpEditId!=null)TextButton(onClick={bumpEditId=null;bumpUrl="";bumpTimes=listOf(540)}) {Text("취소",color=colors.text)}
                }
                Text(p.getString("automation_last_status","").orEmpty(),color=colors.subText)
            }
            if(page==AutomationSettingsPage.TAB) {
                ModernSettingsBlock("자동 탭 분류 사용","제목·본문의 키워드로 게시글 분류",Icons.Default.SwapHoriz,colors,trailing={
                    ModernSettingsSwitch(moveEnabled,{next->attempt {
                        if(next)require(moves.isNotEmpty()) {"분류 규칙을 먼저 저장해 주세요."}
                        check(p.edit().putBoolean(MOVE_ENABLED_KEY,next).commit());moveEnabled=next
                    }},colors,Modifier.testTag("move-enabled"))
                })
                AutomationCard(colors) {
                    if(galleries.isEmpty())Text("관리할 갤러리 주소를 먼저 저장해 주세요.",color=colors.subText)
                    else AutomationMenu("관리 갤러리",gallery?.let {galleryLabel(it)} ?: "갤러리 선택",galleries.map {it to galleryLabel(it)},colors,"move-gallery-menu") {next->
                        if(gallery!=next) {
                            gallery=next;categories=emptyList();destination=null
                            moveEditId=null;normalKeywordsText="";bypassKeywordsText="";keywordEditor=null
                        }
                    }
                }
                AutomationCard(colors) {
                    Text(if(moveEditId==null) "새 분류 규칙" else "분류 규칙 수정",fontWeight=FontWeight.Bold,color=colors.text,fontSize=16.sp)
                    Text("어느 키워드든 하나가 일치하면 선택한 탭으로 옮깁니다.",color=colors.subText,fontSize=12.sp)
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text(if(busy) "갤러리 탭을 불러오는 중…" else "이동할 탭",color=colors.subText,modifier=Modifier.weight(1f))
                        TextButton(enabled=gallery!=null && !busy,onClick={refreshTabs++}) {Text("탭 새로고침",color=colors.iconTint)}
                    }
                    if(categories.isNotEmpty())AutomationMenu("",destination?.label ?: "탭 선택",categories.map {it to it.label},colors,"move-target-menu") {destination=it}
                    if(destination?.obstruct==true)Text("이 탭으로 이동하면 개념글 지정이 해제될 수 있습니다.",color=colors.warningRed,fontSize=12.sp)
                    ReadOnlyTextCard("일반 키워드",normalKeywordsText,colors,modifier=Modifier.testTag("move-normal-keywords")) {keywordEditor="normal"}
                    Text("입력한 문자열 그대로 감지 · 예: 사과 → 사과",color=colors.subText,fontSize=12.sp)
                    ReadOnlyTextCard("우회 키워드",bypassKeywordsText,colors,modifier=Modifier.testTag("move-bypass-keywords")) {keywordEditor="bypass"}
                    Text("글자 사이 공백·특수문자도 감지 · 예: 사과 → 사. 과",color=colors.subText,fontSize=12.sp)
                    Spacer(Modifier.height(8.dp))
                    Button(enabled=!busy && gallery!=null,onClick={attempt {
                        val g=gallery ?: error("관리 갤러리를 선택해 주세요.")
                        require(g in configuredAutomationGalleries(p)) {"관리할 갤러리 주소를 다시 확인해 주세요."}
                        val d=destination ?: error("이동할 탭을 선택해 주세요.")
                        require(d in categories)
                        val rule=TabMoveRule(moveEditId ?: UUID.randomUUID().toString(),g.first,g.second,
                            parseAutomationKeywords(normalKeywordsText),d.id,d.label,d.obstruct,parseAutomationKeywords(bypassKeywordsText))
                        val next=if(moveEditId==null)moves+rule else moves.map {if(it.id==rule.id)rule else it}
                        check(p.edit().putString(MOVE_RULES_KEY,encodeTabMoveRules(next)).commit())
                        moves=next;moveEditId=null;normalKeywordsText="";bypassKeywordsText="";destination=null
                    }},modifier=Modifier.fillMaxWidth().testTag("move-save"),colors=ButtonDefaults.buttonColors(containerColor=PastelNavy)) {
                        Text(if(moveEditId==null)"규칙 추가" else "수정 저장")
                    }
                    if(moveEditId!=null)TextButton(onClick={moveEditId=null;normalKeywordsText="";bypassKeywordsText="";destination=null},modifier=Modifier.fillMaxWidth().testTag("move-cancel")) {Text("수정 취소",color=colors.text)}
                    error?.let {Text(it,color=colors.warningRed,fontSize=12.sp)}
                }
                val galleryRules=moves.filter {it.gallType to it.gallId==gallery}
                Text("저장된 규칙 · ${galleryRules.size}개",fontWeight=FontWeight.Bold,color=colors.text,modifier=Modifier.padding(top=12.dp))
                Text("위에서부터 확인하여 처음 일치한 규칙을 적용합니다.",color=colors.subText,fontSize=12.sp)
                if(galleryRules.isEmpty())Text("아직 등록된 규칙이 없습니다.",color=colors.subText,modifier=Modifier.padding(vertical=16.dp))
                galleryRules.forEachIndexed {index,rule->
                    AutomationCard(colors,Modifier.testTag("move-rule-${rule.id}")) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text("${index+1}. ${rule.label}",color=colors.text,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                            TextButton(onClick={
                                moveEditId=rule.id;normalKeywordsText=rule.normalKeywords.joinToString("\n");bypassKeywordsText=rule.bypassKeywords.joinToString("\n")
                                destination=categories.firstOrNull {it.id==rule.headtext};scope.launch {scroll.animateScrollTo(0)}
                            },modifier=Modifier.testTag("move-edit-${rule.id}")) {Text("수정",color=colors.iconTint)}
                            TextButton(onClick={attempt {
                                val next=moves.filterNot {it.id==rule.id};check(p.edit().putString(MOVE_RULES_KEY,encodeTabMoveRules(next)).commit());moves=next
                                if(moveEditId==rule.id){moveEditId=null;normalKeywordsText="";bypassKeywordsText="";destination=null}
                            }}) {Text("삭제",color=colors.subText)}
                        }
                        if(rule.normalKeywords.isNotEmpty())Text("일반 ${rule.normalKeywords.size}개 · ${rule.normalKeywords.joinToString(", ")}",color=colors.subText,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,fontSize=13.sp)
                        if(rule.bypassKeywords.isNotEmpty())Text("우회 ${rule.bypassKeywords.size}개 · ${rule.bypassKeywords.joinToString(", ")}",color=colors.subText,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,fontSize=13.sp)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    keywordEditor?.let { kind ->
        AutomationKeywordDialog(if(kind=="normal") "일반 키워드" else "우회 키워드",
            if(kind=="normal")normalKeywordsText else bypassKeywordsText,colors,
            onDismiss={keywordEditor=null},onSave={value->
                if(kind=="normal")normalKeywordsText=value else bypassKeywordsText=value
                keywordEditor=null
            })
    }
    if(showHelp)AutomationSettingsHelp(page,colors) {showHelp=false}
}
private fun galleryLabel(g:Pair<String,String>)="${g.second} · ${if(g.first=="MI")"미니갤" else "마이너갤"}"
@Composable
private fun <T> AutomationMenu(label:String,value:String,options:List<Pair<T,String>>,colors:BotColorScheme,tag:String,onSelect:(T)->Unit) {
    var open by remember {mutableStateOf(false)}
    if(label.isNotEmpty())Text(label,color=colors.subText)
    Box {
        OutlinedButton(onClick={open=true},modifier=Modifier.fillMaxWidth().testTag(tag)) {Text(value,color=colors.text,modifier=Modifier.weight(1f));Icon(Icons.Default.ArrowDropDown,null,tint=colors.subText)}
        DropdownMenu(expanded=open,onDismissRequest={open=false},containerColor=colors.dialogBg) {
            options.forEach {(option,title)->DropdownMenuItem(text={Text(title,color=colors.text)},onClick={open=false;onSelect(option)})}
        }
    }
}
@Composable
private fun AutomationField(value:String,onChange:(String)->Unit,label:String,colors:BotColorScheme,tag:String) {
    OutlinedTextField(value,onChange,label={Text(label)},modifier=Modifier.fillMaxWidth().padding(vertical=4.dp).testTag(tag),singleLine=true,
        colors=OutlinedTextFieldDefaults.colors(focusedTextColor=colors.text,unfocusedTextColor=colors.text,focusedLabelColor=colors.text,unfocusedLabelColor=colors.subText,focusedBorderColor=colors.accent,unfocusedBorderColor=colors.divider))
}

@Composable
private fun AutomationCard(colors:BotColorScheme,modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    Card(modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),colors=CardDefaults.cardColors(containerColor=colors.card),elevation=CardDefaults.cardElevation(defaultElevation=0.dp)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp),content=content)
    }
}

@Composable
private fun AutomationKeywordDialog(title:String,initial:String,colors:BotColorScheme,onDismiss:()->Unit,onSave:(String)->Unit) {
    var value by remember {mutableStateOf(TextFieldValue(initial))}
    AlertDialog(onDismissRequest=onDismiss,
        properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false),
        modifier=Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.88f).imePadding().testTag("move-keyword-dialog"),
        containerColor=colors.dialogBg,titleContentColor=colors.text,textContentColor=colors.text,
        title={Text("$title 설정",fontWeight=FontWeight.Bold)},
        text={OutlinedTextField(value,{value=it},
            placeholder={Text("줄바꿈으로 키워드를 하나씩 입력하세요.\n\n사과\n바나나\n\n특수문자도 키워드의 일부로 저장됩니다.")},
            modifier=Modifier.fillMaxWidth().fillMaxHeight(0.72f).testTag("move-keyword-input"),
            colors=OutlinedTextFieldDefaults.colors(focusedTextColor=colors.text,unfocusedTextColor=colors.text))},
        confirmButton={TextButton(onClick={onSave(parseAutomationKeywords(value.text).joinToString("\n"))},modifier=Modifier.testTag("move-keyword-save")) {Text("확인",color=colors.iconTint)}},
        dismissButton={TextButton(onClick=onDismiss,modifier=Modifier.testTag("move-keyword-cancel")) {Text("취소",color=colors.subText)}})
}
