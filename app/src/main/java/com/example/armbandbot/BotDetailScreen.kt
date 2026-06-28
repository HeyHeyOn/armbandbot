package com.heyheyon.armbandbot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Process
import android.webkit.CookieManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale



private fun fetchPostDocument(url: String) = Jsoup.connect(DcinsidePostUrls.desktopUrl(url))
    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
    .get()

private fun fetchCommentDcconRefs(url: String, postHtml: String): List<DcconRef> {
    val locator = DcinsidePostUrls.parsePostLocator(url) ?: return emptyList()
    val doc = Jsoup.parse(postHtml)
    val esnoToken = doc.select("input[id=e_s_n_o]").attr("value")
    val refs = linkedMapOf<String, DcconRef>()
    for (page in 1..5) {
        val response = Jsoup.connect("https://gall.dcinside.com/board/comment/")
            .userAgent("Mozilla/5.0")
            .header("Referer", locator.refererUrl)
            .header("X-Requested-With", "XMLHttpRequest")
            .data("id", locator.gallId)
            .data("no", locator.postNo)
            .data("cmt_id", locator.gallId)
            .data("cmt_no", locator.postNo)
            .data("e_s_n_o", esnoToken)
            .data("comment_page", page.toString())
            .data("sort", "D")
            .data("_GALLTYPE_", locator.gallType)
            .ignoreContentType(true)
            .method(org.jsoup.Connection.Method.POST)
            .execute()
        val pageRefs = DcconFilter.extractDcconRefsFromCommentApiJson(response.body())
        pageRefs.forEach { refs.putIfAbsent("${it.source}:${it.token}", it) }
        if (pageRefs.isEmpty() || !response.body().contains("\"comments\"")) break
    }
    return refs.values.toList()
}

private fun fetchDcconPackageDetail(tokenOrUrl: String): DcconPackageDetail? {
    val token = DcconFilter.normalizeBlacklistEntry(tokenOrUrl) ?: return null
    val response = Jsoup.connect("https://gall.dcinside.com/dccon/package_detail")
        .userAgent("Mozilla/5.0")
        .header("X-Requested-With", "XMLHttpRequest")
        .data("ci_t", "")
        .data("package_idx", "")
        .data("code", token)
        .ignoreContentType(true)
        .method(org.jsoup.Connection.Method.POST)
        .execute()
    return DcconFilter.parsePackageDetailJson(response.body())
}

@Composable
private fun DcconPreviewImage(tokenOrUrl: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(DcconFilter.buildImageUrl(tokenOrUrl))
            .setHeader("Referer", "https://gall.dcinside.com/")
            .setHeader("User-Agent", "Mozilla/5.0")
            .crossfade(true)
            .build(),
        contentDescription = "디시콘",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(8.dp))
    )
}

private fun dcconTokensFromBlacklistText(text: String): List<String> = DcconFilter.normalizeBlacklistText(text)
    .lineSequence()
    .mapNotNull { DcconFilter.normalizeBlacklistEntry(it.substringBefore("#")) }
    .distinct()
    .toList()

private fun imageAltRefsFromBlacklistText(text: String): List<ImageAltRef> = DcconFilter.normalizeImageAltBlacklistText(text)
    .lineSequence()
    .map { it.trim() }
    .filter { it.isNotBlank() }
    .map { line ->
        val alt = DcconFilter.imageAltMatchValue(line)
        val previewUrl = line.substringAfter("#", "").trim().takeIf { it.startsWith("http") || it.startsWith("//") }
            ?.let { if (it.startsWith("//")) "https:$it" else it }
        ImageAltRef(alt, previewUrl)
    }
    .toList()

