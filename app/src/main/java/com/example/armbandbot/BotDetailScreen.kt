package com.heyheyon.armbandbot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Process
import android.webkit.CookieManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.heyheyon.armbandbot.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun BotDetailScreen(botId: String, openBlockLogTrigger: Boolean, onTriggerConsumed: () -> Unit, onBack: () -> Unit) {

    val context = LocalContext.current
    val masterPref = context.getSharedPreferences("bot_master", Context.MODE_PRIVATE)
    val botPref = context.getSharedPreferences("bot_prefs_$botId", Context.MODE_PRIVATE)

    // 🌟 다크모드 색상 팔레트
    val isDarkMode = LocalIsDarkMode.current
    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF8F9FA)
    val topBarColor = if (isDarkMode) Color(0xFF1E2329) else Color.White
    val cardColor = if (isDarkMode) Color(0xFF1E2329) else Color.White
    val dialogBgColor = if (isDarkMode) Color(0xFF2C323A) else Color.White
    val textColor = if (isDarkMode) Color(0xFFE0E0E0) else Color(0xFF2C3E50)
    val subTextColor = if (isDarkMode) Color(0xFFAAAEB3) else Color.Gray
    val dividerColor = if (isDarkMode) Color(0xFF333333) else Color(0xFFEEEEEE)
    val warningRed = if (isDarkMode) Color(0xFFEF5350) else Color(0xFFD32F2F)
    val colors = botColors(isDarkMode)

    var isNotiKkang by remember { mutableStateOf(botPref.getBoolean("noti_kkang", true)) }
    var isKkangFilterMode by remember { mutableStateOf(botPref.getBoolean("is_kkang_filter_mode", false)) }
    var kkangPostMinText by remember { mutableStateOf(botPref.getInt("kkang_post_min", 5).toString()) }
    var kkangCmtMinText by remember { mutableStateOf(botPref.getInt("kkang_comment_min", 10).toString()) }
    var isKkangPostBlock by remember { mutableStateOf(botPref.getBoolean("is_kkang_post_block", false)) }
    var isKkangCommentBlock by remember { mutableStateOf(botPref.getBoolean("is_kkang_comment_block", false)) }
    var isKkangImageBlock by remember { mutableStateOf(botPref.getBoolean("is_kkang_image_block", false)) }
    var isKkangVoiceBlock by remember { mutableStateOf(botPref.getBoolean("is_kkang_voice_block", false)) }

    var isExpertMode by remember { mutableStateOf(botPref.getBoolean("is_expert_mode", false)) }
    var snapshotKeepDaysText by remember { mutableStateOf(botPref.getInt("snapshot_keep_days", 7).toString()) }
    var isSnapshotBlocked by remember { mutableStateOf(botPref.getBoolean("is_snapshot_blocked", true)) }
    var isSnapshotAll by remember { mutableStateOf(botPref.getBoolean("is_snapshot_all", false)) }
    var htmlSnapshotPathToView by remember { mutableStateOf<String?>(null) }

    var devModeClickCount by remember { mutableStateOf(0) }
    var lastDevModeClickTime by remember { mutableStateOf(0L) }
    var isDevModeUnlocked by remember { mutableStateOf(masterPref.getBoolean("dev_mode_unlocked", false)) }
    var botName by remember { mutableStateOf(botPref.getString("bot_name", "이름 없는 봇") ?: "이름 없는 봇") }
    var myCookie by remember { mutableStateOf(botPref.getString("saved_cookie", null)) }

    var editDialogType by remember { mutableStateOf<String?>(null) }
    var tempEditText by remember { mutableStateOf("") }
    var showConfirmDialog by remember { mutableStateOf<String?>(null) }

    var extractUrlText by remember { mutableStateOf("") }
    var isExtracting by remember { mutableStateOf(false) }
    var extractedAltsList by remember { mutableStateOf<List<String>?>(null) }
    var extractedAltsError by remember { mutableStateOf<String?>(null) }
    var extractVoiceText by remember { mutableStateOf("") }
    var extractedVoiceResult by remember { mutableStateOf<String?>(null) }

    if (myCookie == null) {
        Column(modifier = Modifier.fillMaxSize().background(bgColor)) {
            Row(modifier = Modifier.fillMaxWidth().background(topBarColor).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "뒤로가기", modifier = Modifier.clickable { onBack() }.padding(end=16.dp), tint = PastelNavy)
                Text("디시인사이드 로그인", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = textColor)
            }
            LoginScreen(onLoginSuccess = { extractedCookie -> botPref.edit().putString("saved_cookie", extractedCookie).apply(); myCookie = extractedCookie })
        }
    } else {
        val coroutineScope = rememberCoroutineScope()
        var selectedTabIndex by remember { mutableStateOf(0) }
        var logFilterTab by remember { mutableStateOf("ALL") }

        LaunchedEffect(openBlockLogTrigger) {
            if (openBlockLogTrigger) { selectedTabIndex = 1; logFilterTab = "BLOCK"; onTriggerConsumed() }
        }

        val tabs = listOf("기본 설정", "활동 로그")
        val logMessages = GlobalBotState.logs.getOrPut(botId) { mutableStateListOf() }
        var isRunning by remember { mutableStateOf(botPref.getBoolean("is_running", false)) }
        var showEditNameDialog by remember { mutableStateOf(false) }
        var newBotNameInput by remember { mutableStateOf(botName) }
        val settingsScrollState = rememberScrollState()

        LaunchedEffect(botId) {
            val fileLogs = withContext(Dispatchers.IO) {
                loadBotLogsFromFile(context, botId)
            }

            if (fileLogs.isNotEmpty()) {
                logMessages.clear()
                logMessages.addAll(fileLogs.takeLast(500))
            }
        }

        var currentSubScreen by remember { mutableStateOf<String?>(null) }
        BackHandler(enabled = currentSubScreen != null) { currentSubScreen = null }
        BackHandler(enabled = currentSubScreen == null) { onBack() }

        var blockDurationHours by remember { mutableStateOf(botPref.getInt("block_duration_hours", 6)) }
        var isBlockDurationDropdownExpanded by remember { mutableStateOf(false) }
        val blockDurationOptions = mapOf(1 to "1시간", 6 to "6시간", 24 to "24시간 (1일)", 168 to "168시간 (7일)", 336 to "336시간 (14일)", 744 to "744시간 (31일)")
        var blockReasonText by remember { mutableStateOf(botPref.getString("block_reason_text", "커뮤니티 규칙 위반") ?: "커뮤니티 규칙 위반") }
        var isDeletePostOnBlock by remember { mutableStateOf(botPref.getBoolean("delete_post_on_block", true)) }

        var isNotiMaster by remember { mutableStateOf(botPref.getBoolean("noti_master", true)) }
        var isNotiKeyword by remember { mutableStateOf(botPref.getBoolean("noti_keyword", true)) }
        var isNotiUser by remember { mutableStateOf(botPref.getBoolean("noti_user", true)) }
        var isNotiNickname by remember { mutableStateOf(botPref.getBoolean("noti_nickname", true)) }
        var isNotiYudong by remember { mutableStateOf(botPref.getBoolean("noti_yudong", true)) }
        var isNotiUrl by remember { mutableStateOf(botPref.getBoolean("noti_url", true)) }
        var isNotiImage by remember { mutableStateOf(botPref.getBoolean("noti_image", true)) }
        var isNotiVoice by remember { mutableStateOf(botPref.getBoolean("noti_voice", true)) }
        var isNotiSpam by remember { mutableStateOf(botPref.getBoolean("noti_spam", true)) }

        var targetUrlsText by remember { mutableStateOf(botPref.getString("target_urls", "") ?: "") }
        var isSearchMode by remember { mutableStateOf(botPref.getBoolean("is_search_mode", false)) }
        var searchType by remember { mutableStateOf(botPref.getString("search_type", "search_subject_memo") ?: "search_subject_memo") }
        var isSearchTypeDropdownExpanded by remember { mutableStateOf(false) }
        val searchTypeMap = mapOf("search_subject_memo" to "제목+내용", "search_subject" to "제목", "search_memo" to "내용", "search_name" to "글쓴이", "search_comment" to "댓글")
        var searchWordsText by remember { mutableStateOf(botPref.getStringSet("search_keywords", setOf())?.joinToString("\n") ?: "") }

        var isUserFilterMode by remember { mutableStateOf(botPref.getBoolean("is_user_filter_mode", false)) }
        var userBlacklistText by remember { mutableStateOf(botPref.getStringSet("user_blacklist", setOf())?.joinToString("\n") ?: "") }
        var userWhitelistText by remember { mutableStateOf(botPref.getStringSet("user_whitelist", setOf())?.joinToString("\n") ?: "") }

        var isNicknameFilterMode by remember { mutableStateOf(botPref.getBoolean("is_nickname_filter_mode", false)) }
        var nicknameBlacklistText by remember { mutableStateOf(botPref.getStringSet("nickname_blacklist", setOf())?.joinToString("\n") ?: "") }
        var nicknameWhitelistText by remember { mutableStateOf(botPref.getStringSet("nickname_whitelist", setOf())?.joinToString("\n") ?: "") }

        var isYudongPostBlock by remember { mutableStateOf(botPref.getBoolean("is_yudong_post_block", false)) }
        var isYudongCommentBlock by remember { mutableStateOf(botPref.getBoolean("is_yudong_comment_block", false)) }
        var isYudongImageBlock by remember { mutableStateOf(botPref.getBoolean("is_yudong_image_block", false)) }
        var isYudongVoiceBlock by remember { mutableStateOf(botPref.getBoolean("is_yudong_voice_block", false)) }

        var isUrlFilterMode by remember { mutableStateOf(botPref.getBoolean("is_url_filter_mode", false)) }
        var urlWhitelistText by remember { mutableStateOf(botPref.getStringSet("url_whitelist", setOf())?.joinToString("\n") ?: "") }

        var isImageFilterMode by remember { mutableStateOf(botPref.getBoolean("is_image_filter_mode", false)) }
        var imageFilterThresholdText by remember { mutableStateOf(botPref.getInt("image_filter_threshold", 80).toString()) }
        var imageAltBlacklistText by remember { mutableStateOf(botPref.getStringSet("image_alt_blacklist", setOf())?.joinToString("\n") ?: "") }

        var isVoiceFilterMode by remember { mutableStateOf(botPref.getBoolean("is_voice_filter_mode", false)) }
        var voiceBlacklistText by remember { mutableStateOf(botPref.getStringSet("voice_blacklist", setOf())?.joinToString("\n") ?: "") }

        var isSpamCodeFilterMode by remember { mutableStateOf(botPref.getBoolean("is_spam_code_filter_mode", false)) }
        var spamCodeLengthText by remember { mutableStateOf(botPref.getInt("spam_code_length", 6).toString()) }

        var normalWordsText by remember { mutableStateOf(botPref.getStringSet("normal", setOf())?.joinToString("\n") ?: "") }
        var bypassWordsText by remember { mutableStateOf(botPref.getStringSet("bypass", setOf())?.joinToString("\n") ?: "") }

        var scanPageText by remember { mutableStateOf(botPref.getInt("scan_page_count", 1).toString()) }
        var postMinText by remember { mutableStateOf(botPref.getFloat("delay_post_min_sec", 1.0f).toString()) }
        var postMaxText by remember { mutableStateOf(botPref.getFloat("delay_post_max_sec", 2.5f).toString()) }
        var pageMinText by remember { mutableStateOf(botPref.getFloat("delay_page_min_sec", 2.0f).toString()) }
        var pageMaxText by remember { mutableStateOf(botPref.getFloat("delay_page_max_sec", 4.0f).toString()) }
        var cycleMinText by remember { mutableStateOf(botPref.getFloat("delay_cycle_min_sec", 45.0f).toString()) }
        var cycleMaxText by remember { mutableStateOf(botPref.getFloat("delay_cycle_max_sec", 90.0f).toString()) }

        var rememberedPostCount by remember { mutableStateOf(0) }
        var isDebugMode by remember { mutableStateOf(botPref.getBoolean("is_debug_mode", false)) }
        var lastCheckedNumber by remember { mutableStateOf(GlobalBotState.lastCheckedNumbers[botId] ?: 0) }
        val logListState = rememberLazyListState()

        LaunchedEffect(botId) {
            withContext(Dispatchers.IO) {
                rememberedPostCount = GlobalBotState.getHistoryCount()
            }
        }

        DisposableEffect(context) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.getStringExtra("BOT_ID") == botId) {
                        lastCheckedNumber = GlobalBotState.lastCheckedNumbers[botId] ?: 0
                        coroutineScope.launch(Dispatchers.IO) {
                            rememberedPostCount = GlobalBotState.getHistoryCount()
                        }
                    }
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter("BOT_LOG_EVENT"), ContextCompat.RECEIVER_NOT_EXPORTED)
            onDispose { context.unregisterReceiver(receiver) }
        }

        AnimatedContent(
            targetState = currentSubScreen,
            transitionSpec = {
                if (targetState != null && initialState == null) { slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it / 2 } + fadeOut() }
                else if (targetState == null && initialState != null) { slideInHorizontally { -it / 2 } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut() }
                else { fadeIn() togetherWith fadeOut() }
            }, label = "SubScreenAnimation"
        ) { activeSubScreen ->
            if (activeSubScreen != null) {
                if (activeSubScreen == "DB_DASHBOARD") {
                    DbDashboardScreen(botId = botId, onBack = { currentSubScreen = null })
                } else {
                    Column(modifier = Modifier.fillMaxSize().background(bgColor)) {
                        Row(modifier = Modifier.fillMaxWidth().background(topBarColor).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "뒤로", modifier = Modifier.clickable { currentSubScreen = null }.padding(end=16.dp), tint = PastelNavy)
                            Text(
                                when(activeSubScreen) {
                                    "TARGET" -> "관리할 갤러리 및 검색 모드"
                                    "USER" -> "유저 ID/IP 필터"
                                    "NICKNAME" -> "닉네임 필터"
                                    "YUDONG" -> "유동 필터"
                                    "URL" -> "URL 필터"
                                    "IMAGE" -> "이미지 필터"
                                    "VOICE" -> "보이스 필터"
                                    "SPAM" -> "스팸코드 필터"
                                    "WORD" -> "금지어 필터"
                                    "SPEED" -> "탐색 범위 및 속도 설정"
                                    "BLOCK_SETTING" -> "차단 기본 설정"
                                    "NOTI_SETTING" -> "차단 알림 상세 설정"
                                    else -> "상세 설정"
                                }, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = textColor, modifier = Modifier.weight(1f)
                            )
                        }

                        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            when (activeSubScreen) {
                                "NOTI_SETTING" -> {
                                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                            Column {
                                                Text("모든 차단 알림 켜기", fontWeight = FontWeight.Bold, color = textColor)
                                                Text("이 스위치를 끄면 아래 설정과 무관하게 알림이 오지 않습니다.", fontSize=12.sp, color=subTextColor)
                                            }
                                            Switch(checked = isNotiMaster, onCheckedChange = { isNotiMaster = it; botPref.edit().putBoolean("noti_master", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                        }
                                    }
                                    if (isNotiMaster) {
                                        Text("알림을 받을 차단 유형", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = PastelNavy, modifier = Modifier.padding(start=4.dp, bottom=8.dp))
                                        Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp)) {
                                            Column(modifier = Modifier.padding(16.dp)) {
                                                Row(modifier=Modifier.fillMaxWidth().padding(vertical=4.dp), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) { Text("금지어 필터 차단", color=textColor) ; Switch(checked=isNotiKeyword, onCheckedChange={ isNotiKeyword=it; botPref.edit().putBoolean("noti_keyword", it).apply() }, modifier=Modifier.scale(0.8f)) }
                                                Divider(color = dividerColor)
                                                Row(modifier=Modifier.fillMaxWidth().padding(vertical=4.dp), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) { Text("ID/IP 필터 차단", color=textColor) ; Switch(checked=isNotiUser, onCheckedChange={ isNotiUser=it; botPref.edit().putBoolean("noti_user", it).apply() }, modifier=Modifier.scale(0.8f)) }
                                                Divider(color = dividerColor)
                                                Row(modifier=Modifier.fillMaxWidth().padding(vertical=4.dp), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) { Text("닉네임 필터 차단", color=textColor) ; Switch(checked=isNotiNickname, onCheckedChange={ isNotiNickname=it; botPref.edit().putBoolean("noti_nickname", it).apply() }, modifier=Modifier.scale(0.8f)) }
                                                Divider(color = dividerColor)
                                                Row(modifier=Modifier.fillMaxWidth().padding(vertical=4.dp), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) { Text("유동 필터 차단", color=textColor) ; Switch(checked=isNotiYudong, onCheckedChange={ isNotiYudong=it; botPref.edit().putBoolean("noti_yudong", it).apply() }, modifier=Modifier.scale(0.8f)) }
                                                Divider(color = dividerColor)
                                                Row(modifier=Modifier.fillMaxWidth().padding(vertical=4.dp), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) { Text("URL 필터 차단", color=textColor) ; Switch(checked=isNotiUrl, onCheckedChange={ isNotiUrl=it; botPref.edit().putBoolean("noti_url", it).apply() }, modifier=Modifier.scale(0.8f)) }
                                                Divider(color = dividerColor)
                                                Row(modifier=Modifier.fillMaxWidth().padding(vertical=4.dp), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) { Text("이미지 필터 차단", color=textColor) ; Switch(checked=isNotiImage, onCheckedChange={ isNotiImage=it; botPref.edit().putBoolean("noti_image", it).apply() }, modifier=Modifier.scale(0.8f)) }
                                                Divider(color = dividerColor)
                                                Row(modifier=Modifier.fillMaxWidth().padding(vertical=4.dp), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) { Text("보이스 필터 차단", color=textColor) ; Switch(checked=isNotiVoice, onCheckedChange={ isNotiVoice=it; botPref.edit().putBoolean("noti_voice", it).apply() }, modifier=Modifier.scale(0.8f)) }
                                                Divider(color = dividerColor)
                                                Row(modifier=Modifier.fillMaxWidth().padding(vertical=4.dp), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) { Text("스팸코드 필터 차단", color=textColor) ; Switch(checked=isNotiSpam, onCheckedChange={ isNotiSpam=it; botPref.edit().putBoolean("noti_spam", it).apply() }, modifier=Modifier.scale(0.8f)) }
                                            }
                                        }
                                    }
                                }
                                "BLOCK_SETTING" -> {
                                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("차단 시간", fontWeight = FontWeight.Bold, color = textColor)
                                                Box {
                                                    OutlinedButton(onClick = { isBlockDurationDropdownExpanded = true }) {
                                                        Text(blockDurationOptions[blockDurationHours] ?: "${blockDurationHours}시간", color = textColor)
                                                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy)
                                                    }
                                                    DropdownMenu(expanded = isBlockDurationDropdownExpanded, onDismissRequest = { isBlockDurationDropdownExpanded = false }, modifier = Modifier.background(dialogBgColor)) {
                                                        blockDurationOptions.forEach { (hours, label) -> DropdownMenuItem(text = { Text(label, color = textColor) }, onClick = { blockDurationHours = hours; botPref.edit().putInt("block_duration_hours", hours).apply(); isBlockDurationDropdownExpanded = false }) }
                                                    }
                                                }
                                            }
                                            Divider(color = dividerColor, modifier = Modifier.padding(bottom=8.dp))
                                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("차단 시 글/댓글 함께 삭제", color = textColor)
                                                Switch(checked = isDeletePostOnBlock, onCheckedChange = { isDeletePostOnBlock = it; botPref.edit().putBoolean("delete_post_on_block", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                            }
                                        }
                                    }
                                    ReadOnlyTextCard("차단 사유 (유저에게 표시됨)", blockReasonText, colors) { tempEditText = blockReasonText; editDialogType = "block_reason" }
                                }
                                "TARGET" -> {
                                    ReadOnlyTextCard("관리할 갤러리 URL", targetUrlsText, colors) { tempEditText = targetUrlsText; editDialogType = "url" }
                                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("검색어 모드 사용", fontWeight = FontWeight.Bold, color = textColor)
                                            Switch(checked = isSearchMode, onCheckedChange = { isSearchMode = it; botPref.edit().putBoolean("is_search_mode", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                        }
                                    }
                                    if (isSearchMode) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                                            Text("검색 유형: ", fontWeight = FontWeight.Bold, color = PastelNavy)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Box {
                                                OutlinedButton(onClick = { isSearchTypeDropdownExpanded = true }) {
                                                    Text(searchTypeMap[searchType] ?: "유형 선택", color = textColor)
                                                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy)
                                                }
                                                DropdownMenu(expanded = isSearchTypeDropdownExpanded, onDismissRequest = { isSearchTypeDropdownExpanded = false }, modifier = Modifier.background(dialogBgColor)) {
                                                    searchTypeMap.forEach { (key, label) -> DropdownMenuItem(text = { Text(label, color = textColor) }, onClick = { searchType = key; botPref.edit().putString("search_type", key).apply(); isSearchTypeDropdownExpanded = false }) }
                                                }
                                            }
                                        }
                                        ReadOnlyTextCard("검색어 목록", searchWordsText, colors) { tempEditText = searchWordsText; editDialogType = "search" }
                                    }
                                }
                                "USER" -> {
                                    ReadOnlyTextCard("ID/IP 블랙리스트 (발견 즉시 차단)", userBlacklistText, colors) { tempEditText = userBlacklistText; editDialogType = "user_blacklist" }
                                    ReadOnlyTextCard("ID/IP 화이트리스트 (차단 예외)", userWhitelistText, colors) { tempEditText = userWhitelistText; editDialogType = "user_whitelist" }
                                }
                                "NICKNAME" -> {
                                    ReadOnlyTextCard("닉네임 블랙리스트 (발견 즉시 차단)", nicknameBlacklistText, colors) { tempEditText = nicknameBlacklistText; editDialogType = "nickname_blacklist" }
                                    ReadOnlyTextCard("닉네임 화이트리스트 (차단 예외)", nicknameWhitelistText, colors) { tempEditText = nicknameWhitelistText; editDialogType = "nickname_whitelist" }
                                }
                                "YUDONG" -> {
                                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp)) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("유동 게시글 쓰기 금지", color = textColor)
                                                Switch(checked = isYudongPostBlock, onCheckedChange = { isYudongPostBlock = it; botPref.edit().putBoolean("is_yudong_post_block", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                            }
                                            Divider(color = dividerColor)
                                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("유동 댓글 쓰기 금지", color = textColor)
                                                Switch(checked = isYudongCommentBlock, onCheckedChange = { isYudongCommentBlock = it; botPref.edit().putBoolean("is_yudong_comment_block", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                            }
                                            Divider(color = dividerColor)
                                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("유동 이미지 첨부 금지 (게시글)", color = textColor)
                                                Switch(checked = isYudongImageBlock, onCheckedChange = { isYudongImageBlock = it; botPref.edit().putBoolean("is_yudong_image_block", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                            }
                                            Divider(color = dividerColor)
                                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("유동 보이스 첨부 금지 (글/댓글)", color = textColor)
                                                Switch(checked = isYudongVoiceBlock, onCheckedChange = { isYudongVoiceBlock = it; botPref.edit().putBoolean("is_yudong_voice_block", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                            }
                                        }
                                    }
                                }
                                "KKANG" -> {
                                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp)) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("작성 게시글 기준 수", fontWeight = FontWeight.Bold, color = textColor)
                                                OutlinedTextField(value = kkangPostMinText, onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) { kkangPostMinText = it; botPref.edit().putInt("kkang_post_min", it.toIntOrNull() ?: 5).apply() } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.width(80.dp), textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor))
                                            }
                                            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("작성 댓글 기준 수", fontWeight = FontWeight.Bold, color = textColor)
                                                OutlinedTextField(value = kkangCmtMinText, onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) { kkangCmtMinText = it; botPref.edit().putInt("kkang_comment_min", it.toIntOrNull() ?: 10).apply() } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.width(80.dp), textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor))
                                            }
                                            Text("※ 위 두 숫자 중 하나라도 미달하면 깡계로 간주합니다.", fontSize = 12.sp, color = subTextColor)

                                            Divider(color = dividerColor, modifier = Modifier.padding(vertical=8.dp))
                                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("깡계 게시글 금지", color = textColor)
                                                Switch(checked = isKkangPostBlock, onCheckedChange = { isKkangPostBlock = it; botPref.edit().putBoolean("is_kkang_post_block", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                            }
                                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("깡계 댓글 금지", color = textColor)
                                                Switch(checked = isKkangCommentBlock, onCheckedChange = { isKkangCommentBlock = it; botPref.edit().putBoolean("is_kkang_comment_block", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                            }
                                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("깡계 이미지 첨부 금지", color = textColor)
                                                Switch(checked = isKkangImageBlock, onCheckedChange = { isKkangImageBlock = it; botPref.edit().putBoolean("is_kkang_image_block", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                            }
                                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("깡계 보이스 첨부 금지", color = textColor)
                                                Switch(checked = isKkangVoiceBlock, onCheckedChange = { isKkangVoiceBlock = it; botPref.edit().putBoolean("is_kkang_voice_block", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                            }
                                        }
                                    }
                                }
                                "URL" -> {
                                    Text("여기에 없는 외부 링크는 모두 차단됩니다.", fontSize = 12.sp, color = subTextColor, modifier = Modifier.padding(bottom = 8.dp))
                                    ReadOnlyTextCard("허용할 도메인 (화이트리스트)", urlWhitelistText, colors) { tempEditText = urlWhitelistText; editDialogType = "url_whitelist" }
                                }
                                "IMAGE" -> {
                                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("일치도 기준 (%)", fontWeight = FontWeight.Bold, color = textColor)
                                            OutlinedTextField(value = imageFilterThresholdText, onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) { imageFilterThresholdText = it; botPref.edit().putInt("image_filter_threshold", it.toIntOrNull() ?: 80).apply() } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.width(80.dp), textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor))
                                        }
                                    }
                                    ReadOnlyTextCard("차단할 이미지 alt값 (블랙리스트)", imageAltBlacklistText, colors) { tempEditText = imageAltBlacklistText; editDialogType = "image_alt_blacklist" }
                                    Spacer(modifier = Modifier.height(24.dp))

                                    Text("도구", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = PastelNavy, modifier = Modifier.padding(start=4.dp, bottom=8.dp))
                                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp)) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Filled.Search, contentDescription = null, tint = PastelNavy, modifier = Modifier.size(18.dp))
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("게시글 이미지 alt값 추출기", fontWeight = FontWeight.Bold, color = textColor)
                                            }
                                            Text("스팸 글의 주소를 입력하면 이미지를 스캔합니다.", fontSize = 12.sp, color = subTextColor, modifier = Modifier.padding(top=4.dp, bottom=12.dp))
                                            Row {
                                                OutlinedTextField(value = extractUrlText, onValueChange = { extractUrlText = it }, placeholder = { Text("https://...", fontSize=12.sp) }, singleLine = true, modifier = Modifier.weight(1f).height(50.dp), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor))
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Button(onClick = {
                                                    if (extractUrlText.isNotBlank()) {
                                                        isExtracting = true
                                                        coroutineScope.launch(Dispatchers.IO) {
                                                            try {
                                                                val doc = Jsoup.connect(extractUrlText)
                                                                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                                                                    .get()
                                                                val alts = doc.select(".write_div img").mapNotNull { it.attr("alt") }.filter { it.isNotBlank() }
                                                                withContext(Dispatchers.Main) {
                                                                    if (alts.isEmpty()) extractedAltsError = "이미지가 없거나 alt 값이 없습니다." else extractedAltsList = alts
                                                                    isExtracting = false
                                                                }
                                                            } catch (e: Exception) { withContext(Dispatchers.Main) { extractedAltsError = "오류 발생: 주소를 확인해주세요. (${e.message})"; isExtracting = false } }
                                                        }
                                                    }
                                                }, colors = ButtonDefaults.buttonColors(containerColor = PastelNavy), modifier = Modifier.height(50.dp)) { Text(if(isExtracting) "..." else "추출", color = Color.White) }
                                            }
                                        }
                                    }
                                }
                                "VOICE" -> {
                                    ReadOnlyTextCard("차단할 보이스 ID (블랙리스트)", voiceBlacklistText, colors) { tempEditText = voiceBlacklistText; editDialogType = "voice_blacklist" }
                                    Spacer(modifier = Modifier.height(24.dp))
                                    Text("도구", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = PastelNavy, modifier = Modifier.padding(start=4.dp, bottom=8.dp))
                                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp)) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Filled.Search, contentDescription = null, tint = PastelNavy, modifier = Modifier.size(18.dp))
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("보이스 ID 소스 추출기", fontWeight = FontWeight.Bold, color = textColor)
                                            }
                                            Text("보플 iframe 또는 복사 버튼 소스코드를 넣으세요.", fontSize = 12.sp, color = subTextColor, modifier = Modifier.padding(top=4.dp, bottom=12.dp))
                                            Row {
                                                OutlinedTextField(value = extractVoiceText, onValueChange = { extractVoiceText = it }, placeholder = { Text("<iframe src=...", fontSize=12.sp) }, singleLine = true, modifier = Modifier.weight(1f).height(50.dp), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor))
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Button(onClick = {
                                                    if (extractVoiceText.isNotBlank()) {
                                                        val match = Regex("(?:vr=|copyTextToClipboard\\('[^']+',\\s*')([a-zA-Z0-9]+)").find(extractVoiceText)
                                                        extractedVoiceResult = match?.groupValues?.get(1) ?: "ID를 찾을 수 없습니다."
                                                        extractVoiceText = ""
                                                    }
                                                }, colors = ButtonDefaults.buttonColors(containerColor = PastelNavy), modifier = Modifier.height(50.dp)) { Text("추출", color = Color.White) }
                                            }
                                        }
                                    }
                                }
                                "SPAM" -> {
                                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("감지할 스팸코드 자릿수", fontWeight = FontWeight.Bold, color = textColor)
                                            OutlinedTextField(value = spamCodeLengthText, onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) { spamCodeLengthText = it; botPref.edit().putInt("spam_code_length", it.toIntOrNull() ?: 6).apply() } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.width(80.dp), textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor))
                                        }
                                    }
                                }
                                "WORD" -> {
                                    ReadOnlyTextCard("일반 금지어 (완전히 일치하는 경우 차단)", normalWordsText, colors) { tempEditText = normalWordsText; editDialogType = "normal" }
                                    ReadOnlyTextCard("우회 금지어 (글자 사이 특수문자 등 무시)", bypassWordsText, colors) { tempEditText = bypassWordsText; editDialogType = "bypass" }
                                }
                                "SPEED" -> {
                                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp)) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("탐색 페이지 수", fontWeight = FontWeight.Bold, color = textColor)
                                                OutlinedTextField(value = scanPageText, onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) { scanPageText = it; botPref.edit().putInt("scan_page_count", it.toIntOrNull() ?: 1).apply() } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.width(80.dp), textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor))
                                            }
                                            Divider(color = dividerColor, modifier = Modifier.padding(bottom=8.dp))
                                            DelayInputRow("게시물 사이 간격", postMinText, postMaxText, "초", { postMinText = it; botPref.edit().putFloat("delay_post_min_sec", it.toFloatOrNull() ?: 1.0f).apply() }, { postMaxText = it; botPref.edit().putFloat("delay_post_max_sec", it.toFloatOrNull() ?: 2.5f).apply() }, colors)
                                            DelayInputRow("페이지 전환 간격", pageMinText, pageMaxText, "초", { pageMinText = it; botPref.edit().putFloat("delay_page_min_sec", it.toFloatOrNull() ?: 2.0f).apply() }, { pageMaxText = it; botPref.edit().putFloat("delay_page_max_sec", it.toFloatOrNull() ?: 4.0f).apply() }, colors)
                                            DelayInputRow("1사이클 종료 후 대기", cycleMinText, cycleMaxText, "초", { cycleMinText = it; botPref.edit().putFloat("delay_cycle_min_sec", it.toFloatOrNull() ?: 45.0f).apply() }, { cycleMaxText = it; botPref.edit().putFloat("delay_cycle_max_sec", it.toFloatOrNull() ?: 90.0f).apply() }, colors)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            else {
                Column(modifier = Modifier.fillMaxSize().background(bgColor)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().background(topBarColor).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { onBack() }, modifier = Modifier.size(40.dp)) {
                            Icon(androidx.compose.material.icons.Icons.Filled.ArrowBack, contentDescription = "뒤로가기", tint = textColor)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Text(text = botName, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(onClick = { showEditNameDialog = true }, modifier = Modifier.size(28.dp)) {
                                Icon(androidx.compose.material.icons.Icons.Filled.Edit, contentDescription = "이름 수정", tint = subTextColor, modifier = Modifier.size(18.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = isRunning,
                            onCheckedChange = {
                                isRunning = it; botPref.edit().putBoolean("is_running", it).apply()
                                val serviceIntent = Intent(context, BotService::class.java).apply { putExtra("BOT_ID", botId); putExtra("COOKIE", botPref.getString("saved_cookie", "")); action = if (isRunning) "START" else "STOP" }
                                if (isRunning && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent) else context.startService(serviceIntent)
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray, uncheckedBorderColor = Color.Transparent),
                            modifier = Modifier.scale(0.85f)
                        )
                    }

                    TabRow(selectedTabIndex = selectedTabIndex, containerColor = topBarColor, contentColor = PastelNavy) {
                        tabs.forEachIndexed { index, title -> Tab(selected = selectedTabIndex == index, onClick = { selectedTabIndex = index }, text = { Text(title, fontWeight = FontWeight.Bold, color = if(selectedTabIndex==index) PastelNavy else subTextColor) }) }
                    }

                    Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        if (selectedTabIndex == 0) {
                            Column(modifier = Modifier.fillMaxSize().verticalScroll(settingsScrollState).padding(vertical = 16.dp)) {
                                Text("기본 탐색 설정", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = PastelNavy, modifier = Modifier.padding(start=4.dp, bottom=4.dp))
                                ModernSettingItem("관리할 갤러리 및 검색 모드", if (targetUrlsText.isBlank()) "대상 없음" else "대상 설정됨", Icons.Filled.List, colors) { currentSubScreen = "TARGET" }
                                ModernSettingItem("탐색 속도 및 범위", "페이지 수 및 딜레이 설정", Icons.Filled.Build, colors) { currentSubScreen = "SPEED" }

                                Spacer(modifier = Modifier.height(24.dp))
                                Text("차단 후속 동작", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = PastelNavy, modifier = Modifier.padding(start=4.dp, bottom=4.dp))
                                ModernSettingItem("차단 기본 설정", "차단 시간, 사유, 글 삭제 여부", Icons.Filled.Settings, colors) { currentSubScreen = "BLOCK_SETTING" }
                                ModernSettingItem("차단 알림 상세 설정", "어떤 경우에 알림을 받을지 설정", Icons.Filled.Notifications, colors) { currentSubScreen = "NOTI_SETTING" }

                                Spacer(modifier = Modifier.height(24.dp))
                                Text("게시물 차단 필터", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = PastelNavy, modifier = Modifier.padding(start=4.dp, bottom=4.dp))
                                ModernSettingItem("금지어 필터", "금지어 기반 차단 설정", Icons.Filled.Create, colors) { currentSubScreen = "WORD" }
                                ModernSettingItem("유저 ID/IP 필터", "식별코드/IP 기반 차단 설정", Icons.Filled.Person, colors, isUserFilterMode, { isUserFilterMode = it; botPref.edit().putBoolean("is_user_filter_mode", it).apply() }) { currentSubScreen = "USER" }
                                ModernSettingItem("닉네임 필터", "닉네임 기반 차단 설정", Icons.Filled.Face, colors, isNicknameFilterMode, { isNicknameFilterMode = it; botPref.edit().putBoolean("is_nickname_filter_mode", it).apply() }) { currentSubScreen = "NICKNAME" }
                                ModernSettingItem("유동 필터", "비로그인 유저 이용 제한", Icons.Filled.Lock, colors) { currentSubScreen = "YUDONG" }
                                ModernSettingItem("깡계 필터", "글/댓글 수 미달 유저 차단", Icons.Filled.Info, colors, isKkangFilterMode, { isKkangFilterMode = it; botPref.edit().putBoolean("is_kkang_filter_mode", it).apply() }) { currentSubScreen = "KKANG" }

                                Spacer(modifier = Modifier.height(24.dp))
                                Text("고급 미디어 필터", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = PastelNavy, modifier = Modifier.padding(start=4.dp, bottom=4.dp))
                                ModernSettingItem("URL 필터", "외부 링크 차단", Icons.Filled.Share, colors, isUrlFilterMode, { isUrlFilterMode = it; botPref.edit().putBoolean("is_url_filter_mode", it).apply() }) { currentSubScreen = "URL" }
                                ModernSettingItem("이미지 필터", "alt값 기반 이미지 차단", Icons.Filled.Search, colors, isImageFilterMode, { isImageFilterMode = it; botPref.edit().putBoolean("is_image_filter_mode", it).apply() }) { currentSubScreen = "IMAGE" }
                                ModernSettingItem("보이스 필터", "보이스 리플 차단", Icons.Filled.Call, colors, isVoiceFilterMode, { isVoiceFilterMode = it; botPref.edit().putBoolean("is_voice_filter_mode", it).apply() }) { currentSubScreen = "VOICE" }
                                ModernSettingItem("스팸코드 필터", "대문자+숫자 조합 문자열 차단", Icons.Filled.Warning, colors, isSpamCodeFilterMode, { isSpamCodeFilterMode = it; botPref.edit().putBoolean("is_spam_code_filter_mode", it).apply() }) { currentSubScreen = "SPAM" }

                                Spacer(modifier = Modifier.height(24.dp))
                                Text("시스템 관리", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = PastelNavy, modifier = Modifier.padding(start=4.dp, bottom=4.dp))
                                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = cardColor)) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth().clickable(
                                            onClick = {
                                                val now = System.currentTimeMillis()
                                                if (now - lastDevModeClickTime > 3000) devModeClickCount = 0
                                                devModeClickCount++
                                                lastDevModeClickTime = now
                                                if (devModeClickCount >= 5 && !isDevModeUnlocked) {
                                                    isDevModeUnlocked = true
                                                    masterPref.edit().putBoolean("dev_mode_unlocked", true).apply()
                                                    Toast.makeText(context, "개발자 설정을 표시합니다.", Toast.LENGTH_SHORT).show()
                                                }
                                                isDebugMode = !isDebugMode
                                                botPref.edit().putBoolean("is_debug_mode", isDebugMode).apply()
                                            }
                                        ).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("디버그 로그 출력", fontWeight = FontWeight.Bold, color = textColor)
                                            Switch(checked = isDebugMode, onCheckedChange = null, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray, uncheckedBorderColor = Color.Transparent), modifier = Modifier.scale(0.8f))
                                        }

                                        if (isDevModeUnlocked) {
                                            Spacer(modifier = Modifier.height(16.dp))
                                            Divider(color = warningRed)
                                            Spacer(modifier = Modifier.height(16.dp))
                                            Text("개발자 설정", fontWeight = FontWeight.Bold, color = warningRed)
                                            Text("고급 로깅 및 DB를 관리합니다.", fontSize = 12.sp, color = subTextColor, modifier = Modifier.padding(bottom = 12.dp))

                                            Box(modifier = Modifier.fillMaxWidth().background(if(isDarkMode) Color(0xFF3E2723) else Color(0xFFFFF0F0), RoundedCornerShape(8.dp)).padding(12.dp)) {
                                                Column {
                                                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text("스냅샷 수집", fontWeight = FontWeight.Bold, color = warningRed, fontSize = 14.sp)
                                                            Text("글의 HTML을 캐시 파일로 수집합니다.", fontSize = 11.sp, color = subTextColor)
                                                        }
                                                        Switch(checked = isExpertMode, onCheckedChange = { isExpertMode = it; botPref.edit().putBoolean("is_expert_mode", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = warningRed, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray), modifier = Modifier.scale(0.8f))
                                                    }

                                                    if (isExpertMode) {
                                                        Spacer(modifier = Modifier.height(8.dp))
                                                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                            Text("스냅샷 보관 기간 (일)", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = textColor)
                                                            OutlinedTextField(value = snapshotKeepDaysText, onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) { snapshotKeepDaysText = it; botPref.edit().putInt("snapshot_keep_days", it.toIntOrNull() ?: 7).apply() } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.width(70.dp).height(50.dp), textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center, fontSize = 14.sp), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor))
                                                        }

                                                        Divider(color = if(isDarkMode) Color(0xFF5D4037) else Color(0xFFFFCDD2), modifier = Modifier.padding(vertical = 8.dp))

                                                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                            Column(modifier = Modifier.weight(1f)) {
                                                                Text("차단 시 스냅샷 저장", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = textColor)
                                                                Text("게시물/댓글 차단 시 스냅샷을 남깁니다.", fontSize = 11.sp, color = subTextColor)
                                                            }
                                                            Switch(checked = isSnapshotBlocked, onCheckedChange = { isSnapshotBlocked = it; botPref.edit().putBoolean("is_snapshot_blocked", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = warningRed, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray), modifier = Modifier.scale(0.8f))
                                                        }

                                                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                            Column(modifier = Modifier.weight(1f)) {
                                                                Text("모든 글 스냅샷 저장", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = textColor)
                                                                Text("검사하는 모든 글의 스냅샷을 남깁니다. (캐시 용량 증가)", fontSize = 11.sp, color = subTextColor)
                                                            }
                                                            Switch(checked = isSnapshotAll, onCheckedChange = { isSnapshotAll = it; botPref.edit().putBoolean("is_snapshot_all", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = warningRed, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray), modifier = Modifier.scale(0.8f))
                                                        }
                                                    }
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(16.dp))
                                            Button(
                                                onClick = { currentSubScreen = "DB_DASHBOARD" },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if(isDarkMode) Color(0xFF424242) else Color.DarkGray,
                                                    contentColor = Color.White
                                                ),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("DB 대시보드")
                                            }
                                            Spacer(modifier = Modifier.height(8.dp))

                                            Button(
                                                onClick = { showConfirmDialog = "crash_test" },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if (isDarkMode) Color(0xFF5D4037) else Color(0xFFFFCDD2),
                                                    contentColor = if (isDarkMode) Color.White else warningRed
                                                ),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("강제 종료 테스트")
                                            }
                                            Spacer(modifier = Modifier.height(8.dp))

                                            Button(
                                                onClick = {
                                                    isDevModeUnlocked = false
                                                    devModeClickCount = 0
                                                    masterPref.edit().putBoolean("dev_mode_unlocked", false).apply()
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if(isDarkMode) Color(0xFF333333) else Color(0xFFEEEEEE),
                                                    contentColor = textColor
                                                ),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("▲ 개발자 설정 숨기기")
                                            }
                                            Spacer(modifier = Modifier.height(16.dp))
                                            Divider(color = dividerColor)
                                        }

                                        Spacer(modifier = Modifier.height(16.dp))
                                        Button(
                                            onClick = { showConfirmDialog = "db_reset" },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if(isDarkMode) Color(0xFF37474F) else PastelNavyLight,
                                                contentColor = if(isDarkMode) Color.White else PastelNavy
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("공용 DB 초기화 (공유 기록: $rememberedPostCount)")
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Button(onClick = { showConfirmDialog = "logout" }, colors = ButtonDefaults.buttonColors(containerColor = if(isDarkMode) Color(0xFF333333) else Color(0xFFEEEEEE), contentColor = textColor), modifier = Modifier.fillMaxWidth()) { Text("계정 로그아웃") }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Button(onClick = { showConfirmDialog = "delete" }, colors = ButtonDefaults.buttonColors(containerColor = if(isDarkMode) Color(0xFF3E2723) else Color(0xFFFFEBEE), contentColor = warningRed), modifier = Modifier.fillMaxWidth()) { Text("봇 삭제") }
                                    }
                                }
                                Spacer(modifier = Modifier.height(40.dp))
                            }
                        }

                        else if (selectedTabIndex == 1) {
                            val filteredLogs by remember(logMessages.size, logFilterTab) {
                                derivedStateOf {
                                    when(logFilterTab) {
                                        "CYCLE" -> logMessages.filter { it.contains("사이클") || it.contains("탐색") || it.contains("대기") || it.contains("점프") || it.contains("검색") || it.contains("페이지") }
                                        "BLOCK" -> logMessages.filter { it.contains("차단") || it.contains("삭제") || it.contains("악성") || it.contains("악플") }
                                        else -> logMessages.toList()
                                    }
                                }
                            }

                            val isAtBottom by remember { derivedStateOf { val info = logListState.layoutInfo.visibleItemsInfo; if (info.isEmpty()) true else info.last().index >= logListState.layoutInfo.totalItemsCount - 8 } }
                            LaunchedEffect(filteredLogs.size) { if (isAtBottom && filteredLogs.isNotEmpty()) logListState.scrollToItem(filteredLogs.size - 1) }

                            Column(modifier = Modifier.fillMaxSize().padding(vertical = 16.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                                    Button(
                                        onClick = {
                                            logMessages.clear()
                                            try {
                                                val logFile = File(File(context.filesDir, "bot_logs"), "log_$botId.txt")
                                                if (logFile.exists()) logFile.delete()
                                            } catch (_: Exception) {
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if(isDarkMode) Color(0xFF37474F) else PastelNavyLight,
                                            contentColor = if(isDarkMode) Color.White else PastelNavy
                                        ),
                                        modifier = Modifier.fillMaxWidth().height(50.dp),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(
                                            androidx.compose.material.icons.Icons.Filled.Delete,
                                            contentDescription = "삭제",
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("로그 지우기", fontWeight = FontWeight.Bold)
                                    }
                                }

                                Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = logFilterTab == "ALL", onClick = { logFilterTab = "ALL" }, label = { Text("전체 로그", color = textColor) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = PastelNavy, selectedLabelColor = Color.White))
                                    FilterChip(selected = logFilterTab == "CYCLE", onClick = { logFilterTab = "CYCLE" }, label = { Text("탐색/사이클", color = textColor) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = PastelNavy, selectedLabelColor = Color.White))
                                    FilterChip(selected = logFilterTab == "BLOCK", onClick = { logFilterTab = "BLOCK" }, label = { Text("차단 내역", color = textColor) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = warningRed, selectedLabelColor = Color.White))
                                }

                                Box(modifier = Modifier.weight(1f).fillMaxWidth().background(DarkTerminal, RoundedCornerShape(12.dp)).padding(12.dp)) {
                                    SelectionContainer {
                                        LazyColumn(state = logListState, modifier = Modifier.fillMaxSize()) {
                                            items(filteredLogs.size) { index ->
                                                val msg = filteredLogs[index]
                                                val logTextColor = when {
                                                    msg.contains("차단") || msg.contains("삭제") || msg.contains("오류") || msg.contains("실패") -> Color(0xFFFF5252)
                                                    msg.contains("대기") || msg.contains("디버그") || msg.contains("경고") -> Color(0xFFFFD740)
                                                    msg.contains("시작") || msg.contains("완료") || msg.contains("돌파") -> Color(0xFF69F0AE)
                                                    else -> Color(0xFFE0E0E0)
                                                }
                                                Text(text = msg, color = logTextColor, fontSize = 13.sp)
                                                Spacer(modifier = Modifier.height(6.dp))
                                            }
                                        }
                                    }
                                    Column(modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                                        Button(onClick = { coroutineScope.launch { if (filteredLogs.isNotEmpty()) logListState.scrollToItem(0) } }, colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha=0.2f)), contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp)) { Text("▲", color = Color.White) }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Button(onClick = { coroutineScope.launch { if (filteredLogs.isNotEmpty()) logListState.scrollToItem(filteredLogs.size - 1) } }, colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha=0.2f)), contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp)) { Text("▼", color = Color.White) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (showEditNameDialog) {
                AlertDialog(
                    containerColor = dialogBgColor, titleContentColor = textColor, textContentColor = textColor,
                    onDismissRequest = { showEditNameDialog = false },
                    title = { Text("봇 이름 수정", fontWeight = FontWeight.Bold) },
                    text = { OutlinedTextField(value = newBotNameInput, onValueChange = { newBotNameInput = it }, label = { Text("새로운 이름") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor)) },
                    confirmButton = { Button(onClick = { if (newBotNameInput.isNotBlank()) { botName = newBotNameInput; botPref.edit().putString("bot_name", newBotNameInput).apply(); showEditNameDialog = false } }, colors = ButtonDefaults.buttonColors(containerColor = PastelNavy)) { Text("저장", color = Color.White) } },
                    dismissButton = { TextButton(onClick = { showEditNameDialog = false }) { Text("취소", color = subTextColor) } }
                )
            }
        }

        if (editDialogType != null) {
            val isSingleLine = editDialogType == "bot_name" || editDialogType == "block_reason"
            val title = when(editDialogType) {
                "bot_name" -> "봇 이름 수정"; "block_reason" -> "차단 사유 설정"; "normal" -> "일반 금지어 설정"; "bypass" -> "우회 금지어 설정"; "search" -> "검색어 설정"; "url" -> "관리할 갤러리 URL 설정"; "url_whitelist" -> "허용할 URL 도메인 설정"; "user_blacklist" -> "차단할 유저 ID/IP 설정"; "user_whitelist" -> "보호할 유저 ID/IP 설정"; "nickname_blacklist" -> "차단할 닉네임 설정"; "nickname_whitelist" -> "보호할 닉네임 설정"; "image_alt_blacklist" -> "차단할 이미지 alt값 설정"; "voice_blacklist" -> "차단할 보이스 ID 설정"; else -> ""
            }
            val placeholderMsg = when(editDialogType) {
                "bot_name" -> "새로운 봇 이름을 입력하세요"; "block_reason" -> "예: 커뮤니티 규칙 위반"; "url" -> "줄바꿈으로 구분합니다. (# ← 뒷부분은 무시됨)\n[예시]\nhttps://gall.dcinside.com/..."; "user_blacklist", "user_whitelist" -> "줄바꿈으로 구분합니다. (# ← 뒷부분은 무시됨)\n[예시]\ngonick1234 #김고닉\n123.456 #박유동"; "nickname_blacklist", "nickname_whitelist" -> "줄바꿈으로 구분합니다. (# ← 뒷부분은 무시됨)\n[예시]\n김고닉 #호감고닉\n김분탕 #분탕고닉 등"; "image_alt_blacklist" -> "줄바꿈으로 구분합니다. (# ← 뒷부분은 무시됨)\n[예시]\n759b8005c4... #광고1"; "voice_blacklist" -> "39a4c023b... #어그로보플"; else -> "줄바꿈으로 구분합니다. (# ← 뒷부분은 무시됨)\n[예시]\n사과 #금지어1"
            }

            AlertDialog(
                containerColor = dialogBgColor, titleContentColor = textColor, textContentColor = textColor,
                onDismissRequest = { editDialogType = null }, title = { Text(title, fontWeight = FontWeight.Bold) },
                text = { OutlinedTextField(value = tempEditText, onValueChange = { tempEditText = it }, placeholder = { Text(placeholderMsg) }, singleLine = isSingleLine, modifier = if (isSingleLine) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().height(250.dp), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor)) },
                confirmButton = { Button(onClick = {
                    when(editDialogType) {
                        "bot_name" -> { botName = tempEditText; botPref.edit().putString("bot_name", tempEditText).apply() }
                        "block_reason" -> { blockReasonText = tempEditText; botPref.edit().putString("block_reason_text", tempEditText).apply() }
                        "normal" -> { normalWordsText = tempEditText; botPref.edit().putStringSet("normal", tempEditText.split("\n").map{it.trim()}.filter{it.isNotEmpty()}.toSet()).apply() }
                        "bypass" -> { bypassWordsText = tempEditText; botPref.edit().putStringSet("bypass", tempEditText.split("\n").map{it.trim()}.filter{it.isNotEmpty()}.toSet()).apply() }
                        "search" -> { searchWordsText = tempEditText; botPref.edit().putStringSet("search_keywords", tempEditText.split("\n").map{it.trim()}.filter{it.isNotEmpty()}.toSet()).apply() }
                        "url" -> { targetUrlsText = tempEditText; botPref.edit().putString("target_urls", tempEditText).apply() }
                        "url_whitelist" -> { urlWhitelistText = tempEditText; botPref.edit().putStringSet("url_whitelist", tempEditText.split("\n").map{it.trim()}.filter{it.isNotEmpty()}.toSet()).apply() }
                        "user_blacklist" -> { userBlacklistText = tempEditText; botPref.edit().putStringSet("user_blacklist", tempEditText.split("\n").map{it.trim()}.filter{it.isNotEmpty()}.toSet()).apply() }
                        "user_whitelist" -> { userWhitelistText = tempEditText; botPref.edit().putStringSet("user_whitelist", tempEditText.split("\n").map{it.trim()}.filter{it.isNotEmpty()}.toSet()).apply() }
                        "nickname_blacklist" -> { nicknameBlacklistText = tempEditText; botPref.edit().putStringSet("nickname_blacklist", tempEditText.split("\n").map{it.trim()}.filter{it.isNotEmpty()}.toSet()).apply() }
                        "nickname_whitelist" -> { nicknameWhitelistText = tempEditText; botPref.edit().putStringSet("nickname_whitelist", tempEditText.split("\n").map{it.trim()}.filter{it.isNotEmpty()}.toSet()).apply() }
                        "image_alt_blacklist" -> { imageAltBlacklistText = tempEditText; botPref.edit().putStringSet("image_alt_blacklist", tempEditText.split("\n").map{it.trim()}.filter{it.isNotEmpty()}.toSet()).apply() }
                        "voice_blacklist" -> { voiceBlacklistText = tempEditText; botPref.edit().putStringSet("voice_blacklist", tempEditText.split("\n").map{it.trim()}.filter{it.isNotEmpty()}.toSet()).apply() }
                    }
                    editDialogType = null; Toast.makeText(context, "저장되었습니다.", Toast.LENGTH_SHORT).show()
                }, colors = ButtonDefaults.buttonColors(containerColor = PastelNavy)) { Text("저장", color = Color.White) } },
                dismissButton = { TextButton(onClick = { editDialogType = null }) { Text("취소", color = subTextColor) } }
            )
        }

        val alts = extractedAltsList
        if (alts != null) {
            AlertDialog(
                containerColor = dialogBgColor, titleContentColor = textColor, textContentColor = textColor,
                onDismissRequest = { extractedAltsList = null }, title = { Text("이미지 alt 값 (${alts.size}개)", fontWeight = FontWeight.Bold) },
                text = {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp)) {
                        items(alts.size) { index ->
                            val altVal = alts[index]
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(value = altVal, onValueChange = {}, readOnly = true, singleLine = true, modifier = Modifier.weight(1f), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor))
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(onClick = { copyToClipboard(context, altVal, "개별 이미지 alt") }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(55.dp), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = if(isDarkMode) Color(0xFF37474F) else PastelNavyLight, contentColor = if(isDarkMode) Color.White else PastelNavy)) { Text("복사", fontSize = 12.sp) }
                            }
                        }
                    }
                },
                confirmButton = { Button(onClick = { copyToClipboard(context, alts.joinToString("\n"), "전체 이미지 alt") }, colors = ButtonDefaults.buttonColors(containerColor = PastelNavy)) { Text("모두 복사", color = Color.White) } },
                dismissButton = { TextButton(onClick = { extractedAltsList = null }) { Text("닫기", color = subTextColor) } }
            )
        }

        if (extractedAltsError != null) {
            AlertDialog(containerColor = dialogBgColor, titleContentColor = textColor, textContentColor = textColor, onDismissRequest = { extractedAltsError = null }, title = { Text("추출 실패") }, text = { Text(extractedAltsError!!) }, confirmButton = { Button(onClick = { extractedAltsError = null }, colors = ButtonDefaults.buttonColors(containerColor = PastelNavy)) { Text("확인", color = Color.White) } })
        }

        if (extractedVoiceResult != null) {
            AlertDialog(
                containerColor = dialogBgColor, titleContentColor = textColor, textContentColor = textColor,
                onDismissRequest = { extractedVoiceResult = null }, title = { Text("보이스 ID 추출 결과", fontWeight = FontWeight.Bold) },
                text = { OutlinedTextField(value = extractedVoiceResult!!, onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor)) },
                confirmButton = { if (!extractedVoiceResult!!.contains("찾을 수 없")) { Button(onClick = { copyToClipboard(context, extractedVoiceResult!!, "보이스 ID") }, colors = ButtonDefaults.buttonColors(containerColor = PastelNavy)) { Text("복사", color = Color.White) } } },
                dismissButton = { TextButton(onClick = { extractedVoiceResult = null }) { Text("닫기", color = subTextColor) } }
            )
        }

        if (htmlSnapshotPathToView != null) {
            var isShowingInitial by remember { mutableStateOf(false) }
            val currentPath = remember(htmlSnapshotPathToView, isShowingInitial) {
                if (isShowingInitial && htmlSnapshotPathToView!!.endsWith("_latest.html")) htmlSnapshotPathToView!!.replace("_latest.html", "_initial.html") else htmlSnapshotPathToView!!
            }
            val initialFileExists = remember(htmlSnapshotPathToView) {
                if (htmlSnapshotPathToView!!.endsWith("_latest.html")) File(htmlSnapshotPathToView!!.replace("_latest.html", "_initial.html")).exists() else false
            }

            Dialog(onDismissRequest = { htmlSnapshotPathToView = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(modifier = Modifier.fillMaxSize(), color = if(isDarkMode) Color.Black else Color.White) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF1E2329)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Close, contentDescription = "닫기", modifier = Modifier.clickable { htmlSnapshotPathToView = null }.padding(end=16.dp), tint = Color.White)
                            Text(text = if (isShowingInitial) "최초 스냅샷 확인" else "최신 스냅샷 뷰어", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
                            if (initialFileExists) {
                                Button(
                                    onClick = { isShowingInitial = !isShowingInitial },
                                    colors = ButtonDefaults.buttonColors(containerColor = if (isShowingInitial) Color(0xFFD32F2F) else Color(0xFF7F8C8D)),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp), modifier = Modifier.height(32.dp)
                                ) { Text(text = if (isShowingInitial) "최신 스냅샷" else "최초 스냅샷", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                        AndroidView(
                            factory = { ctx ->
                                android.webkit.WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.mediaPlaybackRequiresUserGesture = false
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                    }
                                    webChromeClient = android.webkit.WebChromeClient()
                                    webViewClient = object : android.webkit.WebViewClient() {
                                        override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                            val url = request?.url?.toString() ?: return false
                                            if (url.startsWith("http")) { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))); return true }
                                            return false
                                        }
                                        override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            view?.evaluateJavascript("""
                                                (function() {
                                                    ['header.header','nav.nav','.side-box','.ad-wrap','.adv-group','.adv-groupno','.adv-groupin','.ad-md','.pwlink','.con-search-box','.outside-search-box','.view-btm-con','.reco-search','#singoPopup','#blockLayer','#voice_share','#sns_share'].forEach(function(s){document.querySelectorAll(s).forEach(function(e){e.style.display='none';});});
                                                    document.body.style.maxWidth='100%';
                                                    document.body.style.fontSize='14px';
                                                    document.body.style.wordBreak='break-all';
                                                    document.querySelectorAll('img,video').forEach(function(e){e.style.maxWidth='100%';e.style.height='auto';});
                                                    document.querySelectorAll('.write_div').forEach(function(e){e.style.lineHeight='1.6';});
                                                })();
                                            """.trimIndent(), null)
                                        }
                                    }
                                }
                            },
                            update = { webView ->
                                val file = File(currentPath)
                                if (file.exists()) {
                                    val htmlData = file.readText()
                                    val parts = file.nameWithoutExtension.split("_")
                                    val spoofedBaseUrl = if (parts.size >= 2) "https://gall.dcinside.com/board/view/?id=${parts[0]}&no=${parts[1]}" else "https://gall.dcinside.com/"
                                    webView.loadDataWithBaseURL(spoofedBaseUrl, htmlData, "text/html", "UTF-8", null)
                                } else webView.loadDataWithBaseURL(null, "<h3 style='padding:20px; text-align:center;'>스냅샷 파일이 삭제되었거나 존재하지 않습니다.</h3>", "text/html", "UTF-8", null)
                            },
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        )
                    }
                }
            }
        }

        if (showConfirmDialog != null) {
            val confirmTitle = when(showConfirmDialog) {
                "db_reset" -> "공용 DB 초기화"
                "logout" -> "로그아웃"
                "delete" -> "봇 삭제"
                "crash_test" -> "강제 종료 테스트"
                else -> ""
            }

            val confirmMsg = when(showConfirmDialog) {
                "db_reset" -> "공용 DB를 초기화하시겠습니까?\n(모든 봇이 공유하는 기록이 삭제됩니다)"
                "logout" -> "이 봇을 로그아웃하겠습니까?\n(재접속이 필요합니다)"
                "delete" -> "이 봇을 완전히 삭제하겠습니까?\n(복구할 수 없습니다)"
                "crash_test" -> "앱 프로세스를 즉시 종료합니다.\n자동 복구가 정상이라면 잠시 후 봇이 다시 살아나야 합니다."
                else -> ""
            }
            AlertDialog(
                containerColor = dialogBgColor, titleContentColor = textColor, textContentColor = textColor,
                onDismissRequest = { showConfirmDialog = null }, title = { Text(confirmTitle, fontWeight = FontWeight.Bold) }, text = { Text(confirmMsg) },
                confirmButton = { Button(onClick = {
                    when(showConfirmDialog) {
                        "db_reset" -> {
                            GlobalBotState.clearDb(context)
                            rememberedPostCount = 0
                            lastCheckedNumber = 0
                            logMessages.add("[${getCurrentTimeStr()}] 공용 DB 초기화됨!")
                        }

                        "logout" -> {
                            context.startService(
                                Intent(context, BotService::class.java).apply {
                                    putExtra("BOT_ID", botId)
                                    action = "STOP"
                                }
                            )
                            isRunning = false
                            botPref.edit().remove("saved_cookie").apply()
                            myCookie = null
                            CookieManager.getInstance().removeAllCookies(null)
                            CookieManager.getInstance().flush()
                        }

                        "delete" -> {
                            context.startService(
                                Intent(context, BotService::class.java).apply {
                                    putExtra("BOT_ID", botId)
                                    action = "STOP"
                                }
                            )
                            val masterIdsStr = masterPref.getString("bot_ids_list", "") ?: ""
                            val currentIds = masterIdsStr.split(",").filter { it.isNotBlank() }.toMutableList()
                            currentIds.remove(botId)
                            masterPref.edit().putString("bot_ids_list", currentIds.joinToString(",")).apply()
                            botPref.edit().clear().apply()
                            GlobalBotState.logs.remove(botId)
                            onBack()
                        }

                        "crash_test" -> {
                            logMessages.add("[${getCurrentTimeStr()}] [개발자] 강제 종료 테스트 실행")
                            Process.killProcess(Process.myPid())
                        }
                    }
                    showConfirmDialog = null
                }, colors = ButtonDefaults.buttonColors(containerColor = if (showConfirmDialog == "delete") warningRed else PastelNavy)) { Text("예", color = Color.White) } },
                dismissButton = { TextButton(onClick = { showConfirmDialog = null }) { Text("취소", color = subTextColor) } }
            )
        }
    }
}