@Composable
private fun ImageAltPreviewImage(imageUrl: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .setHeader("Referer", "https://gall.dcinside.com/")
                    .setHeader("User-Agent", "Mozilla/5.0")
                    .build(),
                contentDescription = "이미지 미리보기",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(Icons.Filled.Image, contentDescription = null, tint = Color(0xFF9E9E9E), modifier = Modifier.size(28.dp))
        }
    }
}

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
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

    fun safeKkangCriteriaInt(key: String, defaultValue: Int): Int = when (val value = botPref.all[key]) {
        is Int -> value
        is String -> value.toIntOrNull() ?: defaultValue
        is Long -> value.toInt()
        else -> defaultValue
    }
    fun currentKkangCriteriaDescription(): String {
        val mode = (botPref.all["kkang_detection_mode"] as? String)
            ?.takeIf { it in setOf("total", "separate", "dc_mark") }
            ?: "separate"
        return when (mode) {
            "total" -> "글+댓글 수 ${safeKkangCriteriaInt("kkang_total_min", 15)} 미만"
            "dc_mark" -> "신규 고정닉 표시"
            else -> "글 수 ${safeKkangCriteriaInt("kkang_post_min", 5)} 미만 또는 댓글 수 ${safeKkangCriteriaInt("kkang_comment_min", 10)} 미만"
        }
    }
    fun kkangCriteriaGuideText(): String = "깡계 필터 설정의 판정 기준을 따릅니다. (현재 기준: ${currentKkangCriteriaDescription()})"

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
    var isAutoLoginInProgress by remember { mutableStateOf(false) }
    var shouldOpenWebViewFallback by remember { mutableStateOf(botPref.getBoolean("session_webview_fallback_pending", false)) }
    var sessionRecoveryReason by remember { mutableStateOf(botPref.getString("session_recovery_reason", null)) }

    var editDialogType by remember { mutableStateOf<String?>(null) }
    var tempEditText by remember { mutableStateOf("") }
    var showConfirmDialog by remember { mutableStateOf<String?>(null) }

    var imageAltExtractUrlText by remember { mutableStateOf("") }
    var dcconExtractUrlText by remember { mutableStateOf("") }
    var isExtractingImageAlts by remember { mutableStateOf(false) }
    var isExtractingDccons by remember { mutableStateOf(false) }
    var isAddingDcconPackage by remember { mutableStateOf(false) }
    var isDcconBlacklistDialogOpen by remember { mutableStateOf(false) }
    var isImageAltBlacklistDialogOpen by remember { mutableStateOf(false) }
    var imageAltBlacklistDraftText by remember { mutableStateOf("") }
    var imageAltBlacklistAddText by remember { mutableStateOf("") }
    var isImageAltAddInputVisible by remember { mutableStateOf(false) }
    var selectedImageAlts by remember { mutableStateOf<Set<String>>(emptySet()) }
    var imageAltDeleteConfirmAlts by remember { mutableStateOf<Set<String>?>(null) }
    var dcconBlacklistDraftText by remember { mutableStateOf("") }
    var dcconBlacklistAddText by remember { mutableStateOf("") }
    var isDcconAddInputVisible by remember { mutableStateOf(false) }
    var isDcconExtractInputVisible by remember { mutableStateOf(false) }
    var selectedDcconTokens by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedDcconGroupName by remember { mutableStateOf<String?>(null) }
    var dcconDeleteConfirmTokens by remember { mutableStateOf<Set<String>?>(null) }
    var extractedAltsList by remember { mutableStateOf<List<ImageAltRef>?>(null) }
    var extractedDcconsList by remember { mutableStateOf<List<DcconRef>?>(null) }
    var extractedAltsError by remember { mutableStateOf<String?>(null) }
    var extractVoiceText by remember { mutableStateOf("") }
    var extractedVoiceResult by remember { mutableStateOf<String?>(null) }

    fun resumeBotWithRecoveredSession(extractedCookie: String) {
        botPref.edit()
            .putString("saved_cookie", extractedCookie)
            .putBoolean("session_login_required", false)
            .putBoolean("session_webview_fallback_pending", false)
            .remove("session_recovery_reason")
            .apply()
        myCookie = extractedCookie
        shouldOpenWebViewFallback = false
        sessionRecoveryReason = null

        if (isAutoLoginInProgress) {
            isAutoLoginInProgress = false
            val serviceIntent = Intent(context, BotService::class.java).apply {
                putExtra("BOT_ID", botId)
                putExtra("COOKIE", extractedCookie)
                action = "START"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent)
            else context.startService(serviceIntent)
            botPref.edit().putBoolean("is_running", true).putBoolean("should_restore_after_restart", true).apply()
        }
    }

    if (myCookie == null || isAutoLoginInProgress) {
        BotLoginScreen(
            botId = botId,
            preferWebViewFallback = shouldOpenWebViewFallback,
            recoveryReason = sessionRecoveryReason,
            onLoginSuccess = { extractedCookie ->
                resumeBotWithRecoveredSession(extractedCookie)
            },
            onBack = {
                if (isAutoLoginInProgress) {
                    isAutoLoginInProgress = false
                    shouldOpenWebViewFallback = false
                } else onBack()
            }
        )
    } else {
        val coroutineScope = rememberCoroutineScope()
        val dcconPackageCache = remember { mutableMapOf<String, DcconPackageDetail>() }
        var selectedTabIndex by remember { mutableStateOf(0) }
        val logFilterKeys = listOf("CYCLE", "BLOCK", "DEBUG", "AI", "HEALTH", "SESSION", "ERROR")
        val savedLogFilters = botPref.getStringSet("activity_log_selected_filters", logFilterKeys.toSet())
            ?.filter { it in logFilterKeys }
            ?: logFilterKeys
        val selectedLogFilters = remember(botId) { mutableStateListOf<String>().apply { addAll(savedLogFilters) } }
        fun persistLogFilters() {
            botPref.edit().putStringSet("activity_log_selected_filters", selectedLogFilters.toSet()).apply()
        }
        val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            runCatching {
                writeBotSettingsJson(context, uri.toString(), exportBotSettings(context, botId))
            }.onSuccess {
                Toast.makeText(context, "JSON 설정 파일을 저장했습니다.", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, it.message ?: "JSON 설정 파일 저장에 실패했습니다.", Toast.LENGTH_LONG).show()
            }
        }

        LaunchedEffect(openBlockLogTrigger) {
            if (openBlockLogTrigger) { selectedTabIndex = 1; selectedLogFilters.clear(); selectedLogFilters.add("BLOCK"); persistLogFilters(); onTriggerConsumed() }
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
                logMessages.addAll(fileLogs.takeLast(5000))
            }
        }

        var currentSubScreen by remember { mutableStateOf<String?>(null) }
        BackHandler(enabled = currentSubScreen != null) { currentSubScreen = null }
        BackHandler(enabled = currentSubScreen == null) { onBack() }

        val actionModeOptions = linkedMapOf("delete" to "삭제", "block" to "차단", "hold" to "보류")
        fun actionModeToPref(mode: String): String = when (mode) { "delete" -> "DELETE"; "hold" -> "HOLD"; else -> "BLOCK" }
        fun readActionMode(prefix: String? = null, fallbackDeleteOnly: Boolean = false): String {
            val processKey = prefix?.let { "${it}_block_process_mode" } ?: "block_process_mode"
            val deleteKey = prefix?.let { "${it}_delete_only_mode" } ?: "delete_only_mode"
            return when (botPref.getString(processKey, null)) {
                "HOLD" -> "hold"
                "DELETE" -> "delete"
                "BLOCK" -> "block"
                else -> if (botPref.getBoolean(deleteKey, fallbackDeleteOnly)) "delete" else "block"
            }
        }
        fun saveActionMode(prefix: String? = null, mode: String) {
            val processKey = prefix?.let { "${it}_block_process_mode" } ?: "block_process_mode"
            val deleteKey = prefix?.let { "${it}_delete_only_mode" } ?: "delete_only_mode"
            botPref.edit()
                .putString(processKey, actionModeToPref(mode))
                .putBoolean(deleteKey, mode == "delete")
                .apply()
        }
        val blockDurationOptions = linkedMapOf(1 to "1시간", 6 to "6시간", 24 to "24시간 (1일)", 168 to "168시간 (7일)", 336 to "336시간 (14일)", 744 to "744시간 (31일)")
        val galleryRefreshIntervalOptions = linkedMapOf(5 to "5분", 10 to "10분", 30 to "30분", 60 to "1시간", 180 to "3시간", 360 to "6시간")
        val galleryProxyTimeOptions = linkedMapOf(60 to "1시간", 360 to "6시간", 1440 to "24시간", 2880 to "48시간")
        val galleryMobileTimeOptions = linkedMapOf(10 to "10분", 30 to "30분", 60 to "1시간", 180 to "3시간", 720 to "12시간")
        val galleryMobileIpsTimeOptions = linkedMapOf(180 to "3시간", 360 to "6시간", 720 to "12시간", 1440 to "24시간")
        val galleryImageBlockTimeOptions = linkedMapOf(60 to "1시간", 360 to "6시간", 1440 to "24시간", 2880 to "48시간")

        var isGallerySettingRefreshEnabled by remember { mutableStateOf(botPref.getBoolean("gallery_setting_refresh_enabled", false)) }
        var gallerySettingRefreshIntervalMinutes by remember { mutableStateOf(botPref.getInt("gallery_setting_refresh_interval_minutes", 30).takeIf { it in galleryRefreshIntervalOptions.keys } ?: 30) }
        var gallerySettingProxyUse by remember { mutableStateOf(botPref.getBoolean("gallery_setting_proxy_use", false)) }
        var gallerySettingProxyTimeMinutes by remember { mutableStateOf(botPref.getInt("gallery_setting_proxy_time_minutes", 2880).takeIf { it in galleryProxyTimeOptions.keys } ?: 2880) }
        var gallerySettingMobileUse by remember { mutableStateOf(botPref.getBoolean("gallery_setting_mobile_use", false)) }
        var gallerySettingMobileTimeMinutes by remember { mutableStateOf(botPref.getInt("gallery_setting_mobile_time_minutes", 720).takeIf { it in galleryMobileTimeOptions.keys } ?: 720) }
        var gallerySettingMobileIpsUse by remember { mutableStateOf(botPref.getBoolean("gallery_setting_mobile_ips_use", false)) }
        var gallerySettingMobileIpsTimeMinutes by remember { mutableStateOf(botPref.getInt("gallery_setting_mobile_ips_time_minutes", 1440).takeIf { it in galleryMobileIpsTimeOptions.keys } ?: 1440) }
        var gallerySettingImageBlockUse by remember { mutableStateOf(botPref.getBoolean("gallery_setting_image_block_use", false)) }
        var gallerySettingImageBlockTimeMinutes by remember { mutableStateOf(botPref.getInt("gallery_setting_image_block_time_minutes", 2880).takeIf { it in galleryImageBlockTimeOptions.keys } ?: 2880) }
        var gallerySettingImageBlockProxy by remember { mutableStateOf(botPref.getBoolean("gallery_setting_image_block_proxy", true)) }
        var gallerySettingImageBlockMobile by remember { mutableStateOf(botPref.getBoolean("gallery_setting_image_block_mobile", false)) }
        var gallerySettingImageBlockAll by remember { mutableStateOf(botPref.getBoolean("gallery_setting_image_block_all", false)) }
        var gallerySettingDropdownKey by remember { mutableStateOf<String?>(null) }

        @Composable
        fun MinuteDropdownRow(title: String, value: Int, options: Map<Int, String>, dropdownKey: String, enabled: Boolean = true, onSelect: (Int) -> Unit) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, color = textColor, fontWeight = FontWeight.Bold)
                Box {
                    OutlinedButton(enabled = enabled, onClick = { gallerySettingDropdownKey = dropdownKey }) {
                        Text(options[value] ?: "${value}분", color = textColor)
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy)
                    }
                    DropdownMenu(expanded = gallerySettingDropdownKey == dropdownKey, onDismissRequest = { gallerySettingDropdownKey = null }, modifier = Modifier.background(dialogBgColor)) {
                        options.forEach { (minutes, label) ->
                            DropdownMenuItem(text = { Text(label, color = textColor) }, onClick = { onSelect(minutes); gallerySettingDropdownKey = null })
                        }
                    }
                }
            }
        }

        @Composable
        fun GallerySwitchRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(title, color = textColor, fontWeight = FontWeight.Bold)
                    if (subtitle != null) Text(subtitle, color = subTextColor, fontSize = 12.sp)
                }
                Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
            }
        }

        // 기본 차단 설정
        var blockActionMode by remember { mutableStateOf(readActionMode()) }
        var blockDurationHours by remember { mutableStateOf(botPref.getInt("block_duration_hours", 6)) }
        var isBlockActionModeDropdownExpanded by remember { mutableStateOf(false) }
        var isBlockDurationDropdownExpanded by remember { mutableStateOf(false) }
        var blockReasonText by remember { mutableStateOf(botPref.getString("block_reason_text", "커뮤니티 규칙 위반") ?: "커뮤니티 규칙 위반") }
        var isDeletePostOnBlock by remember { mutableStateOf(botPref.getBoolean("delete_post_on_block", true)) }
        var isDeleteOnlyMode by remember { mutableStateOf(botPref.getBoolean("delete_only_mode", false)) }
        var blockExemptPostNumbersText by remember { mutableStateOf(botPref.getStringSet("block_exempt_post_numbers", setOf())?.joinToString("\n") ?: "") }

        // 금지어 필터 개별 차단 설정
        var keywordUseCustomAction by remember { mutableStateOf(botPref.getBoolean("keyword_use_custom_action_config", false)) }
        var keywordActionMode by remember { mutableStateOf(readActionMode("keyword", isDeleteOnlyMode)) }
        var keywordBlockDurationHours by remember { mutableStateOf(botPref.getInt("keyword_block_duration_hours", blockDurationHours)) }
        var isKeywordActionModeDropdownExpanded by remember { mutableStateOf(false) }
        var isKeywordBlockDurationDropdownExpanded by remember { mutableStateOf(false) }
        var keywordBlockReasonText by remember { mutableStateOf(botPref.getString("keyword_block_reason_text", null) ?: blockReasonText) }
        var keywordDeletePostOnBlock by remember { mutableStateOf(if (botPref.contains("keyword_delete_post_on_block")) botPref.getBoolean("keyword_delete_post_on_block", true) else isDeletePostOnBlock) }
        var keywordDeleteOnlyMode by remember { mutableStateOf(if (botPref.contains("keyword_delete_only_mode")) botPref.getBoolean("keyword_delete_only_mode", false) else isDeleteOnlyMode) }
        var keywordApplyYudongOnly by remember { mutableStateOf(botPref.getBoolean("keyword_apply_yudong_only", false)) }
        var keywordApplyKkangOnly by remember { mutableStateOf(botPref.getBoolean("keyword_apply_kkang_only", false)) }

        var userUseCustomAction by remember { mutableStateOf(botPref.getBoolean("user_use_custom_action_config", false)) }
        var userActionMode by remember { mutableStateOf(readActionMode("user", isDeleteOnlyMode)) }
        var userBlockDurationHours by remember { mutableStateOf(botPref.getInt("user_block_duration_hours", blockDurationHours)) }
        var isUserActionModeDropdownExpanded by remember { mutableStateOf(false) }
        var isUserBlockDurationDropdownExpanded by remember { mutableStateOf(false) }
        var userBlockReasonText by remember { mutableStateOf(botPref.getString("user_block_reason_text", null) ?: blockReasonText) }
        var userDeletePostOnBlock by remember { mutableStateOf(if (botPref.contains("user_delete_post_on_block")) botPref.getBoolean("user_delete_post_on_block", true) else isDeletePostOnBlock) }
        var userDeleteOnlyMode by remember { mutableStateOf(if (botPref.contains("user_delete_only_mode")) botPref.getBoolean("user_delete_only_mode", false) else isDeleteOnlyMode) }

        var nicknameUseCustomAction by remember { mutableStateOf(botPref.getBoolean("nickname_use_custom_action_config", false)) }
        var nicknameActionMode by remember { mutableStateOf(readActionMode("nickname", isDeleteOnlyMode)) }
        var nicknameBlockDurationHours by remember { mutableStateOf(botPref.getInt("nickname_block_duration_hours", blockDurationHours)) }
        var isNicknameActionModeDropdownExpanded by remember { mutableStateOf(false) }
        var isNicknameBlockDurationDropdownExpanded by remember { mutableStateOf(false) }
        var nicknameBlockReasonText by remember { mutableStateOf(botPref.getString("nickname_block_reason_text", null) ?: blockReasonText) }
        var nicknameDeletePostOnBlock by remember { mutableStateOf(if (botPref.contains("nickname_delete_post_on_block")) botPref.getBoolean("nickname_delete_post_on_block", true) else isDeletePostOnBlock) }
        var nicknameDeleteOnlyMode by remember { mutableStateOf(if (botPref.contains("nickname_delete_only_mode")) botPref.getBoolean("nickname_delete_only_mode", false) else isDeleteOnlyMode) }

        var urlUseCustomAction by remember { mutableStateOf(botPref.getBoolean("url_use_custom_action_config", false)) }
        var urlActionMode by remember { mutableStateOf(readActionMode("url", isDeleteOnlyMode)) }
        var urlBlockDurationHours by remember { mutableStateOf(botPref.getInt("url_block_duration_hours", blockDurationHours)) }
        var isUrlActionModeDropdownExpanded by remember { mutableStateOf(false) }
        var isUrlBlockDurationDropdownExpanded by remember { mutableStateOf(false) }
        var urlBlockReasonText by remember { mutableStateOf(botPref.getString("url_block_reason_text", null) ?: blockReasonText) }
        var urlDeletePostOnBlock by remember { mutableStateOf(if (botPref.contains("url_delete_post_on_block")) botPref.getBoolean("url_delete_post_on_block", true) else isDeletePostOnBlock) }
        var urlDeleteOnlyMode by remember { mutableStateOf(if (botPref.contains("url_delete_only_mode")) botPref.getBoolean("url_delete_only_mode", false) else isDeleteOnlyMode) }

        var voiceUseCustomAction by remember { mutableStateOf(botPref.getBoolean("voice_use_custom_action_config", false)) }
        var voiceActionMode by remember { mutableStateOf(readActionMode("voice", isDeleteOnlyMode)) }
        var voiceBlockDurationHours by remember { mutableStateOf(botPref.getInt("voice_block_duration_hours", blockDurationHours)) }
        var isVoiceActionModeDropdownExpanded by remember { mutableStateOf(false) }
        var isVoiceBlockDurationDropdownExpanded by remember { mutableStateOf(false) }
        var voiceBlockReasonText by remember { mutableStateOf(botPref.getString("voice_block_reason_text", null) ?: blockReasonText) }
        var voiceDeletePostOnBlock by remember { mutableStateOf(if (botPref.contains("voice_delete_post_on_block")) botPref.getBoolean("voice_delete_post_on_block", true) else isDeletePostOnBlock) }
        var voiceDeleteOnlyMode by remember { mutableStateOf(if (botPref.contains("voice_delete_only_mode")) botPref.getBoolean("voice_delete_only_mode", false) else isDeleteOnlyMode) }

        var imageUseCustomAction by remember { mutableStateOf(botPref.getBoolean("image_use_custom_action_config", false)) }
        var imageActionMode by remember { mutableStateOf(readActionMode("image", isDeleteOnlyMode)) }
        var imageBlockDurationHours by remember { mutableStateOf(botPref.getInt("image_block_duration_hours", blockDurationHours)) }
        var isImageActionModeDropdownExpanded by remember { mutableStateOf(false) }
        var isImageBlockDurationDropdownExpanded by remember { mutableStateOf(false) }
        var imageBlockReasonText by remember { mutableStateOf(botPref.getString("image_block_reason_text", null) ?: blockReasonText) }
        var imageDeletePostOnBlock by remember { mutableStateOf(if (botPref.contains("image_delete_post_on_block")) botPref.getBoolean("image_delete_post_on_block", true) else isDeletePostOnBlock) }
        var imageDeleteOnlyMode by remember { mutableStateOf(if (botPref.contains("image_delete_only_mode")) botPref.getBoolean("image_delete_only_mode", false) else isDeleteOnlyMode) }

        var dcconUseCustomAction by remember { mutableStateOf(botPref.getBoolean("dccon_use_custom_action_config", false)) }
        var dcconActionMode by remember { mutableStateOf(readActionMode("dccon", isDeleteOnlyMode)) }
        var dcconBlockDurationHours by remember { mutableStateOf(botPref.getInt("dccon_block_duration_hours", blockDurationHours)) }
        var isDcconActionModeDropdownExpanded by remember { mutableStateOf(false) }
        var isDcconBlockDurationDropdownExpanded by remember { mutableStateOf(false) }
        var dcconBlockReasonText by remember { mutableStateOf(botPref.getString("dccon_block_reason_text", null) ?: blockReasonText) }
        var dcconDeletePostOnBlock by remember { mutableStateOf(if (botPref.contains("dccon_delete_post_on_block")) botPref.getBoolean("dccon_delete_post_on_block", true) else isDeletePostOnBlock) }
        var dcconDeleteOnlyMode by remember { mutableStateOf(if (botPref.contains("dccon_delete_only_mode")) botPref.getBoolean("dccon_delete_only_mode", false) else isDeleteOnlyMode) }

        var spamUseCustomAction by remember { mutableStateOf(botPref.getBoolean("spam_use_custom_action_config", false)) }
        var spamActionMode by remember { mutableStateOf(readActionMode("spam", isDeleteOnlyMode)) }
        var spamBlockDurationHours by remember { mutableStateOf(botPref.getInt("spam_block_duration_hours", blockDurationHours)) }
        var isSpamActionModeDropdownExpanded by remember { mutableStateOf(false) }
        var isSpamBlockDurationDropdownExpanded by remember { mutableStateOf(false) }
        var spamBlockReasonText by remember { mutableStateOf(botPref.getString("spam_block_reason_text", null) ?: blockReasonText) }
        var spamDeletePostOnBlock by remember { mutableStateOf(if (botPref.contains("spam_delete_post_on_block")) botPref.getBoolean("spam_delete_post_on_block", true) else isDeletePostOnBlock) }
        var spamDeleteOnlyMode by remember { mutableStateOf(if (botPref.contains("spam_delete_only_mode")) botPref.getBoolean("spam_delete_only_mode", false) else isDeleteOnlyMode) }

        var yudongUseCustomAction by remember { mutableStateOf(botPref.getBoolean("yudong_use_custom_action_config", false)) }
        var yudongActionMode by remember { mutableStateOf(readActionMode("yudong", isDeleteOnlyMode)) }
        var yudongBlockDurationHours by remember { mutableStateOf(botPref.getInt("yudong_block_duration_hours", blockDurationHours)) }
        var isYudongActionModeDropdownExpanded by remember { mutableStateOf(false) }
        var isYudongBlockDurationDropdownExpanded by remember { mutableStateOf(false) }
        var yudongBlockReasonText by remember { mutableStateOf(botPref.getString("yudong_block_reason_text", null) ?: blockReasonText) }
        var yudongDeletePostOnBlock by remember { mutableStateOf(if (botPref.contains("yudong_delete_post_on_block")) botPref.getBoolean("yudong_delete_post_on_block", true) else isDeletePostOnBlock) }
        var yudongDeleteOnlyMode by remember { mutableStateOf(if (botPref.contains("yudong_delete_only_mode")) botPref.getBoolean("yudong_delete_only_mode", false) else isDeleteOnlyMode) }

        var overseasIpUseCustomAction by remember { mutableStateOf(botPref.getBoolean("overseas_ip_use_custom_action_config", false)) }
        var overseasIpActionMode by remember { mutableStateOf(readActionMode("overseas_ip", isDeleteOnlyMode)) }
        var overseasIpBlockDurationHours by remember { mutableStateOf(botPref.getInt("overseas_ip_block_duration_hours", blockDurationHours)) }
        var isOverseasIpActionModeDropdownExpanded by remember { mutableStateOf(false) }
        var isOverseasIpBlockDurationDropdownExpanded by remember { mutableStateOf(false) }
        var overseasIpBlockReasonText by remember { mutableStateOf(botPref.getString("overseas_ip_block_reason_text", null) ?: blockReasonText) }
        var overseasIpDeletePostOnBlock by remember { mutableStateOf(if (botPref.contains("overseas_ip_delete_post_on_block")) botPref.getBoolean("overseas_ip_delete_post_on_block", true) else isDeletePostOnBlock) }
        var overseasIpDeleteOnlyMode by remember { mutableStateOf(if (botPref.contains("overseas_ip_delete_only_mode")) botPref.getBoolean("overseas_ip_delete_only_mode", false) else isDeleteOnlyMode) }

        var kkangUseCustomAction by remember { mutableStateOf(botPref.getBoolean("kkang_use_custom_action_config", false)) }
        var kkangActionMode by remember { mutableStateOf(readActionMode("kkang", isDeleteOnlyMode)) }
        var kkangBlockDurationHours by remember { mutableStateOf(botPref.getInt("kkang_block_duration_hours", blockDurationHours)) }
        var isKkangActionModeDropdownExpanded by remember { mutableStateOf(false) }
        var isKkangBlockDurationDropdownExpanded by remember { mutableStateOf(false) }
        var kkangBlockReasonText by remember { mutableStateOf(botPref.getString("kkang_block_reason_text", null) ?: blockReasonText) }
        var kkangDeletePostOnBlock by remember { mutableStateOf(if (botPref.contains("kkang_delete_post_on_block")) botPref.getBoolean("kkang_delete_post_on_block", true) else isDeletePostOnBlock) }
        var kkangDeleteOnlyMode by remember { mutableStateOf(if (botPref.contains("kkang_delete_only_mode")) botPref.getBoolean("kkang_delete_only_mode", false) else isDeleteOnlyMode) }

        var isNotiMaster by remember { mutableStateOf(botPref.getBoolean("noti_master", true)) }
        var isNotiKeyword by remember { mutableStateOf(botPref.getBoolean("noti_keyword", true)) }
        var isNotiUser by remember { mutableStateOf(botPref.getBoolean("noti_user", true)) }
        var isNotiNickname by remember { mutableStateOf(botPref.getBoolean("noti_nickname", true)) }
        var isNotiYudong by remember { mutableStateOf(botPref.getBoolean("noti_yudong", true)) }
        var isNotiUrl by remember { mutableStateOf(botPref.getBoolean("noti_url", true)) }
        var isNotiImage by remember { mutableStateOf(botPref.getBoolean("noti_image", true)) }
        var isNotiVoice by remember { mutableStateOf(botPref.getBoolean("noti_voice", true)) }
        var isNotiSpam by remember { mutableStateOf(botPref.getBoolean("noti_spam", true)) }
        var isNotiAi by remember { mutableStateOf(botPref.getBoolean("noti_ai", true)) }

        fun loadMultilineText(key: String): String {
            return botPref.getString("${key}_text", null)
                ?: botPref.getStringSet(key, emptySet())?.joinToString("\n")
                ?: ""
        }

        fun persistMultilineText(key: String, rawText: String) {
            val normalizedLines = rawText
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val normalizedText = normalizedLines.joinToString("\n")
            val normalizedSet = LinkedHashSet(normalizedLines)

            botPref.edit()
                .putString("${key}_text", normalizedText)
                .putStringSet(key, normalizedSet)
                .commit()
        }

        var targetUrlsText by remember { mutableStateOf(botPref.getString("target_urls", "") ?: "") }
        var isSearchMode by remember { mutableStateOf(botPref.getBoolean("is_search_mode", false)) }
        var searchType by remember { mutableStateOf(botPref.getString("search_type", "search_subject_memo") ?: "search_subject_memo") }
        var isSearchTypeDropdownExpanded by remember { mutableStateOf(false) }
        val searchTypeMap = mapOf("search_subject_memo" to "제목+내용", "search_subject" to "제목", "search_memo" to "내용", "search_name" to "글쓴이", "search_comment" to "댓글")
        var searchWordsText by remember { mutableStateOf(loadMultilineText("search_keywords")) }

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

        var isOverseasIpFilterMode by remember { mutableStateOf(botPref.getBoolean("is_overseas_ip_filter_mode", false)) }
        var isOverseasIpPostBlock by remember { mutableStateOf(botPref.getBoolean("is_overseas_ip_post_block", true)) }
        var isOverseasIpCommentBlock by remember { mutableStateOf(botPref.getBoolean("is_overseas_ip_comment_block", true)) }

        var isUrlFilterMode by remember { mutableStateOf(botPref.getBoolean("is_url_filter_mode", false)) }
        var urlWhitelistText by remember { mutableStateOf(botPref.getStringSet("url_whitelist", setOf())?.joinToString("\n") ?: "") }

        var isImageFilterMode by remember { mutableStateOf(botPref.getBoolean("is_image_filter_mode", false)) }
        var imageFilterThresholdText by remember { mutableStateOf(botPref.getInt("image_filter_threshold", 80).toString()) }
        var imageAltBlacklistText by remember { mutableStateOf(botPref.getStringSet("image_alt_blacklist", setOf())?.joinToString("\n") ?: "") }
        var isDcconFilterMode by remember { mutableStateOf(botPref.getBoolean("is_dccon_filter_mode", false)) }
        var dcconBlacklistText by remember { mutableStateOf(botPref.getStringSet("dccon_blacklist", setOf())?.joinToString("\n") ?: "") }

        var isVoiceFilterMode by remember { mutableStateOf(botPref.getBoolean("is_voice_filter_mode", false)) }
        var voiceBlacklistText by remember { mutableStateOf(botPref.getStringSet("voice_blacklist", setOf())?.joinToString("\n") ?: "") }

        val isAiFilterVisible = true
        var isAiFilterMode by remember { mutableStateOf(botPref.getBoolean("is_ai_filter_mode", false)) }
        var notiAi by remember { mutableStateOf(botPref.getBoolean("noti_ai", true)) }

        var isSpamCodeFilterMode by remember { mutableStateOf(botPref.getBoolean("is_spam_code_filter_mode", false)) }
        var spamCodeLengthText by remember { mutableStateOf(botPref.getInt("spam_code_length", 6).toString()) }

        var normalWordsText by remember { mutableStateOf(loadMultilineText("normal")) }
        var bypassWordsText by remember { mutableStateOf(loadMultilineText("bypass")) }

        var scanPageText by remember { mutableStateOf(botPref.getInt("scan_page_count", 1).toString()) }
        var postMinText by remember { mutableStateOf(botPref.getFloat("delay_post_min_sec", 1.0f).toString()) }
        var postMaxText by remember { mutableStateOf(botPref.getFloat("delay_post_max_sec", 2.5f).toString()) }
        var pageMinText by remember { mutableStateOf(botPref.getFloat("delay_page_min_sec", 2.0f).toString()) }
        var pageMaxText by remember { mutableStateOf(botPref.getFloat("delay_page_max_sec", 4.0f).toString()) }
        var cycleMinText by remember { mutableStateOf(botPref.getFloat("delay_cycle_min_sec", 45.0f).toString()) }
        var cycleMaxText by remember { mutableStateOf(botPref.getFloat("delay_cycle_max_sec", 90.0f).toString()) }

        var rememberedPostCount by remember { mutableStateOf(0) }
        var isDebugMode by remember { mutableStateOf(botPref.getBoolean("is_debug_mode", false)) }
        var isSpamBurstProtectionEnabled by remember { mutableStateOf(botPref.getBoolean("is_spam_burst_protection_enabled", false)) }
        var spamBurstWindowMinutesText by remember { mutableStateOf(botPref.getInt("spam_burst_window_minutes", 3).toString()) }
        var spamBurstYudongThresholdText by remember { mutableStateOf(botPref.getInt("spam_burst_yudong_threshold", 10).toString()) }
                var spamBurstDurationMinutesText by remember { mutableStateOf(botPref.getInt("spam_burst_duration_minutes", 10).toString()) }
        var spamBurstTargetYudong by remember { mutableStateOf(botPref.getBoolean("spam_burst_target_yudong", true)) }
        var spamBurstTargetKkang by remember { mutableStateOf(botPref.getBoolean("spam_burst_target_kkang", true)) }
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

        DisposableEffect(context) {
            val sessionReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.getStringExtra("BOT_ID") == botId) {
                        isRunning = false
                        isAutoLoginInProgress = true
                        shouldOpenWebViewFallback = intent.getBooleanExtra(
                            "REQUIRE_WEBVIEW_FALLBACK",
                            botPref.getBoolean("session_webview_fallback_pending", false)
                        )
                        sessionRecoveryReason = intent.getStringExtra("RECOVERY_REASON")
                            ?: botPref.getString("session_recovery_reason", null)
                    }
                }
            }
            ContextCompat.registerReceiver(context, sessionReceiver, IntentFilter("BOT_SESSION_EXPIRED"), ContextCompat.RECEIVER_NOT_EXPORTED)
            onDispose { context.unregisterReceiver(sessionReceiver) }
        }

        AnimatedContent(
            targetState = currentSubScreen,
            transitionSpec = {
                if (targetState != null && initialState == null) { slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it / 2 } + fadeOut() }
                else if (targetState == null && initialState != null) { slideInHorizontally { -it / 2 } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut() }
                else { fadeIn() togetherWith fadeOut() }
            }, label = "SubScreenAnimation"
        ) { activeSubScreen ->
            LaunchedEffect(activeSubScreen) {
                if (activeSubScreen != "IMAGE") {
                    imageAltExtractUrlText = ""
                    dcconExtractUrlText = ""
                    extractedAltsList = null
                    extractedDcconsList = null
                }
            }
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
                                    "OVERSEAS_IP" -> "해외 IP 필터"
                                    "URL" -> "URL 필터"
                                    "IMAGE" -> "이미지 필터"
                                    "DCCON" -> "디시콘 필터"
                                    "VOICE" -> "보이스 필터"
                                    "SPAM" -> "스팸코드 필터"
                                    "WORD" -> "금지어 필터"
                                    "SPEED" -> "탐색 범위 및 속도 설정"
                                    "GALLERY_REFRESH" -> "갤러리 설정 자동 갱신"
                                    "BLOCK_SETTING" -> "차단 기본 설정"
                                    "BLOCK_EXEMPT_POSTS" -> "차단 예외 글 설정"
                                    "NOTI_SETTING" -> "차단 알림 상세 설정"
                                    else -> "상세 설정"
                                }, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = textColor, modifier = Modifier.weight(1f)
                            )
                        }

                        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            when (activeSubScreen) {
                                "GALLERY_REFRESH" -> {
                                    val galleryRefreshEnabled = isGallerySettingRefreshEnabled
                                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            GallerySwitchRow(
                                                title = "갤러리 설정 자동 갱신",
                                                subtitle = "갤러리 설정을 자동으로 갱신합니다. 갱신 시 켜져 있는 모든 설정이 다시 설정됩니다.",
                                                checked = isGallerySettingRefreshEnabled,
                                                onCheckedChange = { isGallerySettingRefreshEnabled = it; botPref.edit().putBoolean("gallery_setting_refresh_enabled", it).apply() }
                                            )
                                            Divider(color = dividerColor)
                                            Column(modifier = Modifier.alpha(if (galleryRefreshEnabled) 1f else 0.45f)) {
                                                MinuteDropdownRow("갱신 주기", gallerySettingRefreshIntervalMinutes, galleryRefreshIntervalOptions, "refresh_interval", enabled = galleryRefreshEnabled) { minutes ->
                                                    gallerySettingRefreshIntervalMinutes = minutes
                                                    botPref.edit().putInt("gallery_setting_refresh_interval_minutes", minutes).apply()
                                                }
                                                Text("갱신은 사이클 시작 시 이루어지므로 사이클이 길어지면 실제 갱신 주기와 차이가 있을 수 있습니다.", color = subTextColor, fontSize = 12.sp)
                                            }
                                        }
                                    }

                                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 16.dp).alpha(if (galleryRefreshEnabled) 1f else 0.45f)) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Text("IP 제한 갱신", fontWeight = FontWeight.Bold, color = textColor, modifier = Modifier.padding(bottom = 4.dp))
                                            Text("갱신 주기마다 아래 설정한 시간으로 IP 제한이 다시 설정됩니다.", color = subTextColor, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                                            GallerySwitchRow("VPN 제한", null, gallerySettingProxyUse, enabled = galleryRefreshEnabled) { gallerySettingProxyUse = it; botPref.edit().putBoolean("gallery_setting_proxy_use", it).apply() }
                                            if (gallerySettingProxyUse) MinuteDropdownRow("VPN 제한 시간", gallerySettingProxyTimeMinutes, galleryProxyTimeOptions, "proxy_time", enabled = galleryRefreshEnabled) { minutes -> gallerySettingProxyTimeMinutes = minutes; botPref.edit().putInt("gallery_setting_proxy_time_minutes", minutes).apply() }
                                            Divider(color = dividerColor)
                                            GallerySwitchRow("전체 통신사 IP 제한", null, gallerySettingMobileUse, enabled = galleryRefreshEnabled) { gallerySettingMobileUse = it; botPref.edit().putBoolean("gallery_setting_mobile_use", it).apply() }
                                            if (gallerySettingMobileUse) MinuteDropdownRow("전체 통신사 IP 제한 시간", gallerySettingMobileTimeMinutes, galleryMobileTimeOptions, "mobile_time", enabled = galleryRefreshEnabled) { minutes -> gallerySettingMobileTimeMinutes = minutes; botPref.edit().putInt("gallery_setting_mobile_time_minutes", minutes).apply() }
                                            Divider(color = dividerColor)
                                            GallerySwitchRow("특정 통신사 IP 대역 제한", null, gallerySettingMobileIpsUse, enabled = galleryRefreshEnabled) { gallerySettingMobileIpsUse = it; botPref.edit().putBoolean("gallery_setting_mobile_ips_use", it).apply() }
                                            if (gallerySettingMobileIpsUse) MinuteDropdownRow("특정 통신사 IP 제한 시간", gallerySettingMobileIpsTimeMinutes, galleryMobileIpsTimeOptions, "mobile_ips_time", enabled = galleryRefreshEnabled) { minutes -> gallerySettingMobileIpsTimeMinutes = minutes; botPref.edit().putInt("gallery_setting_mobile_ips_time_minutes", minutes).apply() }
                                        }
                                    }

                                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.alpha(if (galleryRefreshEnabled) 1f else 0.45f)) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            GallerySwitchRow("이미지/동영상 첨부 제한", "갱신 주기마다 아래 설정한 시간으로 이미지/동영상 첨부 제한이 다시 설정됩니다.", gallerySettingImageBlockUse, enabled = galleryRefreshEnabled) { gallerySettingImageBlockUse = it; botPref.edit().putBoolean("gallery_setting_image_block_use", it).apply() }
                                            if (gallerySettingImageBlockUse) {
                                                MinuteDropdownRow("첨부 제한 시간", gallerySettingImageBlockTimeMinutes, galleryImageBlockTimeOptions, "img_time", enabled = galleryRefreshEnabled) { minutes -> gallerySettingImageBlockTimeMinutes = minutes; botPref.edit().putInt("gallery_setting_image_block_time_minutes", minutes).apply() }
                                                Divider(color = dividerColor)
                                                GallerySwitchRow("대상: VPN", null, gallerySettingImageBlockProxy, enabled = galleryRefreshEnabled) { gallerySettingImageBlockProxy = it; botPref.edit().putBoolean("gallery_setting_image_block_proxy", it).apply() }
                                                GallerySwitchRow("대상: 통신사 IP", null, gallerySettingImageBlockMobile, enabled = galleryRefreshEnabled) { gallerySettingImageBlockMobile = it; botPref.edit().putBoolean("gallery_setting_image_block_mobile", it).apply() }
                                                GallerySwitchRow("대상: 전체 비회원", null, gallerySettingImageBlockAll, enabled = galleryRefreshEnabled) { gallerySettingImageBlockAll = it; botPref.edit().putBoolean("gallery_setting_image_block_all", it).apply() }
                                            }
                                        }
                                    }
                                }
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
                                                Divider(color = dividerColor)
                                                Row(modifier=Modifier.fillMaxWidth().padding(vertical=4.dp), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) { Text("AI 필터 차단/검토", color=textColor) ; Switch(checked=isNotiAi, onCheckedChange={ isNotiAi=it; botPref.edit().putBoolean("noti_ai", it).apply() }, modifier=Modifier.scale(0.8f)) }
                                            }
                                        }
                                    }
                                }
                                "BLOCK_SETTING" -> {
                                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("처리 방식", fontWeight = FontWeight.Bold, color = textColor)
                                                Box {
                                                    OutlinedButton(onClick = { isBlockActionModeDropdownExpanded = true }) {
                                                        Text(actionModeOptions[blockActionMode] ?: "차단", color = textColor)
                                                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy)
                                                    }
                                                    DropdownMenu(expanded = isBlockActionModeDropdownExpanded, onDismissRequest = { isBlockActionModeDropdownExpanded = false }, modifier = Modifier.background(dialogBgColor)) {
                                                        actionModeOptions.forEach { (mode, label) -> DropdownMenuItem(text = { Text(label, color = textColor) }, onClick = {
                                                            blockActionMode = mode
                                                            isDeleteOnlyMode = mode == "delete"
                                                            saveActionMode(null, mode)
                                                            isBlockActionModeDropdownExpanded = false
                                                        }) }
                                                    }
                                                }
                                            }
                                            if (blockActionMode == "block") {
                                                Divider(color = dividerColor, modifier = Modifier.padding(bottom=8.dp))
                                                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Text("차단 기간", fontWeight = FontWeight.Bold, color = textColor)
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
                                    }
                                    if (blockActionMode == "block") {
                                        ReadOnlyTextCard("차단 사유 (유저에게 표시됨)", blockReasonText, colors) { tempEditText = blockReasonText; editDialogType = "block_reason" }
                                    }
                                }
                                "BLOCK_EXEMPT_POSTS" -> {
                                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Text("차단 예외 글 설정", fontWeight = FontWeight.Bold, color = textColor)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                "필터별 개별 차단 설정과 관계없이, 등록한 글 번호의 게시글과 댓글은 모든 차단 검사에서 제외됩니다.",
                                                color = subTextColor,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                    ReadOnlyTextCard("예외 처리할 글 번호", blockExemptPostNumbersText, colors) { tempEditText = blockExemptPostNumbersText; editDialogType = "block_exempt_post_numbers" }
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
                                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                            Column {
                                                Text("유저 ID/IP 필터", fontWeight = FontWeight.Bold, color = textColor)
                                                Text("ID/IP 기반으로 사용자를 차단합니다.", fontSize = 12.sp, color = subTextColor)
                                            }
                                            Switch(checked = isUserFilterMode, onCheckedChange = { isUserFilterMode = it; botPref.edit().putBoolean("is_user_filter_mode", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                        }
                                    }
                                    Column(modifier = if (!isUserFilterMode) Modifier.alpha(0.4f).pointerInput(Unit) { detectTapGestures { } } else Modifier) {
                                        ReadOnlyTextCard("ID/IP 블랙리스트 (발견 즉시 차단)", userBlacklistText, colors) { tempEditText = userBlacklistText; editDialogType = "user_blacklist" }
                                        ReadOnlyTextCard("ID/IP 화이트리스트 (차단 예외)", userWhitelistText, colors) { tempEditText = userWhitelistText; editDialogType = "user_whitelist" }
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text("개별 차단 설정", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = PastelNavy, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                                        Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                                            Column(modifier = Modifier.padding(16.dp)) {
                                                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Column {
                                                        Text("개별 차단 설정 사용", fontWeight = FontWeight.Bold, color = textColor)
                                                        Text("끄면 기본 차단 설정을 따릅니다.", fontSize = 12.sp, color = subTextColor)
                                                    }
                                                    Switch(checked = userUseCustomAction, onCheckedChange = { userUseCustomAction = it; botPref.edit().putBoolean("user_use_custom_action_config", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                                }
                                            }
                                        }
                                        Column(modifier = if (!userUseCustomAction) Modifier.alpha(0.4f).pointerInput(Unit) { detectTapGestures { } } else Modifier) {
                                            Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                                                Column(modifier = Modifier.padding(16.dp)) {
                                                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                        Text("처리 방식", fontWeight = FontWeight.Bold, color = textColor)
                                                        Box {
                                                            OutlinedButton(onClick = { if (userUseCustomAction) isUserActionModeDropdownExpanded = true }) {
                                                                Text(actionModeOptions[userActionMode] ?: "차단", color = textColor)
                                                                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy)
                                                            }
                                                            DropdownMenu(expanded = isUserActionModeDropdownExpanded, onDismissRequest = { isUserActionModeDropdownExpanded = false }, modifier = Modifier.background(dialogBgColor)) {
                                                                actionModeOptions.forEach { (mode, label) -> DropdownMenuItem(text = { Text(label, color = textColor) }, onClick = { userActionMode = mode; userDeleteOnlyMode = mode == "delete"; saveActionMode("user", mode); isUserActionModeDropdownExpanded = false }) }
                                                            }
                                                        }
                                                    }
                                                    if (userActionMode == "block") {
                                                        Divider(color = dividerColor, modifier = Modifier.padding(bottom = 8.dp))
                                                        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                            Text("차단 기간", fontWeight = FontWeight.Bold, color = textColor)
                                                            Box {
                                                                OutlinedButton(onClick = { if (userUseCustomAction) isUserBlockDurationDropdownExpanded = true }) {
                                                                    Text(blockDurationOptions[userBlockDurationHours] ?: "${userBlockDurationHours}시간", color = textColor)
                                                                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy)
                                                                }
                                                                DropdownMenu(expanded = isUserBlockDurationDropdownExpanded, onDismissRequest = { isUserBlockDurationDropdownExpanded = false }, modifier = Modifier.background(dialogBgColor)) {
                                                                    blockDurationOptions.forEach { (hours, label) -> DropdownMenuItem(text = { Text(label, color = textColor) }, onClick = { userBlockDurationHours = hours; botPref.edit().putInt("user_block_duration_hours", hours).apply(); isUserBlockDurationDropdownExpanded = false }) }
                                                                }
                                                            }
                                                        }
                                                        Divider(color = dividerColor, modifier = Modifier.padding(bottom = 8.dp))
                                                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                            Text("차단 시 글/댓글 함께 삭제", color = textColor)
                                                            Switch(checked = userDeletePostOnBlock, onCheckedChange = { userDeletePostOnBlock = it; botPref.edit().putBoolean("user_delete_post_on_block", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                                        }
                                                    }
                                                }
                                            }
                                            if (userActionMode == "block") {
                                                ReadOnlyTextCard("차단 사유 (유저에게 표시됨)", userBlockReasonText, colors) { tempEditText = userBlockReasonText; editDialogType = "user_block_reason" }
                                            }
                                        }
                                    }
                                }
                                "NICKNAME" -> {
                                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                            Column {
                                                Text("닉네임 필터", fontWeight = FontWeight.Bold, color = textColor)
                                                Text("닉네임 기반으로 사용자를 차단합니다.", fontSize = 12.sp, color = subTextColor)
                                            }
                                            Switch(checked = isNicknameFilterMode, onCheckedChange = { isNicknameFilterMode = it; botPref.edit().putBoolean("is_nickname_filter_mode", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                        }
                                    }
                                    Column(modifier = if (!isNicknameFilterMode) Modifier.alpha(0.4f).pointerInput(Unit) { detectTapGestures { } } else Modifier) {
                                        ReadOnlyTextCard("닉네임 블랙리스트 (발견 즉시 차단)", nicknameBlacklistText, colors) { tempEditText = nicknameBlacklistText; editDialogType = "nickname_blacklist" }
                                        ReadOnlyTextCard("닉네임 화이트리스트 (차단 예외)", nicknameWhitelistText, colors) { tempEditText = nicknameWhitelistText; editDialogType = "nickname_whitelist" }
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text("개별 차단 설정", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = PastelNavy, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                                        Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                                            Column(modifier = Modifier.padding(16.dp)) {
                                                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Column {
                                                        Text("개별 차단 설정 사용", fontWeight = FontWeight.Bold, color = textColor)
                                                        Text("끄면 기본 차단 설정을 따릅니다.", fontSize = 12.sp, color = subTextColor)
                                                    }
                                                    Switch(checked = nicknameUseCustomAction, onCheckedChange = { nicknameUseCustomAction = it; botPref.edit().putBoolean("nickname_use_custom_action_config", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                                }
                                            }
                                        }
                                        Column(modifier = if (!nicknameUseCustomAction) Modifier.alpha(0.4f).pointerInput(Unit) { detectTapGestures { } } else Modifier) {
                                            Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                                                Column(modifier = Modifier.padding(16.dp)) {
                                                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                        Text("처리 방식", fontWeight = FontWeight.Bold, color = textColor)
                                                        Box {
                                                            OutlinedButton(onClick = { if (nicknameUseCustomAction) isNicknameActionModeDropdownExpanded = true }) {
                                                                Text(actionModeOptions[nicknameActionMode] ?: "차단", color = textColor)
                                                                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy)
                                                            }
                                                            DropdownMenu(expanded = isNicknameActionModeDropdownExpanded, onDismissRequest = { isNicknameActionModeDropdownExpanded = false }, modifier = Modifier.background(dialogBgColor)) {
                                                                actionModeOptions.forEach { (mode, label) -> DropdownMenuItem(text = { Text(label, color = textColor) }, onClick = { nicknameActionMode = mode; nicknameDeleteOnlyMode = mode == "delete"; saveActionMode("nickname", mode); isNicknameActionModeDropdownExpanded = false }) }
                                                            }
                                                        }
                                                    }
                                                    if (nicknameActionMode == "block") {
                                                        Divider(color = dividerColor, modifier = Modifier.padding(bottom = 8.dp))
                                                        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                            Text("차단 기간", fontWeight = FontWeight.Bold, color = textColor)
                                                            Box {
                                                                OutlinedButton(onClick = { if (nicknameUseCustomAction) isNicknameBlockDurationDropdownExpanded = true }) {
                                                                    Text(blockDurationOptions[nicknameBlockDurationHours] ?: "${nicknameBlockDurationHours}시간", color = textColor)
                                                                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy)
                                                                }
                                                                DropdownMenu(expanded = isNicknameBlockDurationDropdownExpanded, onDismissRequest = { isNicknameBlockDurationDropdownExpanded = false }, modifier = Modifier.background(dialogBgColor)) {
                                                                    blockDurationOptions.forEach { (hours, label) -> DropdownMenuItem(text = { Text(label, color = textColor) }, onClick = { nicknameBlockDurationHours = hours; botPref.edit().putInt("nickname_block_duration_hours", hours).apply(); isNicknameBlockDurationDropdownExpanded = false }) }
                                                                }
                                                            }
                                                        }
                                                        Divider(color = dividerColor, modifier = Modifier.padding(bottom = 8.dp))
                                                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                            Text("차단 시 글/댓글 함께 삭제", color = textColor)
                                                            Switch(checked = nicknameDeletePostOnBlock, onCheckedChange = { nicknameDeletePostOnBlock = it; botPref.edit().putBoolean("nickname_delete_post_on_block", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                                        }
                                                    }
                                                }
                                            }
                                            if (nicknameActionMode == "block") {
                                                ReadOnlyTextCard("차단 사유 (유저에게 표시됨)", nicknameBlockReasonText, colors) { tempEditText = nicknameBlockReasonText; editDialogType = "nickname_block_reason" }
                                            }
                                        }
                                    }
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
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text("개별 차단 설정", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = PastelNavy, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 12.dp)) { Column(modifier = Modifier.padding(16.dp)) { Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Column { Text("개별 차단 설정 사용", fontWeight = FontWeight.Bold, color = textColor); Text("끄면 기본 차단 설정을 따릅니다.", fontSize = 12.sp, color = subTextColor) }; Switch(checked = yudongUseCustomAction, onCheckedChange = { yudongUseCustomAction = it; botPref.edit().putBoolean("yudong_use_custom_action_config", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray)) } } }
                                    Column(modifier = if (!yudongUseCustomAction) Modifier.alpha(0.4f).pointerInput(Unit) { detectTapGestures { } } else Modifier) {
                                        Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 12.dp)) { Column(modifier = Modifier.padding(16.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("처리 방식", fontWeight = FontWeight.Bold, color = textColor); Box { OutlinedButton(onClick = { if (yudongUseCustomAction) isYudongActionModeDropdownExpanded = true }) { Text(actionModeOptions[yudongActionMode] ?: "차단", color = textColor); Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy) }; DropdownMenu(expanded = isYudongActionModeDropdownExpanded, onDismissRequest = { isYudongActionModeDropdownExpanded = false }, modifier = Modifier.background(dialogBgColor)) { actionModeOptions.forEach { (mode, label) -> DropdownMenuItem(text = { Text(label, color = textColor) }, onClick = { yudongActionMode = mode; yudongDeleteOnlyMode = mode == "delete"; saveActionMode("yudong", mode); isYudongActionModeDropdownExpanded = false }) } } } }
                                            if (yudongActionMode == "block") {
                                                Divider(color = dividerColor, modifier = Modifier.padding(bottom = 8.dp))
                                                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("차단 기간", fontWeight = FontWeight.Bold, color = textColor); Box { OutlinedButton(onClick = { if (yudongUseCustomAction) isYudongBlockDurationDropdownExpanded = true }) { Text(blockDurationOptions[yudongBlockDurationHours] ?: "${yudongBlockDurationHours}시간", color = textColor); Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy) }; DropdownMenu(expanded = isYudongBlockDurationDropdownExpanded, onDismissRequest = { isYudongBlockDurationDropdownExpanded = false }, modifier = Modifier.background(dialogBgColor)) { blockDurationOptions.forEach { (hours, label) -> DropdownMenuItem(text = { Text(label, color = textColor) }, onClick = { yudongBlockDurationHours = hours; botPref.edit().putInt("yudong_block_duration_hours", hours).apply(); isYudongBlockDurationDropdownExpanded = false }) } } } }
                                                Divider(color = dividerColor, modifier = Modifier.padding(bottom = 8.dp))
                                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("차단 시 글/댓글 함께 삭제", color = textColor); Switch(checked = yudongDeletePostOnBlock, onCheckedChange = { yudongDeletePostOnBlock = it; botPref.edit().putBoolean("yudong_delete_post_on_block", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray)) }
                                            }
                                        } }
                                        if (yudongActionMode == "block") { ReadOnlyTextCard("차단 사유 (유저에게 표시됨)", yudongBlockReasonText, colors) { tempEditText = yudongBlockReasonText; editDialogType = "yudong_block_reason" } }
                                    }
                                }
                                "OVERSEAS_IP" -> {
                                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text("해외 IP 필터 사용", fontWeight = FontWeight.Bold, color = textColor)
                                                    Text("DC에 표시되는 IP 앞 두 자리 기준으로 한국 대역이 아니면 차단합니다.", fontSize = 12.sp, color = subTextColor)
                                                }
                                                Switch(checked = isOverseasIpFilterMode, onCheckedChange = { isOverseasIpFilterMode = it; botPref.edit().putBoolean("is_overseas_ip_filter_mode", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                            }
                                            Divider(color = dividerColor)
                                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("해외 IP 게시글 차단", color = textColor)
                                                Switch(checked = isOverseasIpPostBlock, onCheckedChange = { isOverseasIpPostBlock = it; botPref.edit().putBoolean("is_overseas_ip_post_block", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                            }
                                            Divider(color = dividerColor)
                                            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("해외 IP 댓글 차단", color = textColor)
                                                Switch(checked = isOverseasIpCommentBlock, onCheckedChange = { isOverseasIpCommentBlock = it; botPref.edit().putBoolean("is_overseas_ip_comment_block", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                            }
                                        }
                                    }
                                    Text("표시 예: 123.45.x.x처럼 앞 두 자리만 보이는 유동 IP에 적용됩니다. ID가 있는 고닉/반고닉은 이 필터 대상이 아닙니다. 한국 IP 목록은 IP2Location LITE KR 기준입니다.", fontSize = 12.sp, color = subTextColor, modifier = Modifier.padding(horizontal = 4.dp))
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text("개별 차단 설정", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = PastelNavy, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 12.dp)) { Column(modifier = Modifier.padding(16.dp)) { Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Column { Text("개별 차단 설정 사용", fontWeight = FontWeight.Bold, color = textColor); Text("끄면 기본 차단 설정을 따릅니다.", fontSize = 12.sp, color = subTextColor) }; Switch(checked = overseasIpUseCustomAction, onCheckedChange = { overseasIpUseCustomAction = it; botPref.edit().putBoolean("overseas_ip_use_custom_action_config", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray)) } } }
                                    Column(modifier = if (!overseasIpUseCustomAction) Modifier.alpha(0.4f).pointerInput(Unit) { detectTapGestures { } } else Modifier) {
                                        Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 12.dp)) { Column(modifier = Modifier.padding(16.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("처리 방식", fontWeight = FontWeight.Bold, color = textColor); Box { OutlinedButton(onClick = { if (overseasIpUseCustomAction) isOverseasIpActionModeDropdownExpanded = true }) { Text(actionModeOptions[overseasIpActionMode] ?: "차단", color = textColor); Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy) }; DropdownMenu(expanded = isOverseasIpActionModeDropdownExpanded, onDismissRequest = { isOverseasIpActionModeDropdownExpanded = false }, modifier = Modifier.background(dialogBgColor)) { actionModeOptions.forEach { (mode, label) -> DropdownMenuItem(text = { Text(label, color = textColor) }, onClick = { overseasIpActionMode = mode; overseasIpDeleteOnlyMode = mode == "delete"; saveActionMode("overseas_ip", mode); isOverseasIpActionModeDropdownExpanded = false }) } } } }
                                            if (overseasIpActionMode == "block") {
                                                Divider(color = dividerColor, modifier = Modifier.padding(bottom = 8.dp))
                                                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("차단 기간", fontWeight = FontWeight.Bold, color = textColor); Box { OutlinedButton(onClick = { if (overseasIpUseCustomAction) isOverseasIpBlockDurationDropdownExpanded = true }) { Text(blockDurationOptions[overseasIpBlockDurationHours] ?: "${overseasIpBlockDurationHours}시간", color = textColor); Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy) }; DropdownMenu(expanded = isOverseasIpBlockDurationDropdownExpanded, onDismissRequest = { isOverseasIpBlockDurationDropdownExpanded = false }, modifier = Modifier.background(dialogBgColor)) { blockDurationOptions.forEach { (hours, label) -> DropdownMenuItem(text = { Text(label, color = textColor) }, onClick = { overseasIpBlockDurationHours = hours; botPref.edit().putInt("overseas_ip_block_duration_hours", hours).apply(); isOverseasIpBlockDurationDropdownExpanded = false }) } } } }
                                                Divider(color = dividerColor, modifier = Modifier.padding(bottom = 8.dp))
                                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("차단 시 글/댓글 함께 삭제", color = textColor); Switch(checked = overseasIpDeletePostOnBlock, onCheckedChange = { overseasIpDeletePostOnBlock = it; botPref.edit().putBoolean("overseas_ip_delete_post_on_block", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray)) }
                                            }
                                        } }
                                        if (overseasIpActionMode == "block") { ReadOnlyTextCard("차단 사유 (유저에게 표시됨)", overseasIpBlockReasonText, colors) { tempEditText = overseasIpBlockReasonText; editDialogType = "overseas_ip_block_reason" } }
                                    }
                                }
                                "KKANG" -> {
                                    val kkangDetectionModeOptions = linkedMapOf("total" to "글+댓글 수", "separate" to "글 수/댓글 수", "dc_mark" to "신규 고정닉 표시")
                                    fun safeKkangPrefString(key: String, defaultValue: String): String = (botPref.all[key] as? String) ?: defaultValue
                                    fun safeKkangPrefInt(key: String, defaultValue: Int): Int = when (val value = botPref.all[key]) { is Int -> value; is String -> value.toIntOrNull() ?: defaultValue; is Long -> value.toInt(); else -> defaultValue }
                                    var kkangDetectionMode by remember { mutableStateOf(safeKkangPrefString("kkang_detection_mode", "separate").takeIf { it in kkangDetectionModeOptions.keys } ?: "separate") }
                                    var isKkangDetectionModeDropdownExpanded by remember { mutableStateOf(false) }
                                    var kkangTotalMinText by remember { mutableStateOf(safeKkangPrefInt("kkang_total_min", 15).toString()) }
                                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                            Column {
                                                Text("깡계 필터", fontWeight = FontWeight.Bold, color = textColor)
                                                Text("글/댓글 수 미달 유저를 차단합니다.", fontSize = 12.sp, color = subTextColor)
                                            }
                                            Switch(checked = isKkangFilterMode, onCheckedChange = { isKkangFilterMode = it; botPref.edit().putBoolean("is_kkang_filter_mode", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                        }
                                    }
                                    Column(modifier = if (!isKkangFilterMode) Modifier.alpha(0.4f).pointerInput(Unit) { detectTapGestures { } } else Modifier) {
                                        Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp)) {
                                            Column(modifier = Modifier.padding(16.dp)) {
                                                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Text("깡계 판정 방식", fontWeight = FontWeight.Bold, color = textColor)
                                                    Box {
                                                        OutlinedButton(onClick = { if (isKkangFilterMode) isKkangDetectionModeDropdownExpanded = true }) {
                                                            Text(kkangDetectionModeOptions[kkangDetectionMode] ?: "글 수/댓글 수", color = textColor)
                                                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy)
                                                        }
                                                        DropdownMenu(expanded = isKkangDetectionModeDropdownExpanded, onDismissRequest = { isKkangDetectionModeDropdownExpanded = false }, modifier = Modifier.background(dialogBgColor)) {
                                                            kkangDetectionModeOptions.forEach { (mode, label) -> DropdownMenuItem(text = { Text(label, color = textColor) }, onClick = { kkangDetectionMode = mode; botPref.edit().putString("kkang_detection_mode", mode).apply(); isKkangDetectionModeDropdownExpanded = false }) }
                                                        }
                                                    }
                                                }
                                                if (kkangDetectionMode == "total") {
                                                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                        Text("글+댓글 기준 수", fontWeight = FontWeight.Bold, color = textColor)
                                                        OutlinedTextField(value = kkangTotalMinText, onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) { kkangTotalMinText = it; botPref.edit().putInt("kkang_total_min", it.toIntOrNull() ?: 15).apply() } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.width(80.dp), textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor))
                                                    }
                                                    Text("※ 글 수와 댓글 수의 합이 기준 수 미만이면 깡계로 간주합니다.", fontSize = 12.sp, color = subTextColor)
                                                } else if (kkangDetectionMode == "separate") {
                                                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                        Text("작성 게시글 기준 수", fontWeight = FontWeight.Bold, color = textColor)
                                                        OutlinedTextField(value = kkangPostMinText, onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) { kkangPostMinText = it; botPref.edit().putInt("kkang_post_min", it.toIntOrNull() ?: 5).apply() } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.width(80.dp), textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor))
                                                    }
                                                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                        Text("작성 댓글 기준 수", fontWeight = FontWeight.Bold, color = textColor)
                                                        OutlinedTextField(value = kkangCmtMinText, onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) { kkangCmtMinText = it; botPref.edit().putInt("kkang_comment_min", it.toIntOrNull() ?: 10).apply() } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.width(80.dp), textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor))
                                                    }
                                                    Text("※ 위 두 숫자 중 하나라도 미달하면 깡계로 간주합니다.", fontSize = 12.sp, color = subTextColor)
                                                } else {
                                                    Text("디시인사이드의 신규 고정닉 표시를 감지합니다. (설정 방법: 해당 갤러리>관리>갤러리 설정>신규 고정닉 표시)", fontSize = 12.sp, color = subTextColor)
                                                }

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
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text("개별 차단 설정", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = PastelNavy, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                                        Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 12.dp)) { Column(modifier = Modifier.padding(16.dp)) { Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Column { Text("개별 차단 설정 사용", fontWeight = FontWeight.Bold, color = textColor); Text("끄면 기본 차단 설정을 따릅니다.", fontSize = 12.sp, color = subTextColor) }; Switch(checked = kkangUseCustomAction, onCheckedChange = { kkangUseCustomAction = it; botPref.edit().putBoolean("kkang_use_custom_action_config", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray)) } } }
                                        Column(modifier = if (!kkangUseCustomAction) Modifier.alpha(0.4f).pointerInput(Unit) { detectTapGestures { } } else Modifier) {
                                            Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 12.dp)) { Column(modifier = Modifier.padding(16.dp)) {
                                                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("처리 방식", fontWeight = FontWeight.Bold, color = textColor); Box { OutlinedButton(onClick = { if (kkangUseCustomAction) isKkangActionModeDropdownExpanded = true }) { Text(actionModeOptions[kkangActionMode] ?: "차단", color = textColor); Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy) }; DropdownMenu(expanded = isKkangActionModeDropdownExpanded, onDismissRequest = { isKkangActionModeDropdownExpanded = false }, modifier = Modifier.background(dialogBgColor)) { actionModeOptions.forEach { (mode, label) -> DropdownMenuItem(text = { Text(label, color = textColor) }, onClick = { kkangActionMode = mode; kkangDeleteOnlyMode = mode == "delete"; saveActionMode("kkang", mode); isKkangActionModeDropdownExpanded = false }) } } } }
                                                if (kkangActionMode == "block") {
                                                    Divider(color = dividerColor, modifier = Modifier.padding(bottom = 8.dp))
                                                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("차단 기간", fontWeight = FontWeight.Bold, color = textColor); Box { OutlinedButton(onClick = { if (kkangUseCustomAction) isKkangBlockDurationDropdownExpanded = true }) { Text(blockDurationOptions[kkangBlockDurationHours] ?: "${kkangBlockDurationHours}시간", color = textColor); Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy) }; DropdownMenu(expanded = isKkangBlockDurationDropdownExpanded, onDismissRequest = { isKkangBlockDurationDropdownExpanded = false }, modifier = Modifier.background(dialogBgColor)) { blockDurationOptions.forEach { (hours, label) -> DropdownMenuItem(text = { Text(label, color = textColor) }, onClick = { kkangBlockDurationHours = hours; botPref.edit().putInt("kkang_block_duration_hours", hours).apply(); isKkangBlockDurationDropdownExpanded = false }) } } } }
                                                    Divider(color = dividerColor, modifier = Modifier.padding(bottom = 8.dp))
                                                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("차단 시 글/댓글 함께 삭제", color = textColor); Switch(checked = kkangDeletePostOnBlock, onCheckedChange = { kkangDeletePostOnBlock = it; botPref.edit().putBoolean("kkang_delete_post_on_block", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray)) }
                                                }
                                            } }
                                            if (kkangActionMode == "block") { ReadOnlyTextCard("차단 사유 (유저에게 표시됨)", kkangBlockReasonText, colors) { tempEditText = kkangBlockReasonText; editDialogType = "kkang_block_reason" } }
                                        }
                                    }
                                }
                                "URL" -> {
                                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                            Column {
                                                Text("URL 필터", fontWeight = FontWeight.Bold, color = textColor)
                                                Text("허용 목록에 없는 외부 링크를 차단합니다.", fontSize = 12.sp, color = subTextColor)
                                            }
                                            Switch(checked = isUrlFilterMode, onCheckedChange = { isUrlFilterMode = it; botPref.edit().putBoolean("is_url_filter_mode", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                        }
                                    }
                                    Column(modifier = if (!isUrlFilterMode) Modifier.alpha(0.4f).pointerInput(Unit) { detectTapGestures { } } else Modifier) {
                                        Text("여기에 없는 외부 링크는 모두 차단됩니다.", fontSize = 12.sp, color = subTextColor, modifier = Modifier.padding(bottom = 8.dp))
                                        ReadOnlyTextCard("허용할 도메인 (화이트리스트)", urlWhitelistText, colors) { tempEditText = urlWhitelistText; editDialogType = "url_whitelist" }
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text("개별 차단 설정", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = PastelNavy, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                                        Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 12.dp)) { Column(modifier = Modifier.padding(16.dp)) { Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Column { Text("개별 차단 설정 사용", fontWeight = FontWeight.Bold, color = textColor); Text("끄면 기본 차단 설정을 따릅니다.", fontSize = 12.sp, color = subTextColor) }; Switch(checked = urlUseCustomAction, onCheckedChange = { urlUseCustomAction = it; botPref.edit().putBoolean("url_use_custom_action_config", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray)) } } }
                                        Column(modifier = if (!urlUseCustomAction) Modifier.alpha(0.4f).pointerInput(Unit) { detectTapGestures { } } else Modifier) {
                                            Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 12.dp)) { Column(modifier = Modifier.padding(16.dp)) {
                                                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("처리 방식", fontWeight = FontWeight.Bold, color = textColor); Box { OutlinedButton(onClick = { if (urlUseCustomAction) isUrlActionModeDropdownExpanded = true }) { Text(actionModeOptions[urlActionMode] ?: "차단", color = textColor); Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy) }; DropdownMenu(expanded = isUrlActionModeDropdownExpanded, onDismissRequest = { isUrlActionModeDropdownExpanded = false }, modifier = Modifier.background(dialogBgColor)) { actionModeOptions.forEach { (mode, label) -> DropdownMenuItem(text = { Text(label, color = textColor) }, onClick = { urlActionMode = mode; urlDeleteOnlyMode = mode == "delete"; saveActionMode("url", mode); isUrlActionModeDropdownExpanded = false }) } } } }
                                                if (urlActionMode == "block") {
                                                    Divider(color = dividerColor, modifier = Modifier.padding(bottom = 8.dp))
                                                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("차단 기간", fontWeight = FontWeight.Bold, color = textColor); Box { OutlinedButton(onClick = { if (urlUseCustomAction) isUrlBlockDurationDropdownExpanded = true }) { Text(blockDurationOptions[urlBlockDurationHours] ?: "${urlBlockDurationHours}시간", color = textColor); Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy) }; DropdownMenu(expanded = isUrlBlockDurationDropdownExpanded, onDismissRequest = { isUrlBlockDurationDropdownExpanded = false }, modifier = Modifier.background(dialogBgColor)) { blockDurationOptions.forEach { (hours, label) -> DropdownMenuItem(text = { Text(label, color = textColor) }, onClick = { urlBlockDurationHours = hours; botPref.edit().putInt("url_block_duration_hours", hours).apply(); isUrlBlockDurationDropdownExpanded = false }) } } } }
                                                    Divider(color = dividerColor, modifier = Modifier.padding(bottom = 8.dp))
                                                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("차단 시 글/댓글 함께 삭제", color = textColor); Switch(checked = urlDeletePostOnBlock, onCheckedChange = { urlDeletePostOnBlock = it; botPref.edit().putBoolean("url_delete_post_on_block", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray)) }
                                                }
                                            } }
                                            if (urlActionMode == "block") { ReadOnlyTextCard("차단 사유 (유저에게 표시됨)", urlBlockReasonText, colors) { tempEditText = urlBlockReasonText; editDialogType = "url_block_reason" } }
                                        }
                                    }
                                }
                                "IMAGE" -> {
                                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                            Column {
                                                Text("이미지 필터", fontWeight = FontWeight.Bold, color = textColor)
                                                Text("alt값 기반으로 이미지를 차단합니다.", fontSize = 12.sp, color = subTextColor)
                                            }
                                            Switch(checked = isImageFilterMode, onCheckedChange = { isImageFilterMode = it; botPref.edit().putBoolean("is_image_filter_mode", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                        }
                                    }
                                    Column(modifier = if (!isImageFilterMode) Modifier.alpha(0.4f).pointerInput(Unit) { detectTapGestures { } } else Modifier) {
                                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("일치도 기준 (%)", fontWeight = FontWeight.Bold, color = textColor)
                                            OutlinedTextField(value = imageFilterThresholdText, onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) { imageFilterThresholdText = it; botPref.edit().putInt("image_filter_threshold", it.toIntOrNull() ?: 80).apply() } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.width(80.dp), textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor))
                                        }
                                    }
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = cardColor),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                imageAltBlacklistDraftText = DcconFilter.normalizeImageAltBlacklistText(imageAltBlacklistText)
                                                imageAltBlacklistAddText = ""
                                                imageAltExtractUrlText = ""
                                                isImageAltAddInputVisible = false
                                                selectedImageAlts = emptySet()
                                                imageAltDeleteConfirmAlts = null
                                                isImageAltBlacklistDialogOpen = true
                                            }
                                    ) {
                                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("차단할 이미지 alt값 목록", fontWeight = FontWeight.Bold, color = textColor)
                                                Text("${imageAltRefsFromBlacklistText(imageAltBlacklistText).size}개 등록됨 · 이미지 미리보기로 관리", fontSize = 12.sp, color = subTextColor)
                                            }
                                            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = PastelNavy)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text("개별 차단 설정", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = PastelNavy, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 12.dp)) { Column(modifier = Modifier.padding(16.dp)) { Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Column { Text("개별 차단 설정 사용", fontWeight = FontWeight.Bold, color = textColor); Text("끄면 기본 차단 설정을 따릅니다.", fontSize = 12.sp, color = subTextColor) }; Switch(checked = imageUseCustomAction, onCheckedChange = { imageUseCustomAction = it; botPref.edit().putBoolean("image_use_custom_action_config", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray)) } } }
                                    Column(modifier = if (!imageUseCustomAction) Modifier.alpha(0.4f).pointerInput(Unit) { detectTapGestures { } } else Modifier) {
                                        Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 12.dp)) { Column(modifier = Modifier.padding(16.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("처리 방식", fontWeight = FontWeight.Bold, color = textColor); Box { OutlinedButton(onClick = { if (imageUseCustomAction) isImageActionModeDropdownExpanded = true }) { Text(actionModeOptions[imageActionMode] ?: "차단", color = textColor); Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy) }; DropdownMenu(expanded = isImageActionModeDropdownExpanded, onDismissRequest = { isImageActionModeDropdownExpanded = false }, modifier = Modifier.background(dialogBgColor)) { actionModeOptions.forEach { (mode, label) -> DropdownMenuItem(text = { Text(label, color = textColor) }, onClick = { imageActionMode = mode; imageDeleteOnlyMode = mode == "delete"; saveActionMode("image", mode); isImageActionModeDropdownExpanded = false }) } } } }
                                            if (imageActionMode == "block") {
                                                Divider(color = dividerColor, modifier = Modifier.padding(bottom = 8.dp))
                                                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("차단 기간", fontWeight = FontWeight.Bold, color = textColor); Box { OutlinedButton(onClick = { if (imageUseCustomAction) isImageBlockDurationDropdownExpanded = true }) { Text(blockDurationOptions[imageBlockDurationHours] ?: "${imageBlockDurationHours}시간", color = textColor); Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy) }; DropdownMenu(expanded = isImageBlockDurationDropdownExpanded, onDismissRequest = { isImageBlockDurationDropdownExpanded = false }, modifier = Modifier.background(dialogBgColor)) { blockDurationOptions.forEach { (hours, label) -> DropdownMenuItem(text = { Text(label, color = textColor) }, onClick = { imageBlockDurationHours = hours; botPref.edit().putInt("image_block_duration_hours", hours).apply(); isImageBlockDurationDropdownExpanded = false }) } } } }
                                                Divider(color = dividerColor, modifier = Modifier.padding(bottom = 8.dp))
                                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("차단 시 글/댓글 함께 삭제", color = textColor); Switch(checked = imageDeletePostOnBlock, onCheckedChange = { imageDeletePostOnBlock = it; botPref.edit().putBoolean("image_delete_post_on_block", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray)) }
                                            }
                                        } }
                                        if (imageActionMode == "block") { ReadOnlyTextCard("차단 사유 (유저에게 표시됨)", imageBlockReasonText, colors) { tempEditText = imageBlockReasonText; editDialogType = "image_block_reason" } }
                                    }
                                    }
                                }
                                "DCCON" -> {
                                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("디시콘 필터", fontWeight = FontWeight.Bold, color = textColor)
                                                Text("특정 디시콘 URL/토큰을 감지해 글·댓글을 차단합니다.", fontSize = 12.sp, color = subTextColor)
                                            }
                                            Switch(checked = isDcconFilterMode, onCheckedChange = { isDcconFilterMode = it; botPref.edit().putBoolean("is_dccon_filter_mode", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                        }
                                    }
                                    Text("디시콘 차단 목록", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = PastelNavy, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                                    Column(modifier = if (!isDcconFilterMode) Modifier.alpha(0.4f).pointerInput(Unit) { detectTapGestures { } } else Modifier) {
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = cardColor),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    dcconBlacklistDraftText = dcconBlacklistText
                                                    dcconBlacklistAddText = ""
                                                    isDcconAddInputVisible = false
                                                    isDcconExtractInputVisible = false
                                                    dcconExtractUrlText = ""
                                                    extractedDcconsList = null
                                                    selectedDcconTokens = emptySet()
                                                    selectedDcconGroupName = null
                                                    dcconDeleteConfirmTokens = null
                                                    isDcconBlacklistDialogOpen = true
                                                }
                                        ) {
                                            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                val previewItems = DcconFilter.previewItemsFromBlacklistText(dcconBlacklistText)
                                                Row(horizontalArrangement = Arrangement.spacedBy((-12).dp), modifier = Modifier.widthIn(max = 124.dp)) {
                                                    previewItems.take(3).forEach { item ->
                                                        DcconPreviewImage(item.token, modifier = Modifier.size(44.dp))
                                                    }
                                                }
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text("차단된 디시콘 목록", fontWeight = FontWeight.Bold, color = textColor)
                                                    Text("${previewItems.size}개 등록됨 · 목록에서 미리보기", fontSize = 12.sp, color = subTextColor)
                                                    if (previewItems.isNotEmpty()) Text(previewItems.take(2).joinToString(" · ") { it.packageName }, fontSize = 11.sp, color = subTextColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = PastelNavy)
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text("개별 차단 설정", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = PastelNavy, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                                        Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 12.dp)) { Column(modifier = Modifier.padding(16.dp)) { Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Column { Text("개별 차단 설정 사용", fontWeight = FontWeight.Bold, color = textColor); Text("끄면 기본 차단 설정을 따릅니다.", fontSize = 12.sp, color = subTextColor) }; Switch(checked = dcconUseCustomAction, onCheckedChange = { dcconUseCustomAction = it; botPref.edit().putBoolean("dccon_use_custom_action_config", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray)) } } }
                                        Column(modifier = if (!dcconUseCustomAction) Modifier.alpha(0.4f).pointerInput(Unit) { detectTapGestures { } } else Modifier) {
                                            Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 12.dp)) { Column(modifier = Modifier.padding(16.dp)) {
                                                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("처리 방식", fontWeight = FontWeight.Bold, color = textColor); Box { OutlinedButton(onClick = { if (dcconUseCustomAction) isDcconActionModeDropdownExpanded = true }) { Text(actionModeOptions[dcconActionMode] ?: "차단", color = textColor); Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy) }; DropdownMenu(expanded = isDcconActionModeDropdownExpanded, onDismissRequest = { isDcconActionModeDropdownExpanded = false }, modifier = Modifier.background(dialogBgColor)) { actionModeOptions.forEach { (mode, label) -> DropdownMenuItem(text = { Text(label, color = textColor) }, onClick = { dcconActionMode = mode; dcconDeleteOnlyMode = mode == "delete"; saveActionMode("dccon", mode); isDcconActionModeDropdownExpanded = false }) } } } }
                                                if (dcconActionMode == "block") {
                                                    Divider(color = dividerColor, modifier = Modifier.padding(bottom = 8.dp))
                                                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("차단 기간", fontWeight = FontWeight.Bold, color = textColor); Box { OutlinedButton(onClick = { if (dcconUseCustomAction) isDcconBlockDurationDropdownExpanded = true }) { Text(blockDurationOptions[dcconBlockDurationHours] ?: "${dcconBlockDurationHours}시간", color = textColor); Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy) }; DropdownMenu(expanded = isDcconBlockDurationDropdownExpanded, onDismissRequest = { isDcconBlockDurationDropdownExpanded = false }, modifier = Modifier.background(dialogBgColor)) { blockDurationOptions.forEach { (hours, label) -> DropdownMenuItem(text = { Text(label, color = textColor) }, onClick = { dcconBlockDurationHours = hours; botPref.edit().putInt("dccon_block_duration_hours", hours).apply(); isDcconBlockDurationDropdownExpanded = false }) } } } }
                                                    Divider(color = dividerColor, modifier = Modifier.padding(bottom = 8.dp))
                                                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("차단 시 글/댓글 함께 삭제", color = textColor); Switch(checked = dcconDeletePostOnBlock, onCheckedChange = { dcconDeletePostOnBlock = it; botPref.edit().putBoolean("dccon_delete_post_on_block", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray)) }
                                                }
                                            } }
                                            if (dcconActionMode == "block") { ReadOnlyTextCard("차단 사유 (유저에게 표시됨)", dcconBlockReasonText, colors) { tempEditText = dcconBlockReasonText; editDialogType = "dccon_block_reason" } }
                                        }
                                    }
                                }

                                "VOICE" -> {
                                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                            Column {
                                                Text("보이스 필터", fontWeight = FontWeight.Bold, color = textColor)
                                                Text("특정 보이스 ID의 리플을 차단합니다.", fontSize = 12.sp, color = subTextColor)
                                            }
                                            Switch(checked = isVoiceFilterMode, onCheckedChange = { isVoiceFilterMode = it; botPref.edit().putBoolean("is_voice_filter_mode", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                        }
                                    }
                                    Column(modifier = if (!isVoiceFilterMode) Modifier.alpha(0.4f).pointerInput(Unit) { detectTapGestures { } } else Modifier) {
                                    ReadOnlyTextCard("차단할 보이스 ID (블랙리스트)", voiceBlacklistText, colors) { tempEditText = voiceBlacklistText; editDialogType = "voice_blacklist" }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text("개별 차단 설정", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = PastelNavy, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 12.dp)) { Column(modifier = Modifier.padding(16.dp)) { Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Column { Text("개별 차단 설정 사용", fontWeight = FontWeight.Bold, color = textColor); Text("끄면 기본 차단 설정을 따릅니다.", fontSize = 12.sp, color = subTextColor) }; Switch(checked = voiceUseCustomAction, onCheckedChange = { voiceUseCustomAction = it; botPref.edit().putBoolean("voice_use_custom_action_config", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray)) } } }
                                    Column(modifier = if (!voiceUseCustomAction) Modifier.alpha(0.4f).pointerInput(Unit) { detectTapGestures { } } else Modifier) {
                                        Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 12.dp)) { Column(modifier = Modifier.padding(16.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("처리 방식", fontWeight = FontWeight.Bold, color = textColor); Box { OutlinedButton(onClick = { if (voiceUseCustomAction) isVoiceActionModeDropdownExpanded = true }) { Text(actionModeOptions[voiceActionMode] ?: "차단", color = textColor); Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy) }; DropdownMenu(expanded = isVoiceActionModeDropdownExpanded, onDismissRequest = { isVoiceActionModeDropdownExpanded = false }, modifier = Modifier.background(dialogBgColor)) { actionModeOptions.forEach { (mode, label) -> DropdownMenuItem(text = { Text(label, color = textColor) }, onClick = { voiceActionMode = mode; voiceDeleteOnlyMode = mode == "delete"; saveActionMode("voice", mode); isVoiceActionModeDropdownExpanded = false }) } } } }
                                            if (voiceActionMode == "block") {
                                                Divider(color = dividerColor, modifier = Modifier.padding(bottom = 8.dp))
                                                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("차단 기간", fontWeight = FontWeight.Bold, color = textColor); Box { OutlinedButton(onClick = { if (voiceUseCustomAction) isVoiceBlockDurationDropdownExpanded = true }) { Text(blockDurationOptions[voiceBlockDurationHours] ?: "${voiceBlockDurationHours}시간", color = textColor); Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy) }; DropdownMenu(expanded = isVoiceBlockDurationDropdownExpanded, onDismissRequest = { isVoiceBlockDurationDropdownExpanded = false }, modifier = Modifier.background(dialogBgColor)) { blockDurationOptions.forEach { (hours, label) -> DropdownMenuItem(text = { Text(label, color = textColor) }, onClick = { voiceBlockDurationHours = hours; botPref.edit().putInt("voice_block_duration_hours", hours).apply(); isVoiceBlockDurationDropdownExpanded = false }) } } } }
                                                Divider(color = dividerColor, modifier = Modifier.padding(bottom = 8.dp))
                                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("차단 시 글/댓글 함께 삭제", color = textColor); Switch(checked = voiceDeletePostOnBlock, onCheckedChange = { voiceDeletePostOnBlock = it; botPref.edit().putBoolean("voice_delete_post_on_block", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray)) }
                                            }
                                        } }
                                        if (voiceActionMode == "block") { ReadOnlyTextCard("차단 사유 (유저에게 표시됨)", voiceBlockReasonText, colors) { tempEditText = voiceBlockReasonText; editDialogType = "voice_block_reason" } }
                                    }
                                    } // end Column
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
                                "AI" -> {
                                    AiFilterSettingsPanel(botId = botId)
                                }
                                "SPAM" -> {
                                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                            Column {
                                                Text("스팸코드 필터", fontWeight = FontWeight.Bold, color = textColor)
                                                Text("대문자+숫자 조합 스팸코드를 차단합니다.", fontSize = 12.sp, color = subTextColor)
                                            }
                                            Switch(checked = isSpamCodeFilterMode, onCheckedChange = { isSpamCodeFilterMode = it; botPref.edit().putBoolean("is_spam_code_filter_mode", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                        }
                                    }
                                    Column(modifier = if (!isSpamCodeFilterMode) Modifier.alpha(0.4f).pointerInput(Unit) { detectTapGestures { } } else Modifier) {
                                        Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("감지할 스팸코드 자릿수", fontWeight = FontWeight.Bold, color = textColor)
                                                OutlinedTextField(value = spamCodeLengthText, onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) { spamCodeLengthText = it; botPref.edit().putInt("spam_code_length", it.toIntOrNull() ?: 6).apply() } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.width(80.dp), textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor))
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text("개별 차단 설정", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = PastelNavy, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                                        Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 12.dp)) { Column(modifier = Modifier.padding(16.dp)) { Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Column { Text("개별 차단 설정 사용", fontWeight = FontWeight.Bold, color = textColor); Text("끄면 기본 차단 설정을 따릅니다.", fontSize = 12.sp, color = subTextColor) }; Switch(checked = spamUseCustomAction, onCheckedChange = { spamUseCustomAction = it; botPref.edit().putBoolean("spam_use_custom_action_config", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray)) } } }
                                        Column(modifier = if (!spamUseCustomAction) Modifier.alpha(0.4f).pointerInput(Unit) { detectTapGestures { } } else Modifier) {
                                            Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 12.dp)) { Column(modifier = Modifier.padding(16.dp)) {
                                                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("처리 방식", fontWeight = FontWeight.Bold, color = textColor); Box { OutlinedButton(onClick = { if (spamUseCustomAction) isSpamActionModeDropdownExpanded = true }) { Text(actionModeOptions[spamActionMode] ?: "차단", color = textColor); Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy) }; DropdownMenu(expanded = isSpamActionModeDropdownExpanded, onDismissRequest = { isSpamActionModeDropdownExpanded = false }, modifier = Modifier.background(dialogBgColor)) { actionModeOptions.forEach { (mode, label) -> DropdownMenuItem(text = { Text(label, color = textColor) }, onClick = { spamActionMode = mode; spamDeleteOnlyMode = mode == "delete"; saveActionMode("spam", mode); isSpamActionModeDropdownExpanded = false }) } } } }
                                                if (spamActionMode == "block") {
                                                    Divider(color = dividerColor, modifier = Modifier.padding(bottom = 8.dp))
                                                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("차단 기간", fontWeight = FontWeight.Bold, color = textColor); Box { OutlinedButton(onClick = { if (spamUseCustomAction) isSpamBlockDurationDropdownExpanded = true }) { Text(blockDurationOptions[spamBlockDurationHours] ?: "${spamBlockDurationHours}시간", color = textColor); Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy) }; DropdownMenu(expanded = isSpamBlockDurationDropdownExpanded, onDismissRequest = { isSpamBlockDurationDropdownExpanded = false }, modifier = Modifier.background(dialogBgColor)) { blockDurationOptions.forEach { (hours, label) -> DropdownMenuItem(text = { Text(label, color = textColor) }, onClick = { spamBlockDurationHours = hours; botPref.edit().putInt("spam_block_duration_hours", hours).apply(); isSpamBlockDurationDropdownExpanded = false }) } } } }
                                                    Divider(color = dividerColor, modifier = Modifier.padding(bottom = 8.dp))
                                                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("차단 시 글/댓글 함께 삭제", color = textColor); Switch(checked = spamDeletePostOnBlock, onCheckedChange = { spamDeletePostOnBlock = it; botPref.edit().putBoolean("spam_delete_post_on_block", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray)) }
                                                }
                                            } }
                                            if (spamActionMode == "block") { ReadOnlyTextCard("차단 사유 (유저에게 표시됨)", spamBlockReasonText, colors) { tempEditText = spamBlockReasonText; editDialogType = "spam_block_reason" } }
                                        }
                                    }
                                }
                                "WORD" -> {
                                    ReadOnlyTextCard("일반 금지어 (완전히 일치하는 경우 차단)", normalWordsText, colors) { tempEditText = normalWordsText; editDialogType = "normal" }
                                    ReadOnlyTextCard("우회 금지어 (글자 사이 특수문자 등 무시)", bypassWordsText, colors) { tempEditText = bypassWordsText; editDialogType = "bypass" }

                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text("금지어 적용 대상", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = PastelNavy, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Text("둘 다 끄면 모든 작성자에게 금지어 필터가 적용됩니다.", color = subTextColor, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("유동에게만 적용", color = textColor, fontWeight = FontWeight.Bold)
                                                Switch(checked = keywordApplyYudongOnly, onCheckedChange = { keywordApplyYudongOnly = it; botPref.edit().putBoolean("keyword_apply_yudong_only", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                            }
                                            Divider(color = dividerColor)
                                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text("깡계에게만 적용", color = textColor, fontWeight = FontWeight.Bold)
                                                    Text(kkangCriteriaGuideText(), fontSize = 12.sp, color = subTextColor)
                                                }
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Switch(checked = keywordApplyKkangOnly, onCheckedChange = { keywordApplyKkangOnly = it; botPref.edit().putBoolean("keyword_apply_kkang_only", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text("개별 차단 설정", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = PastelNavy, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                Column {
                                                    Text("개별 차단 설정 사용", fontWeight = FontWeight.Bold, color = textColor)
                                                    Text("끄면 기본 차단 설정을 따릅니다.", fontSize = 12.sp, color = subTextColor)
                                                }
                                                Switch(checked = keywordUseCustomAction, onCheckedChange = { keywordUseCustomAction = it; botPref.edit().putBoolean("keyword_use_custom_action_config", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                            }
                                        }
                                    }
                                    Column(modifier = if (!keywordUseCustomAction) Modifier.alpha(0.4f).pointerInput(Unit) { detectTapGestures { } } else Modifier) {
                                        Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                                            Column(modifier = Modifier.padding(16.dp)) {
                                                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Text("처리 방식", fontWeight = FontWeight.Bold, color = textColor)
                                                    Box {
                                                        OutlinedButton(onClick = { if (keywordUseCustomAction) isKeywordActionModeDropdownExpanded = true }) {
                                                            Text(actionModeOptions[keywordActionMode] ?: "차단", color = textColor)
                                                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy)
                                                        }
                                                        DropdownMenu(expanded = isKeywordActionModeDropdownExpanded, onDismissRequest = { isKeywordActionModeDropdownExpanded = false }, modifier = Modifier.background(dialogBgColor)) {
                                                            actionModeOptions.forEach { (mode, label) -> DropdownMenuItem(text = { Text(label, color = textColor) }, onClick = {
                                                                keywordActionMode = mode
                                                                keywordDeleteOnlyMode = mode == "delete"
                                                                saveActionMode("keyword", mode)
                                                                isKeywordActionModeDropdownExpanded = false
                                                            }) }
                                                        }
                                                    }
                                                }
                                                if (keywordActionMode == "block") {
                                                    Divider(color = dividerColor, modifier = Modifier.padding(bottom = 8.dp))
                                                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                        Text("차단 기간", fontWeight = FontWeight.Bold, color = textColor)
                                                        Box {
                                                            OutlinedButton(onClick = { if (keywordUseCustomAction) isKeywordBlockDurationDropdownExpanded = true }) {
                                                                Text(blockDurationOptions[keywordBlockDurationHours] ?: "${keywordBlockDurationHours}시간", color = textColor)
                                                                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = PastelNavy)
                                                            }
                                                            DropdownMenu(expanded = isKeywordBlockDurationDropdownExpanded, onDismissRequest = { isKeywordBlockDurationDropdownExpanded = false }, modifier = Modifier.background(dialogBgColor)) {
                                                                blockDurationOptions.forEach { (hours, label) -> DropdownMenuItem(text = { Text(label, color = textColor) }, onClick = { keywordBlockDurationHours = hours; botPref.edit().putInt("keyword_block_duration_hours", hours).apply(); isKeywordBlockDurationDropdownExpanded = false }) }
                                                            }
                                                        }
                                                    }
                                                    Divider(color = dividerColor, modifier = Modifier.padding(bottom = 8.dp))
                                                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                        Text("차단 시 글/댓글 함께 삭제", color = textColor)
                                                        Switch(checked = keywordDeletePostOnBlock, onCheckedChange = { keywordDeletePostOnBlock = it; botPref.edit().putBoolean("keyword_delete_post_on_block", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                                    }
                                                }
                                            }
                                        }
                                        if (keywordActionMode == "block") {
                                            ReadOnlyTextCard("차단 사유 (유저에게 표시됨)", keywordBlockReasonText, colors) { tempEditText = keywordBlockReasonText; editDialogType = "keyword_block_reason" }
                                        }
                                    }
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
                                "SPAM_BURST" -> {
                                    Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text("도배 방지", fontWeight = FontWeight.Bold, color = textColor)
                                                    Text("최근 글들의 작성 간격을 기준으로 도배를 감지하고, 기준 글 이후의 유동/깡계 신규 글을 일정 시간 삭제합니다.", fontSize = 12.sp, color = subTextColor)
                                                }
                                                Switch(checked = isSpamBurstProtectionEnabled, onCheckedChange = {
                                                    isSpamBurstProtectionEnabled = it
                                                    botPref.edit().putBoolean("is_spam_burst_protection_enabled", it).apply()
                                                }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                            }
                                        }
                                    }
                                    Column(modifier = if (!isSpamBurstProtectionEnabled) Modifier.alpha(0.4f).pointerInput(Unit) { detectTapGestures { } } else Modifier) {
                                        Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = RoundedCornerShape(12.dp)) {
                                            Column(modifier = Modifier.padding(16.dp)) {
                                                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Text("최근 글 샘플 수", fontWeight = FontWeight.Bold, color = textColor)
                                                    OutlinedTextField(value = spamBurstWindowMinutesText, onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) { spamBurstWindowMinutesText = it; botPref.edit().putInt("spam_burst_window_minutes", it.toIntOrNull() ?: 3).apply() } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.width(80.dp), textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor))
                                                }
                                                Divider(color = dividerColor, modifier = Modifier.padding(bottom = 8.dp))
                                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Text("평균 간격 기준(초)", fontWeight = FontWeight.Bold, color = textColor)
                                                    OutlinedTextField(value = spamBurstYudongThresholdText, onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) { spamBurstYudongThresholdText = it; botPref.edit().putInt("spam_burst_yudong_threshold", it.toIntOrNull() ?: 10).apply() } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.width(80.dp), textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor))
                                                }
                                                Divider(color = dividerColor, modifier = Modifier.padding(vertical = 8.dp))
                                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Text("삭제 지속 시간(분)", fontWeight = FontWeight.Bold, color = textColor)
                                                    OutlinedTextField(value = spamBurstDurationMinutesText, onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) { spamBurstDurationMinutesText = it; botPref.edit().putInt("spam_burst_duration_minutes", it.toIntOrNull() ?: 10).apply() } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.width(80.dp), textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor))
                                                }
                                                Divider(color = dividerColor, modifier = Modifier.padding(vertical = 8.dp))
                                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Text("유동 대상", color = textColor)
                                                    Switch(checked = spamBurstTargetYudong, onCheckedChange = { spamBurstTargetYudong = it; botPref.edit().putBoolean("spam_burst_target_yudong", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                                }
                                                Divider(color = dividerColor)
                                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text("깡계 대상", color = textColor)
                                                        Text(kkangCriteriaGuideText(), fontSize = 12.sp, color = subTextColor)
                                                    }
                                                    Spacer(modifier = Modifier.width(12.dp))
                                                    Switch(checked = spamBurstTargetKkang, onCheckedChange = { spamBurstTargetKkang = it; botPref.edit().putBoolean("spam_burst_target_kkang", it).apply() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PastelNavy, uncheckedThumbColor = if(isDarkMode) Color.LightGray else Color.White, uncheckedTrackColor = if(isDarkMode) Color(0xFF555555) else Color.LightGray))
                                                }
                                            }
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
                        FilledTonalButton(
                            onClick = {
                                val safeName = botName.replace(Regex("[\\/:*?\"<>|]"), "_").trim().ifBlank { "bot" }
                                exportLauncher.launch("${safeName}_settings_$ARMBANDBOT_APP_VERSION.json")
                            },
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (isDarkMode) Color(0xFF2A3542) else Color(0xFFEAF1FF),
                                contentColor = PastelNavy
                            )
                        ) {
                            Icon(Icons.Filled.FileUpload, contentDescription = "내보내기", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("내보내기", fontWeight = FontWeight.SemiBold)
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

                    Box(modifier = Modifier.fillMaxSize()) {
                        if (selectedTabIndex == 0) {
                            Column(modifier = Modifier.fillMaxSize().verticalScroll(settingsScrollState).padding(horizontal = 16.dp, vertical = 16.dp)) {
                                Text("기본 탐색 설정", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = PastelNavy, modifier = Modifier.padding(start=4.dp, bottom=4.dp))
                                ModernSettingItem("관리할 갤러리 및 검색 모드", if (targetUrlsText.isBlank()) "대상 없음" else "대상 설정됨", Icons.Filled.List, colors) { currentSubScreen = "TARGET" }
                                ModernSettingItem("탐색 속도 및 범위", "페이지 수 및 딜레이 설정", Icons.Filled.Build, colors) { currentSubScreen = "SPEED" }
                                ModernSettingItem("갤러리 설정 자동 갱신", "VPN/통신사/첨부 제한 시간 유지", Icons.Filled.Refresh, colors, isGallerySettingRefreshEnabled, { isGallerySettingRefreshEnabled = it; botPref.edit().putBoolean("gallery_setting_refresh_enabled", it).apply() }) { currentSubScreen = "GALLERY_REFRESH" }

                                Spacer(modifier = Modifier.height(24.dp))
                                Text("차단 후속 동작", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = PastelNavy, modifier = Modifier.padding(start=4.dp, bottom=4.dp))
                                ModernSettingItem("차단 기본 설정", "차단 시간, 사유, 글 삭제 여부", Icons.Filled.Settings, colors) { currentSubScreen = "BLOCK_SETTING" }
                                ModernSettingItem("차단 예외 글 설정", "특정 글과 해당 댓글 전체를 차단 예외 처리", Icons.Filled.Forum, colors) { currentSubScreen = "BLOCK_EXEMPT_POSTS" }
                                ModernSettingItem("차단 알림 상세 설정", "어떤 경우에 알림을 받을지 설정", Icons.Filled.Notifications, colors) { currentSubScreen = "NOTI_SETTING" }

                                Spacer(modifier = Modifier.height(24.dp))
                                Text("게시물 차단 필터", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = PastelNavy, modifier = Modifier.padding(start=4.dp, bottom=4.dp))
                                ModernSettingItem("금지어 필터", "금지어 기반 차단 설정", Icons.Filled.Create, colors) { currentSubScreen = "WORD" }
                                ModernSettingItem("유저 ID/IP 필터", "식별코드/IP 기반 차단 설정", Icons.Filled.Person, colors, isUserFilterMode, { isUserFilterMode = it; botPref.edit().putBoolean("is_user_filter_mode", it).apply() }) { currentSubScreen = "USER" }
                                ModernSettingItem("닉네임 필터", "닉네임 기반 차단 설정", Icons.Filled.Face, colors, isNicknameFilterMode, { isNicknameFilterMode = it; botPref.edit().putBoolean("is_nickname_filter_mode", it).apply() }) { currentSubScreen = "NICKNAME" }
                                ModernSettingItem("유동 필터", "비로그인 유저 이용 제한", Icons.Filled.Lock, colors) { currentSubScreen = "YUDONG" }
                                ModernSettingItem("해외 IP 필터", "한국 할당 대역이 아닌 유동 IP 차단", Icons.Filled.Public, colors, isOverseasIpFilterMode, { isOverseasIpFilterMode = it; botPref.edit().putBoolean("is_overseas_ip_filter_mode", it).apply() }) { currentSubScreen = "OVERSEAS_IP" }
                                ModernSettingItem("깡계 필터", "글/댓글 수 미달 유저 차단", Icons.Filled.Info, colors, isKkangFilterMode, { isKkangFilterMode = it; botPref.edit().putBoolean("is_kkang_filter_mode", it).apply() }) { currentSubScreen = "KKANG" }
                                ModernSettingItem("도배 방지", "유동/깡계 급증 시 일정 시간 신규 글 삭제", Icons.Filled.Warning, colors, isSpamBurstProtectionEnabled, { isSpamBurstProtectionEnabled = it; botPref.edit().putBoolean("is_spam_burst_protection_enabled", it).apply() }) { currentSubScreen = "SPAM_BURST" }

                                Spacer(modifier = Modifier.height(24.dp))
                                Text("고급 미디어 필터", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = PastelNavy, modifier = Modifier.padding(start=4.dp, bottom=4.dp))
                                ModernSettingItem("URL 필터", "외부 링크 차단", Icons.Filled.Share, colors, isUrlFilterMode, { isUrlFilterMode = it; botPref.edit().putBoolean("is_url_filter_mode", it).apply() }) { currentSubScreen = "URL" }
                                ModernSettingItem("이미지 필터", "alt값 기반 이미지 차단", Icons.Filled.Search, colors, isImageFilterMode, { isImageFilterMode = it; botPref.edit().putBoolean("is_image_filter_mode", it).apply() }) { currentSubScreen = "IMAGE" }
                                ModernSettingItem("디시콘 필터", "디시콘 URL/토큰 기반 차단", Icons.Filled.Image, colors, isDcconFilterMode, { isDcconFilterMode = it; botPref.edit().putBoolean("is_dccon_filter_mode", it).apply() }) { currentSubScreen = "DCCON" }
                                ModernSettingItem("보이스 필터", "보이스 리플 차단", Icons.Filled.Call, colors, isVoiceFilterMode, { isVoiceFilterMode = it; botPref.edit().putBoolean("is_voice_filter_mode", it).apply() }) { currentSubScreen = "VOICE" }
                                if (isAiFilterVisible) {
                                    ModernSettingItem("AI 필터", "게시글 2차 AI 검토", Icons.Filled.AutoAwesome, colors, isAiFilterMode, { isAiFilterMode = it; botPref.edit().putBoolean("is_ai_filter_mode", it).apply() }) { currentSubScreen = "AI" }
                                }
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
                                        Button(onClick = { showConfirmDialog = "logout" }, colors = ButtonDefaults.buttonColors(containerColor = if(isDarkMode) Color(0xFF333333) else Color(0xFFEEEEEE), contentColor = textColor), modifier = Modifier.fillMaxWidth()) { Text("계정 로그아웃") }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Button(onClick = { showConfirmDialog = "delete" }, colors = ButtonDefaults.buttonColors(containerColor = if(isDarkMode) Color(0xFF3E2723) else Color(0xFFFFEBEE), contentColor = warningRed), modifier = Modifier.fillMaxWidth()) { Text("봇 삭제") }
                                    }
                                }
                                Spacer(modifier = Modifier.height(40.dp))
                            }
                        }

                        else if (selectedTabIndex == 1) {
                            val filteredLogs by remember(logMessages.size, selectedLogFilters.size) {
                                derivedStateOf {
                                    if (selectedLogFilters.containsAll(logFilterKeys)) logMessages.toList()
                                    else logMessages.filter { entry ->
                                        when (entry.category) {
                                            BotLogCategory.CYCLE -> "CYCLE" in selectedLogFilters
                                            BotLogCategory.BLOCK -> "BLOCK" in selectedLogFilters
                                            BotLogCategory.DEBUG -> "DEBUG" in selectedLogFilters
                                            BotLogCategory.AI -> "AI" in selectedLogFilters
                                            BotLogCategory.HEALTH -> "HEALTH" in selectedLogFilters
                                            BotLogCategory.SESSION -> "SESSION" in selectedLogFilters
                                            BotLogCategory.ERROR -> "ERROR" in selectedLogFilters
                                            else -> selectedLogFilters.containsAll(logFilterKeys)
                                        }
                                    }
                                }
                            }

                            val isAtBottom by remember { derivedStateOf { val info = logListState.layoutInfo.visibleItemsInfo; if (info.isEmpty()) true else info.last().index >= logListState.layoutInfo.totalItemsCount - 8 } }
                            LaunchedEffect(filteredLogs.size) { if (isAtBottom && filteredLogs.isNotEmpty()) logListState.scrollToItem(filteredLogs.size - 1) }

                                val exportLogLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
                                if (uri == null) return@rememberLauncherForActivityResult
                                runCatching {
                                    val content = logMessages.joinToString("\n") { it.raw }
                                    context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(content) }
                                }.onSuccess {
                                    Toast.makeText(context, "로그 파일을 저장했습니다.", Toast.LENGTH_SHORT).show()
                                }.onFailure {
                                    Toast.makeText(context, it.message ?: "로그 파일 저장에 실패했습니다.", Toast.LENGTH_LONG).show()
                                }
                            }
                            val exportDebugLogLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
                                if (uri == null) return@rememberLauncherForActivityResult
                                runCatching {
                                    fun sanitizeDebugValue(key: String, value: Any?): String {
                                        val lower = key.lowercase(Locale.getDefault())
                                        return when {
                                            lower.contains("pw") || lower.contains("password") -> "[REDACTED_PASSWORD]"
                                            lower.contains("api_key") || lower.contains("apikey") || lower.contains("token") || lower.contains("secret") -> "[REDACTED_SECRET]"
                                            lower.contains("cookie") || lower.contains("session") -> "[REDACTED_SESSION]"
                                            lower.contains("id") && lower.startsWith("auto_login_") -> "[REDACTED_LOGIN_ID]"
                                            else -> value?.toString() ?: "null"
                                        }
                                    }

                                    val debugLogs = logMessages
                                        .joinToString("\n") { line ->
                                            line.raw
                                                .replace(Regex("""(?i)(api[_-]?key\s*[=:]\s*)([^\s]+)"""), "$1[REDACTED_SECRET]")
                                                .replace(Regex("""(?i)(authorization\s*[:=]\s*bearer\s+)([^\s]+)"""), "$1[REDACTED_SECRET]")
                                                .replace(Regex("""(?i)(cookie\s*[=:]\s*)(.+)$"""), "$1[REDACTED_SESSION]")
                                        }
                                    val settingsDump = buildString {
                                        appendLine("[bot_id]")
                                        appendLine(botId)
                                        appendLine()
                                        appendLine("[bot_name]")
                                        appendLine(botName)
                                        appendLine()
                                        appendLine("[bot_prefs]")
                                        botPref.all.toSortedMap(compareBy<String> { it }).forEach { (key, value) ->
                                            appendLine("$key=${sanitizeDebugValue(key, value)}")
                                        }
                                    }
                                    val content = buildString {
                                        appendLine("[debug_logs]")
                                        appendLine(debugLogs)
                                        appendLine()
                                        appendLine("[settings_dump]")
                                        append(settingsDump)
                                    }
                                    context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(content) }
                                }.onSuccess {
                                    Toast.makeText(context, "디버그 로그 파일을 저장했습니다.", Toast.LENGTH_SHORT).show()
                                }.onFailure {
                                    Toast.makeText(context, it.message ?: "디버그 로그 저장에 실패했습니다.", Toast.LENGTH_LONG).show()
                                }
                            }

                            val logActionIconColor = if (isDarkMode) Color.White else PastelNavy
                            Scaffold(
                                containerColor = bgColor,
                                bottomBar = {
                                    Surface(color = if (isDarkMode) Color(0xFF1A1A1A) else cardColor) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceEvenly,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(62.dp).clip(RoundedCornerShape(12.dp)).clickable { coroutineScope.launch { if (filteredLogs.isNotEmpty()) logListState.scrollToItem(0) } }.padding(vertical = 2.dp)) {
                                                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "맨 위로", tint = logActionIconColor, modifier = Modifier.size(29.dp))
                                                Text("맨 위로", color = logActionIconColor, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1)
                                            }
                                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(62.dp).clip(RoundedCornerShape(12.dp)).clickable { coroutineScope.launch { if (filteredLogs.isNotEmpty()) logListState.scrollToItem(filteredLogs.size - 1) } }.padding(vertical = 2.dp)) {
                                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "맨 아래로", tint = logActionIconColor, modifier = Modifier.size(29.dp))
                                                Text("맨 아래로", color = logActionIconColor, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1)
                                            }
                                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(62.dp).clip(RoundedCornerShape(12.dp)).clickable { exportLogLauncher.launch("완장봇_${botName}_활동로그.txt") }.padding(vertical = 2.dp)) {
                                                Icon(Icons.Filled.FileUpload, contentDescription = "내보내기", tint = logActionIconColor, modifier = Modifier.size(28.dp))
                                                Text("내보내기", color = logActionIconColor, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1)
                                            }
                                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(62.dp).clip(RoundedCornerShape(12.dp)).clickable { exportDebugLogLauncher.launch("완장봇_${botName}_디버그로그.txt") }.padding(vertical = 2.dp)) {
                                                Icon(Icons.Filled.BugReport, contentDescription = "디버그", tint = logActionIconColor, modifier = Modifier.size(28.dp))
                                                Text("디버그", color = logActionIconColor, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1)
                                            }
                                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(62.dp).clip(RoundedCornerShape(12.dp)).clickable {
                                                logMessages.clear()
                                                try {
                                                    val logFile = File(File(context.filesDir, "bot_logs"), "log_$botId.txt")
                                                    if (logFile.exists()) logFile.delete()
                                                } catch (_: Exception) {
                                                }
                                            }.padding(vertical = 2.dp)) {
                                                Icon(Icons.Filled.Delete, contentDescription = "로그 삭제", tint = logActionIconColor, modifier = Modifier.size(28.dp))
                                                Text("로그 삭제", color = logActionIconColor, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            ) { logInnerPadding ->
                                Column(modifier = Modifier.fillMaxSize().padding(logInnerPadding)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    fun toggleLogFilter(key: String) {
                                        if (key == "ALL") {
                                            if (selectedLogFilters.containsAll(logFilterKeys)) selectedLogFilters.clear()
                                            else { selectedLogFilters.clear(); selectedLogFilters.addAll(logFilterKeys) }
                                        } else {
                                            if (selectedLogFilters.contains(key)) selectedLogFilters.remove(key) else selectedLogFilters.add(key)
                                        }
                                        persistLogFilters()
                                    }
                                    FilterChip(selected = selectedLogFilters.containsAll(logFilterKeys), onClick = { toggleLogFilter("ALL") }, label = { Text("전체", color = textColor) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = PastelNavy, selectedLabelColor = Color.White))
                                    FilterChip(selected = "CYCLE" in selectedLogFilters, onClick = { toggleLogFilter("CYCLE") }, label = { Text("탐색", color = textColor) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = PastelNavy, selectedLabelColor = Color.White))
                                    FilterChip(selected = "BLOCK" in selectedLogFilters, onClick = { toggleLogFilter("BLOCK") }, label = { Text("처리 내역", color = textColor) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = warningRed, selectedLabelColor = Color.White))
                                    FilterChip(selected = "DEBUG" in selectedLogFilters, onClick = { toggleLogFilter("DEBUG") }, label = { Text("디버그", color = textColor) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFFFB300), selectedLabelColor = Color.White))
                                    FilterChip(selected = "AI" in selectedLogFilters, onClick = { toggleLogFilter("AI") }, label = { Text("AI", color = textColor) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF00897B), selectedLabelColor = Color.White))
                                    FilterChip(selected = "HEALTH" in selectedLogFilters, onClick = { toggleLogFilter("HEALTH") }, label = { Text("헬스", color = textColor) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF546E7A), selectedLabelColor = Color.White))
                                    FilterChip(selected = "SESSION" in selectedLogFilters, onClick = { toggleLogFilter("SESSION") }, label = { Text("세션/복구", color = textColor) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF6A1B9A), selectedLabelColor = Color.White))
                                    FilterChip(selected = "ERROR" in selectedLogFilters, onClick = { toggleLogFilter("ERROR") }, label = { Text("오류", color = textColor) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFD32F2F), selectedLabelColor = Color.White))
                                }

                                Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).background(DarkTerminal, RoundedCornerShape(12.dp)).padding(12.dp)) {
                                    SelectionContainer {
                                        LazyColumn(state = logListState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
                                            items(filteredLogs.size) { index ->
                                                val entry = filteredLogs[index]
                                                val logTextColor = when (entry.category) {
                                                    BotLogCategory.BLOCK -> Color(0xFFFF5252)
                                                    BotLogCategory.ERROR -> Color(0xFFFF6E6E)
                                                    BotLogCategory.DEBUG -> Color(0xFFFFD740)
                                                    BotLogCategory.AI -> Color(0xFF4DB6AC)
                                                    BotLogCategory.HEALTH -> Color(0xFFB0BEC5)
                                                    BotLogCategory.SESSION -> Color(0xFFCE93D8)
                                                    BotLogCategory.CYCLE -> Color(0xFF69F0AE)
                                                    BotLogCategory.SYSTEM -> Color(0xFF90CAF9)
                                                    else -> Color(0xFFE0E0E0)
                                                }
                                                Text(text = entry.raw, color = logTextColor, fontSize = 13.sp)
                                                Spacer(modifier = Modifier.height(3.dp))
                                            }
                                        }
                                    }
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
            val isSingleLine = editDialogType == "bot_name" || editDialogType == "block_reason" || editDialogType == "ai_block_reason" || editDialogType == "keyword_block_reason" || editDialogType == "user_block_reason" || editDialogType == "nickname_block_reason" || editDialogType == "url_block_reason" || editDialogType == "voice_block_reason" || editDialogType == "image_block_reason" || editDialogType == "dccon_block_reason" || editDialogType == "spam_block_reason" || editDialogType == "yudong_block_reason" || editDialogType == "kkang_block_reason" || editDialogType == "overseas_ip_block_reason"
            val title = when(editDialogType) {
                "bot_name" -> "봇 이름 수정"; "block_reason" -> "차단 사유 설정"; "ai_block_reason" -> "AI 필터 차단 사유 설정"; "keyword_block_reason" -> "금지어 필터 차단 사유 설정"; "user_block_reason" -> "유저 필터 차단 사유 설정"; "nickname_block_reason" -> "닉네임 필터 차단 사유 설정"; "url_block_reason" -> "URL 필터 차단 사유 설정"; "voice_block_reason" -> "보이스 필터 차단 사유 설정"; "image_block_reason" -> "이미지 필터 차단 사유 설정"; "dccon_block_reason" -> "디시콘 필터 차단 사유 설정"; "spam_block_reason" -> "스팸코드 필터 차단 사유 설정"; "yudong_block_reason" -> "유동 필터 차단 사유 설정"; "kkang_block_reason" -> "깡계 필터 차단 사유 설정"; "overseas_ip_block_reason" -> "해외 IP 필터 차단 사유 설정"; "block_exempt_post_numbers" -> "차단 예외 글 번호 설정"; "normal" -> "일반 금지어 설정"; "bypass" -> "우회 금지어 설정"; "search" -> "검색어 설정"; "url" -> "관리할 갤러리 URL 설정"; "url_whitelist" -> "허용할 URL 도메인 설정"; "user_blacklist" -> "차단할 유저 ID/IP 설정"; "user_whitelist" -> "보호할 유저 ID/IP 설정"; "nickname_blacklist" -> "차단할 닉네임 설정"; "nickname_whitelist" -> "보호할 닉네임 설정"; "image_alt_blacklist" -> "차단할 이미지 alt값 설정"; "dccon_blacklist" -> "차단할 디시콘 URL/토큰 설정"; "voice_blacklist" -> "차단할 보이스 ID 설정"; else -> ""
            }
            val placeholderMsg = when(editDialogType) {
                "bot_name" -> "새로운 봇 이름을 입력하세요"
                "block_reason" -> "예: 커뮤니티 규칙 위반"
                "ai_block_reason" -> "예: AI 필터 위반"
                "keyword_block_reason" -> "예: 금지어 사용"
                "user_block_reason" -> "예: 유저 필터 위반"
                "nickname_block_reason" -> "예: 닉네임 필터 위반"
                "url_block_reason" -> "예: URL 필터 위반"
                "voice_block_reason" -> "예: 보이스 필터 위반"
                "image_block_reason" -> "예: 이미지 필터 위반"
                "dccon_block_reason" -> "예: 디시콘 필터 위반"
                "spam_block_reason" -> "예: 스팸코드 필터 위반"
                "yudong_block_reason" -> "예: 유동 필터 위반"
                "kkang_block_reason" -> "예: 깡계 필터 위반"
                "overseas_ip_block_reason" -> "예: 해외 IP 작성 제한"
                "block_exempt_post_numbers" -> "줄바꿈으로 글 번호를 입력합니다. (# ← 뒷부분은 무시됨)\n[예시]\n12345 #신문고\n67890"
                "url" -> "줄바꿈으로 구분합니다. (# ← 뒷부분은 무시됨)\n[예시]\nhttps://gall.dcinside.com/..."
                "user_blacklist", "user_whitelist" -> "줄바꿈으로 구분합니다. (# ← 뒷부분은 무시됨)\n[예시]\ngonick1234 #김고닉\n123.456 #박유동"
                "nickname_blacklist", "nickname_whitelist" -> "줄바꿈으로 구분합니다. (# ← 뒷부분은 무시됨)\n[예시]\n김고닉 #호감고닉\n김분탕 #분탕고닉 등"
                "image_alt_blacklist" -> "줄바꿈으로 구분합니다. (# ← 뒷부분은 무시됨)\n[예시]\n759b8005c4... #광고1"
                "dccon_blacklist" -> "디시콘 URL 또는 dccon.php?no= 토큰을 줄바꿈으로 입력합니다. 저장 시 토큰으로 정규화됩니다.\n[예시]\nhttps://dcimg5.dcinside.com/dccon.php?no=... #차단 디시콘"
                "voice_blacklist" -> "39a4c023b... #어그로보플"
                "ai_filter_provider_custom" -> "사용할 AI 제공자 표시 이름을 입력하세요.\n예: 회사 내부 OpenAI 호환 / 기타 API"
                "ai_filter_endpoint" -> "선택한 AI 서비스의 endpoint를 입력하세요.\n예: LM Studio 에뮬레이터: http://10.0.2.2:1234/v1/chat/completions\n예: 실제 폰 같은 Wi-Fi: http://PC_LAN_IP:1234/v1/chat/completions\n예: 셀룰러/Tailscale: http://PC_TAILSCALE_IP:1234/v1/chat/completions\n주의: 실제 폰에서 127.0.0.1은 PC가 아니라 휴대폰 자신입니다.\n예: Groq는 https://api.groq.com/openai/v1/chat/completions\nGemini direct는 비워두면 기본 경로를 사용합니다."
                "ai_filter_api_key" -> "선택한 AI 서비스의 API 키를 입력하세요.\nLM Studio는 보통 비워둬도 됩니다.\n예: Gemini key 또는 Groq API key"
                "ai_filter_model" -> "사용할 모델명을 입력하세요.\n예: gemini-2.5-flash / llama-3.3-70b-versatile"
                "ai_filter_user_prompt" -> "AI가 어떤 글/댓글을 차단해야 하는지 구체적으로 설명하세요.\n예: 두바이 쫀득 쿠키와 관련 있는 글이나 댓글만 차단해줘. 그 외에는 절대로 차단하지 마."
                "ai_filter_batch_max_posts" -> "한 번의 AI 배치 요청에 포함할 최대 게시글 수를 숫자로 입력하세요.\n예: 3"
                "ai_filter_batch_max_wait_sec" -> "배치를 보내기 전 최대 대기 시간을 초 단위 숫자로 입력하세요.\n예: 60"
                "ai_filter_batch_max_weight" -> "배치 누적 용량 상한을 숫자로 입력하세요.\n큰 글은 이 값을 넘으면 단독 검사로 전환됩니다.\n예: 20000"
                else -> "줄바꿈으로 구분합니다. (# ← 뒷부분은 무시됨)\n[예시]\n사과 #금지어1"
            }

            AlertDialog(
                containerColor = dialogBgColor, titleContentColor = textColor, textContentColor = textColor,
                onDismissRequest = { editDialogType = null }, title = { Text(title, fontWeight = FontWeight.Bold) },
                text = { OutlinedTextField(value = tempEditText, onValueChange = { tempEditText = it }, placeholder = { Text(placeholderMsg) }, singleLine = isSingleLine, modifier = if (isSingleLine) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().height(250.dp), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor)) },
                confirmButton = { Button(onClick = {
                    when(editDialogType) {
                        "bot_name" -> { botName = tempEditText; botPref.edit().putString("bot_name", tempEditText).apply() }
                        "block_reason" -> { blockReasonText = tempEditText; botPref.edit().putString("block_reason_text", tempEditText).apply() }
                        "keyword_block_reason" -> { keywordBlockReasonText = tempEditText; botPref.edit().putString("keyword_block_reason_text", tempEditText).apply() }
                        "user_block_reason" -> { userBlockReasonText = tempEditText; botPref.edit().putString("user_block_reason_text", tempEditText).apply() }
                        "nickname_block_reason" -> { nicknameBlockReasonText = tempEditText; botPref.edit().putString("nickname_block_reason_text", tempEditText).apply() }
                        "url_block_reason" -> { urlBlockReasonText = tempEditText; botPref.edit().putString("url_block_reason_text", tempEditText).apply() }
                        "voice_block_reason" -> { voiceBlockReasonText = tempEditText; botPref.edit().putString("voice_block_reason_text", tempEditText).apply() }
                        "image_block_reason" -> { imageBlockReasonText = tempEditText; botPref.edit().putString("image_block_reason_text", tempEditText).apply() }
                        "dccon_block_reason" -> { dcconBlockReasonText = tempEditText; botPref.edit().putString("dccon_block_reason_text", tempEditText).apply() }
                        "spam_block_reason" -> { spamBlockReasonText = tempEditText; botPref.edit().putString("spam_block_reason_text", tempEditText).apply() }
                        "yudong_block_reason" -> { yudongBlockReasonText = tempEditText; botPref.edit().putString("yudong_block_reason_text", tempEditText).apply() }
                        "kkang_block_reason" -> { kkangBlockReasonText = tempEditText; botPref.edit().putString("kkang_block_reason_text", tempEditText).apply() }
                        "overseas_ip_block_reason" -> { overseasIpBlockReasonText = tempEditText; botPref.edit().putString("overseas_ip_block_reason_text", tempEditText).apply() }
                        "normal" -> { normalWordsText = tempEditText.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"); persistMultilineText("normal", tempEditText) }
                        "bypass" -> { bypassWordsText = tempEditText.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"); persistMultilineText("bypass", tempEditText) }
                        "search" -> { searchWordsText = tempEditText.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"); persistMultilineText("search_keywords", tempEditText) }
                        "url" -> { targetUrlsText = tempEditText; botPref.edit().putString("target_urls", tempEditText).apply() }
                        "url_whitelist" -> { urlWhitelistText = tempEditText; botPref.edit().putStringSet("url_whitelist", tempEditText.split("\n").map{it.trim()}.filter{it.isNotEmpty()}.toSet()).apply() }
                        "block_exempt_post_numbers" -> { blockExemptPostNumbersText = tempEditText; botPref.edit().putStringSet("block_exempt_post_numbers", tempEditText.split("\n").map{it.substringBefore("#").trim()}.filter{it.isNotEmpty()}.toSet()).apply() }
                        "user_blacklist" -> { userBlacklistText = tempEditText; botPref.edit().putStringSet("user_blacklist", tempEditText.split("\n").map{it.trim()}.filter{it.isNotEmpty()}.toSet()).apply() }
                        "user_whitelist" -> { userWhitelistText = tempEditText; botPref.edit().putStringSet("user_whitelist", tempEditText.split("\n").map{it.trim()}.filter{it.isNotEmpty()}.toSet()).apply() }
                        "nickname_blacklist" -> { nicknameBlacklistText = tempEditText; botPref.edit().putStringSet("nickname_blacklist", tempEditText.split("\n").map{it.trim()}.filter{it.isNotEmpty()}.toSet()).apply() }
                        "nickname_whitelist" -> { nicknameWhitelistText = tempEditText; botPref.edit().putStringSet("nickname_whitelist", tempEditText.split("\n").map{it.trim()}.filter{it.isNotEmpty()}.toSet()).apply() }
                        "image_alt_blacklist" -> {
                            val normalized = DcconFilter.normalizeImageAltBlacklistText(tempEditText)
                            imageAltBlacklistText = normalized
                            botPref.edit().putStringSet("image_alt_blacklist", normalized.split("\n").map{it.trim()}.filter{it.isNotEmpty()}.toSet()).apply()
                        }
                        "dccon_blacklist" -> {
                            val normalized = DcconFilter.normalizeBlacklistText(tempEditText)
                            dcconBlacklistText = normalized
                            botPref.edit().putStringSet("dccon_blacklist", normalized.split("\n").map{it.trim()}.filter{it.isNotEmpty()}.toSet()).apply()
                        }
                        "voice_blacklist" -> { voiceBlacklistText = tempEditText; botPref.edit().putStringSet("voice_blacklist", tempEditText.split("\n").map{it.trim()}.filter{it.isNotEmpty()}.toSet()).apply() }
                    }
                    editDialogType = null; Toast.makeText(context, "저장되었습니다.", Toast.LENGTH_SHORT).show()
                }, colors = ButtonDefaults.buttonColors(containerColor = PastelNavy)) { Text("저장", color = Color.White) } },
                dismissButton = { TextButton(onClick = { editDialogType = null }) { Text("취소", color = subTextColor) } }
            )
        }

        if (isImageAltBlacklistDialogOpen) {
            val draftRefs = imageAltRefsFromBlacklistText(imageAltBlacklistDraftText)
            val visibleAlts = draftRefs.map { it.alt }
            val allSelected = visibleAlts.isNotEmpty() && visibleAlts.all { it in selectedImageAlts }
            AlertDialog(
                containerColor = dialogBgColor, titleContentColor = textColor, textContentColor = textColor,
                onDismissRequest = { isImageAltBlacklistDialogOpen = false },
                title = { Text("차단할 이미지 alt값 (${draftRefs.size}개)", fontWeight = FontWeight.Bold) },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (isImageAltAddInputVisible) {
                            OutlinedTextField(
                                value = imageAltBlacklistAddText,
                                onValueChange = { imageAltBlacklistAddText = it },
                                placeholder = { Text("alt값 또는 alt #이미지URL\n여러 개는 줄바꿈으로 입력", fontSize = 12.sp) },
                                minLines = 3,
                                maxLines = 5,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor)
                            )
                            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { imageAltBlacklistAddText = ""; isImageAltAddInputVisible = false }) { Text("취소", color = subTextColor) }
                                Button(onClick = {
                                    imageAltBlacklistDraftText = DcconFilter.addImageAltBlacklistEntries(imageAltBlacklistDraftText, imageAltBlacklistAddText)
                                    imageAltBlacklistAddText = ""
                                    isImageAltAddInputVisible = false
                                }, colors = ButtonDefaults.buttonColors(containerColor = PastelNavy)) { Text("추가", color = Color.White) }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        } else {
                            OutlinedButton(onClick = { isImageAltAddInputVisible = true }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Filled.Add, contentDescription = null, tint = PastelNavy)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("직접 추가", color = PastelNavy)
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        Text("게시글 주소로 찾아 넣기", color = textColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("본문 이미지 alt만 추출해 현재 차단 목록 초안에 추가합니다.", color = subTextColor, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = imageAltExtractUrlText,
                                onValueChange = { imageAltExtractUrlText = it },
                                placeholder = { Text("게시글 주소", fontSize = 12.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f).height(50.dp),
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (imageAltExtractUrlText.isNotBlank() && !isExtractingImageAlts) {
                                        isExtractingImageAlts = true
                                        coroutineScope.launch(Dispatchers.IO) {
                                            try {
                                                val doc = fetchPostDocument(imageAltExtractUrlText)
                                                val refs = DcconFilter.extractImageAltImageRefs(doc.html())
                                                val entries = refs.joinToString("\n") { ref -> if (ref.imageUrl != null) "${ref.alt} #${ref.imageUrl}" else ref.alt }
                                                withContext(Dispatchers.Main) {
                                                    if (refs.isEmpty()) {
                                                        Toast.makeText(context, "본문 이미지 alt를 찾지 못했습니다.", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        extractedAltsList = refs
                                                        Toast.makeText(context, "이미지 alt ${refs.size}개를 찾았습니다. 필요한 항목만 추가하세요.", Toast.LENGTH_SHORT).show()
                                                    }
                                                    imageAltExtractUrlText = ""
                                                    isExtractingImageAlts = false
                                                }
                                            } catch (e: Exception) {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(context, "이미지 alt 추출 오류: ${e.message}", Toast.LENGTH_LONG).show()
                                                    isExtractingImageAlts = false
                                                }
                                            }
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = PastelNavy),
                                modifier = Modifier.height(50.dp)
                            ) { Text(if (isExtractingImageAlts) "..." else "추출", color = Color.White, fontSize = 12.sp) }
                        }
                        Spacer(modifier = Modifier.height(12.dp))

                        if (visibleAlts.isNotEmpty()) {
                            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = allSelected, onCheckedChange = { checked ->
                                    selectedImageAlts = if (checked) selectedImageAlts + visibleAlts else selectedImageAlts - visibleAlts.toSet()
                                })
                                Text("전체 선택", color = textColor, modifier = Modifier.weight(1f))
                                if (selectedImageAlts.isNotEmpty()) Text("${selectedImageAlts.size}개 선택", color = subTextColor, fontSize = 12.sp)
                            }
                        }

                        if (draftRefs.isEmpty()) {
                            Text("등록된 이미지 alt값이 없습니다.", color = subTextColor, modifier = Modifier.padding(vertical = 24.dp))
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                                items(draftRefs.size) { index ->
                                    val ref = draftRefs[index]
                                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = ref.alt in selectedImageAlts, onCheckedChange = { checked ->
                                            selectedImageAlts = if (checked) selectedImageAlts + ref.alt else selectedImageAlts - ref.alt
                                        })
                                        ImageAltPreviewImage(ref.imageUrl, modifier = Modifier.size(64.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(ref.alt, color = textColor, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Text(if (ref.imageUrl != null) "미리보기 있음" else "기존 텍스트 항목", color = subTextColor, fontSize = 11.sp)
                                        }
                                        IconButton(onClick = { imageAltDeleteConfirmAlts = setOf(ref.alt) }) {
                                            Icon(Icons.Filled.Delete, contentDescription = "삭제", tint = warningRed)
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (selectedImageAlts.isNotEmpty()) {
                            OutlinedButton(onClick = { imageAltDeleteConfirmAlts = selectedImageAlts }) { Text("선택 삭제", color = warningRed) }
                        }
                        Button(onClick = {
                            val normalized = DcconFilter.normalizeImageAltBlacklistText(imageAltBlacklistDraftText)
                            imageAltBlacklistText = normalized
                            botPref.edit().putStringSet("image_alt_blacklist", normalized.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()).apply()
                            isImageAltBlacklistDialogOpen = false
                        }, colors = ButtonDefaults.buttonColors(containerColor = PastelNavy)) { Text("저장", color = Color.White) }
                    }
                },
                dismissButton = { TextButton(onClick = { isImageAltBlacklistDialogOpen = false }) { Text("취소", color = subTextColor) } }
            )
        }

        val pendingImageAltDeletes = imageAltDeleteConfirmAlts
        if (pendingImageAltDeletes != null) {
            AlertDialog(
                containerColor = dialogBgColor, titleContentColor = textColor, textContentColor = textColor,
                onDismissRequest = { imageAltDeleteConfirmAlts = null },
                title = { Text(if (pendingImageAltDeletes.size == 1) "이미지 alt 삭제" else "이미지 alt 선택 삭제", fontWeight = FontWeight.Bold) },
                text = { Text(if (pendingImageAltDeletes.size == 1) "이 이미지 alt값을 차단 목록에서 제거할까요?" else "선택한 이미지 alt값 ${pendingImageAltDeletes.size}개를 차단 목록에서 제거할까요?") },
                confirmButton = { Button(onClick = {
                    imageAltBlacklistDraftText = DcconFilter.removeImageAltBlacklistEntries(imageAltBlacklistDraftText, pendingImageAltDeletes)
                    selectedImageAlts = selectedImageAlts - pendingImageAltDeletes
                    imageAltDeleteConfirmAlts = null
                }, colors = ButtonDefaults.buttonColors(containerColor = warningRed)) { Text("삭제", color = Color.White) } },
                dismissButton = { TextButton(onClick = { imageAltDeleteConfirmAlts = null }) { Text("취소", color = subTextColor) } }
            )
        }

        val alts = extractedAltsList
        if (alts != null) {
            AlertDialog(
                containerColor = dialogBgColor, titleContentColor = textColor, textContentColor = textColor,
                onDismissRequest = { extractedAltsList = null }, title = { Text("이미지 alt 값 (${alts.size}개)", fontWeight = FontWeight.Bold) },
                text = {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 390.dp)) {
                        items(alts.size) { index ->
                            val ref = alts[index]
                            val entry = if (ref.imageUrl != null) "${ref.alt} #${ref.imageUrl}" else ref.alt
                            val activeImageAltText = if (isImageAltBlacklistDialogOpen) imageAltBlacklistDraftText else imageAltBlacklistText
                            val isBlocked = DcconFilter.imageAltMatchValue(entry) in DcconFilter.imageAltBlacklistValues(activeImageAltText)
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                ImageAltPreviewImage(ref.imageUrl, modifier = Modifier.size(68.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(ref.alt, color = textColor, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(if (ref.imageUrl != null) "이미지 ${index + 1}" else "미리보기 없음", color = subTextColor, fontSize = 11.sp)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                if (isBlocked) {
                                    AssistChip(onClick = {}, label = { Text("추가됨", fontSize = 12.sp) }, enabled = false)
                                } else {
                                    Button(
                                        onClick = {
                                            if (isImageAltBlacklistDialogOpen) {
                                                imageAltBlacklistDraftText = DcconFilter.addImageAltBlacklistEntries(imageAltBlacklistDraftText, entry)
                                                Toast.makeText(context, "이미지 alt를 차단 목록 초안에 추가했습니다.", Toast.LENGTH_SHORT).show()
                                            } else {
                                                val merged = DcconFilter.addImageAltBlacklistEntries(imageAltBlacklistText, entry)
                                                imageAltBlacklistText = merged
                                                botPref.edit().putStringSet("image_alt_blacklist", merged.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()).apply()
                                                Toast.makeText(context, "이미지 alt를 차단 목록에 추가했습니다.", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                        modifier = Modifier.height(38.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = PastelNavy)
                                    ) { Text("추가", fontSize = 12.sp, color = Color.White) }
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Button(onClick = { copyToClipboard(context, ref.alt, "개별 이미지 alt") }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp), modifier = Modifier.height(38.dp), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = if(isDarkMode) Color(0xFF37474F) else PastelNavyLight, contentColor = if(isDarkMode) Color.White else PastelNavy)) { Text("복사", fontSize = 12.sp) }
                            }
                        }
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { copyToClipboard(context, alts.joinToString("\n") { it.alt }, "전체 이미지 alt") }) { Text("모두 복사", color = PastelNavy) }
                        Button(onClick = {
                            val allAlts = alts.map { it.alt }.toSet()
                            if (isImageAltBlacklistDialogOpen) {
                                imageAltBlacklistDraftText = DcconFilter.addSelectedImageAltRefs(imageAltBlacklistDraftText, alts, allAlts)
                            } else {
                                val merged = DcconFilter.addSelectedImageAltRefs(imageAltBlacklistText, alts, allAlts)
                                imageAltBlacklistText = merged
                                botPref.edit().putStringSet("image_alt_blacklist", merged.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()).apply()
                            }
                            extractedAltsList = null
                            Toast.makeText(context, "이미지 alt ${alts.size}개를 추가했습니다.", Toast.LENGTH_SHORT).show()
                        }, colors = ButtonDefaults.buttonColors(containerColor = PastelNavy)) { Text("모두 추가", color = Color.White) }
                    }
                },
                dismissButton = { TextButton(onClick = { extractedAltsList = null }) { Text("닫기", color = subTextColor) } }
            )
        }

        if (isDcconBlacklistDialogOpen) {
            val draftGroups = DcconFilter.groupBlacklistEntries(dcconBlacklistDraftText)
            val activeGroup = selectedDcconGroupName?.let { name -> draftGroups.firstOrNull { it.packageName == name } }
            val visibleTokens = activeGroup?.tokens ?: draftGroups.flatMap { it.tokens }
            val allVisibleSelected = visibleTokens.isNotEmpty() && visibleTokens.all { it in selectedDcconTokens }
            AlertDialog(
                containerColor = dialogBgColor, titleContentColor = textColor, textContentColor = textColor,
                onDismissRequest = { isDcconBlacklistDialogOpen = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (activeGroup != null) {
                            IconButton(onClick = { selectedDcconGroupName = null; selectedDcconTokens = emptySet() }) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "세트 목록", tint = PastelNavy)
                            }
                        }
                        Column {
                            Text(
                                if (activeGroup == null) "차단된 디시콘 세트 (${draftGroups.size}개)" else "${activeGroup.packageName} (${activeGroup.tokens.size}개)",
                                fontWeight = FontWeight.Bold
                            )
                            if (activeGroup == null) Text("세트명 기준으로 묶어 관리합니다.", fontSize = 12.sp, color = subTextColor)
                        }
                    }
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (isDcconExtractInputVisible && activeGroup == null) {
                            Text("차단하고 싶은 디시콘이 있는 글 주소를 입력하세요.", color = subTextColor, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                            Row {
                                OutlinedTextField(
                                    value = dcconExtractUrlText,
                                    onValueChange = { dcconExtractUrlText = it },
                                    placeholder = { Text("https://...", fontSize = 12.sp) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f).height(50.dp),
                                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(onClick = {
                                    if (dcconExtractUrlText.isNotBlank()) {
                                        isExtractingDccons = true
                                        coroutineScope.launch(Dispatchers.IO) {
                                            try {
                                                val doc = fetchPostDocument(dcconExtractUrlText)
                                                val bodyRefs = DcconFilter.extractDcconRefs(doc.select(".write_div").outerHtml())
                                                    .map { it.copy(source = "본문") }
                                                val commentRefs = fetchCommentDcconRefs(dcconExtractUrlText, doc.html())
                                                val dccons = (bodyRefs + commentRefs).distinctBy { "${it.source}:${it.token}" }
                                                withContext(Dispatchers.Main) {
                                                    if (dccons.isEmpty()) extractedAltsError = "본문/댓글에서 디시콘 URL을 찾지 못했습니다." else extractedDcconsList = dccons
                                                    dcconExtractUrlText = ""
                                                    isExtractingDccons = false
                                                }
                                            } catch (e: Exception) {
                                                withContext(Dispatchers.Main) {
                                                    extractedAltsError = "디시콘 추출 오류: 주소를 확인해주세요. (${e.message})"
                                                    isExtractingDccons = false
                                                }
                                            }
                                        }
                                    }
                                }, enabled = !isExtractingDccons, colors = ButtonDefaults.buttonColors(containerColor = PastelNavy), modifier = Modifier.height(50.dp)) {
                                    Text(if (isExtractingDccons) "..." else "추출", color = Color.White)
                                }
                            }
                            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { dcconExtractUrlText = ""; isDcconExtractInputVisible = false }) { Text("취소", color = subTextColor) }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        } else if (isDcconAddInputVisible && activeGroup == null) {
                            OutlinedTextField(
                                value = dcconBlacklistAddText,
                                onValueChange = { dcconBlacklistAddText = it },
                                placeholder = { Text("dccon.php?no= 토큰 또는 URL\n여러 개는 줄바꿈으로 입력", fontSize = 12.sp) },
                                minLines = 3,
                                maxLines = 5,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor)
                            )
                            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { dcconBlacklistAddText = ""; isDcconAddInputVisible = false }) { Text("취소", color = subTextColor) }
                                Button(onClick = {
                                    dcconBlacklistDraftText = DcconFilter.addBlacklistEntries(dcconBlacklistDraftText, dcconBlacklistAddText)
                                    dcconBlacklistAddText = ""
                                    isDcconAddInputVisible = false
                                }, colors = ButtonDefaults.buttonColors(containerColor = PastelNavy)) { Text("추가", color = Color.White) }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        } else if (activeGroup == null) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { isDcconExtractInputVisible = true; isDcconAddInputVisible = false }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Filled.Search, contentDescription = null, tint = PastelNavy)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("글 주소로 추가", color = PastelNavy)
                                }
                                OutlinedButton(onClick = { isDcconAddInputVisible = true; isDcconExtractInputVisible = false }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Filled.Add, contentDescription = null, tint = PastelNavy)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("직접 추가", color = PastelNavy)
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        if (visibleTokens.isNotEmpty()) {
                            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = allVisibleSelected, onCheckedChange = { checked ->
                                    selectedDcconTokens = DcconFilter.toggleVisibleTokenSelection(selectedDcconTokens, visibleTokens, checked)
                                })
                                Text(if (activeGroup == null) "전체 세트 선택" else "전체 디시콘 선택", color = textColor, modifier = Modifier.weight(1f))
                                if (selectedDcconTokens.isNotEmpty()) Text("${selectedDcconTokens.size}개 선택", color = subTextColor, fontSize = 12.sp)
                            }
                        }

                        if (draftGroups.isEmpty()) {
                            Text("등록된 디시콘이 없습니다.", color = subTextColor, modifier = Modifier.padding(vertical = 24.dp))
                        } else if (activeGroup == null) {
                            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                                items(draftGroups.size) { index ->
                                    val group = draftGroups[index]
                                    val checked = group.tokens.all { it in selectedDcconTokens }
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(checked = checked, onCheckedChange = { selected ->
                                            selectedDcconTokens = DcconFilter.toggleVisibleTokenSelection(selectedDcconTokens, group.tokens, selected)
                                        })
                                        DcconPreviewImage(group.representativeToken, modifier = Modifier.size(64.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(
                                            modifier = Modifier.weight(1f).clickable {
                                                selectedDcconGroupName = group.packageName
                                                selectedDcconTokens = emptySet()
                                            }
                                        ) {
                                            Text(group.packageName, color = textColor, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("차단된 디시콘 ${group.tokens.size}개", color = subTextColor, fontSize = 12.sp)
                                        }
                                        IconButton(onClick = { selectedDcconGroupName = group.packageName; selectedDcconTokens = emptySet() }) {
                                            Icon(Icons.Filled.ChevronRight, contentDescription = "개별 디시콘 보기", tint = PastelNavy)
                                        }
                                        IconButton(onClick = { dcconDeleteConfirmTokens = group.tokens.toSet() }) {
                                            Icon(Icons.Filled.Delete, contentDescription = "세트 삭제", tint = warningRed)
                                        }
                                    }
                                }
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                                items(activeGroup.tokens.size) { index ->
                                    val token = activeGroup.tokens[index]
                                    val checked = token in selectedDcconTokens
                                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = checked, onCheckedChange = { selected ->
                                            selectedDcconTokens = if (selected) selectedDcconTokens + token else selectedDcconTokens - token
                                        })
                                        DcconPreviewImage(token, modifier = Modifier.size(72.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("디시콘 ${index + 1}", color = subTextColor, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        IconButton(onClick = { dcconDeleteConfirmTokens = setOf(token) }) {
                                            Icon(Icons.Filled.Delete, contentDescription = "삭제", tint = warningRed)
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (selectedDcconTokens.isNotEmpty()) {
                            OutlinedButton(onClick = { dcconDeleteConfirmTokens = selectedDcconTokens }) { Text(if (activeGroup == null) "선택 세트 삭제" else "선택 삭제", color = warningRed) }
                        }
                        Button(onClick = {
                            val normalized = DcconFilter.normalizeBlacklistText(dcconBlacklistDraftText)
                            dcconBlacklistText = normalized
                            botPref.edit().putStringSet("dccon_blacklist", normalized.split("\n").map{it.trim()}.filter{it.isNotEmpty()}.toSet()).apply()
                            isDcconBlacklistDialogOpen = false
                        }, colors = ButtonDefaults.buttonColors(containerColor = PastelNavy)) { Text("저장", color = Color.White) }
                    }
                },
                dismissButton = { TextButton(onClick = { isDcconBlacklistDialogOpen = false }) { Text("취소", color = subTextColor) } }
            )
        }

        val pendingDcconDeletes = dcconDeleteConfirmTokens
        if (pendingDcconDeletes != null) {
            AlertDialog(
                containerColor = dialogBgColor, titleContentColor = textColor, textContentColor = textColor,
                onDismissRequest = { dcconDeleteConfirmTokens = null },
                title = { Text(if (pendingDcconDeletes.size == 1) "디시콘 삭제" else "디시콘 선택 삭제", fontWeight = FontWeight.Bold) },
                text = { Text(if (pendingDcconDeletes.size == 1) "이 디시콘을 차단 목록에서 제거할까요?" else "선택한 디시콘 ${pendingDcconDeletes.size}개를 차단 목록에서 제거할까요?") },
                confirmButton = { Button(onClick = {
                    dcconBlacklistDraftText = DcconFilter.removeBlacklistTokens(dcconBlacklistDraftText, pendingDcconDeletes)
                    selectedDcconTokens = selectedDcconTokens - pendingDcconDeletes
                    dcconDeleteConfirmTokens = null
                }, colors = ButtonDefaults.buttonColors(containerColor = warningRed)) { Text("삭제", color = Color.White) } },
                dismissButton = { TextButton(onClick = { dcconDeleteConfirmTokens = null }) { Text("취소", color = subTextColor) } }
            )
        }

        val dccons = extractedDcconsList
        if (dccons != null) {
            AlertDialog(
                containerColor = dialogBgColor, titleContentColor = textColor, textContentColor = textColor,
                onDismissRequest = { extractedDcconsList = null },
                title = { Text("디시콘 추출 결과 (${dccons.size}개)", fontWeight = FontWeight.Bold) },
                text = {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp)) {
                        items(dccons.size) { index ->
                            val ref = dccons[index]
                            val label = listOfNotNull(ref.source, ref.label).joinToString(" / ").ifBlank { "디시콘" }
                            val blacklistForCheck = if (isDcconBlacklistDialogOpen) dcconBlacklistDraftText else dcconBlacklistText
                            val isBlocked = DcconFilter.isTokenBlocked(blacklistForCheck, ref.token)
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                DcconPreviewImage(ref.token, modifier = Modifier.size(76.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(label, color = textColor, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (isBlocked) "이미 차단 목록에 있음" else "토큰 ${index + 1}", color = subTextColor, fontSize = 11.sp)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                if (isBlocked) {
                                    AssistChip(onClick = {}, label = { Text("차단됨", fontSize = 12.sp) }, enabled = false)
                                } else {
                                    Button(
                                        onClick = {
                                            isAddingDcconPackage = true
                                            coroutineScope.launch(Dispatchers.IO) {
                                                try {
                                                    val detail = dcconPackageCache[ref.token] ?: fetchDcconPackageDetail(ref.token)?.also { dcconPackageCache[ref.token] = it }
                                                    val merged = DcconFilter.addSingleTokenWithPackageTitle(dcconBlacklistDraftText, ref.token, detail)
                                                    withContext(Dispatchers.Main) {
                                                        dcconBlacklistDraftText = merged
                                                        isAddingDcconPackage = false
                                                        Toast.makeText(context, "디시콘 1개를 차단 목록에 추가했습니다. 저장을 눌러 반영하세요.", Toast.LENGTH_SHORT).show()
                                                    }
                                                } catch (e: Exception) {
                                                    withContext(Dispatchers.Main) {
                                                        dcconBlacklistDraftText = DcconFilter.addSingleTokenWithPackageTitle(dcconBlacklistDraftText, ref.token, null)
                                                        isAddingDcconPackage = false
                                                        Toast.makeText(context, "세트명 확인은 실패했지만 디시콘 1개를 추가했습니다.", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        },
                                        enabled = !isAddingDcconPackage,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                        modifier = Modifier.height(38.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = PastelNavy)
                                    ) { Text("차단", fontSize = 12.sp, color = Color.White) }
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Button(
                                    onClick = {
                                        isAddingDcconPackage = true
                                        coroutineScope.launch(Dispatchers.IO) {
                                            try {
                                                val detail = dcconPackageCache[ref.token] ?: fetchDcconPackageDetail(ref.token)?.also { dcconPackageCache[ref.token] = it }
                                                if (detail == null) {
                                                    withContext(Dispatchers.Main) {
                                                        isAddingDcconPackage = false
                                                        extractedAltsError = "디시콘 세트 정보를 찾지 못했습니다."
                                                    }
                                                } else {
                                                    val beforeCount = dcconBlacklistDraftText.lines().mapNotNull { DcconFilter.normalizeBlacklistEntry(it.substringBefore("#")) }.toSet().size
                                                    val merged = DcconFilter.mergePackageTokensIntoBlacklist(dcconBlacklistDraftText, detail)
                                                    val afterCount = merged.lines().mapNotNull { DcconFilter.normalizeBlacklistEntry(it.substringBefore("#")) }.toSet().size
                                                    withContext(Dispatchers.Main) {
                                                        dcconBlacklistDraftText = merged
                                                        isAddingDcconPackage = false
                                                        Toast.makeText(context, if (afterCount > beforeCount) "${detail.title} 세트 ${afterCount - beforeCount}개를 추가했습니다. 저장을 눌러 반영하세요." else "이미 차단된 세트입니다.", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                withContext(Dispatchers.Main) {
                                                    isAddingDcconPackage = false
                                                    extractedAltsError = "디시콘 세트 추가 오류: ${e.message}"
                                                }
                                            }
                                        }
                                    },
                                    enabled = !isAddingDcconPackage,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                    modifier = Modifier.height(38.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = if(isDarkMode) Color(0xFF37474F) else PastelNavyLight, contentColor = if(isDarkMode) Color.White else PastelNavy)
                                ) { Text("세트 차단", fontSize = 12.sp) }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { extractedDcconsList = null }) { Text("닫기", color = PastelNavy) } }
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
                            logMessages.add(parseBotLogEntry("[${getCurrentTimeStr()}] 공용 DB 초기화됨!"))
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
                            logMessages.add(parseBotLogEntry("[${getCurrentTimeStr()}] [개발자] 강제 종료 테스트 실행"))
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